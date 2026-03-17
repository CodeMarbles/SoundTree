package com.treecast.app.ui.record

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.treecast.app.R
import com.treecast.app.databinding.FragmentRecordBinding
import com.treecast.app.service.RecordingService
import com.treecast.app.ui.MainActivity
import com.treecast.app.ui.MainViewModel
import com.treecast.app.ui.common.TopicPickerBottomSheet
import com.treecast.app.ui.waveform.WaveformMark
import com.treecast.app.util.AppVolume
import com.treecast.app.util.Icons
import com.treecast.app.util.StorageVolumeHelper
import com.treecast.app.util.themeColor
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordFragment : Fragment() {

    private var _binding: FragmentRecordBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    private var selectedTopicId: Long? = null

    // ── Double-tap cancel tracking ────────────────────────────────────
    private var lastCancelTapMs: Long = 0L
    private val doubleTapWindowMs = 500L

    // ── In-progress recording display name ────────────────────────────
    // Set in doStartRecording() to a timestamp string (the auto-generated default).
    // Updated by showRenameDialog() to whatever the user types.
    // Used as the recording title on save.
    private var currentRecordingDisplayName: String = ""

    // True once the user has explicitly renamed via showRenameDialog().
    // When true, stopAndSave() uses currentRecordingDisplayName verbatim
    // (no "Recording – " prefix). When false (auto-stamp), the prefix is applied.
    private var userHasRenamedRecording: Boolean = false

    // ── Recording service ─────────────────────────────────────────────
    private var recordingService: RecordingService? = null
    private var isBound = false
    private var pendingQuickRecord = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            recordingService = (binder as RecordingService.RecordingBinder).getService()
            isBound = true
            observeServiceState()
            if (pendingQuickRecord) {
                pendingQuickRecord = false
                startRecording()
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            recordingService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else Toast.makeText(requireContext(), "Microphone permission required", Toast.LENGTH_LONG).show()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
        setupNewControls()
        setupTopicHeader()
        setupTimerShadow()
        setupMultiLineWaveform()
        observeLock()
        bindRecordingService()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (isBound) {
            requireContext().unbindService(serviceConnection)
            isBound = false
        }
        _binding = null
    }

    fun triggerQuickRecord() {
        if (isBound && recordingService != null) startRecording()
        else pendingQuickRecord = true
    }

    /**
     * Called by MainActivity when the Mini Recorder's save button is tapped
     * from outside the Record tab. Delegates to the existing stopAndSave() path.
     */
    fun triggerSaveFromExternal() {
        stopAndSave()
    }

    // ── Setup ─────────────────────────────────────────────────────────
    private fun setupButtons() {
        binding.btnRecord.setOnClickListener {
            when (recordingService?.state?.value) {
                RecordingService.State.IDLE      -> checkPermissionAndRecord()
                RecordingService.State.RECORDING -> pauseRecording()
                RecordingService.State.PAUSED    -> resumeRecording()
                null -> checkPermissionAndRecord()
            }
        }

        binding.btnSave.setOnClickListener { stopAndSave() }

        // Drop mark button — shows extended mark controls after dropping.
        binding.btnDropMark.setOnClickListener {
            recordingService?.dropMark()
            showMarkControls(recordingService?.elapsedMs?.value ?: 0L)
        }

        // Cancel requires a double-tap to prevent accidental presses.
        binding.btnCancel.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastCancelTapMs <= doubleTapWindowMs) {
                cancelRecording()
            } else {
                lastCancelTapMs = now
                Toast.makeText(requireContext(), "Tap again to cancel", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnLockScreen.setOnClickListener { viewModel.setLocked(true) }
    }

    // ── New controls (dead-space, mark extended, recording name) ──────
    private fun setupNewControls() {
        // ── Mark extended controls ────────────────────────────────────
        // Confirm: UI-only dismiss — no data written.
        binding.btnMarkConfirm.setOnClickListener {
            viewModel.commitMarkNudge()
            hideMarkControls()
        }

        // Delete: remove the currently selected recording mark.
        binding.btnMarkDelete.setOnClickListener {
            viewModel.deleteSelectedRecordingMark()
            hideMarkControls()
        }

        // Nudge: routed through ViewModel → nudgeBack/ForwardEvent → service,
        // consistent with the Mini Recorder nudge buttons.
        binding.btnMarkNudgeBack.setOnClickListener    { viewModel.requestNudgeBack()    }
        binding.btnMarkNudgeForward.setOnClickListener { viewModel.requestNudgeForward() }

        // ── Recording name zone ───────────────────────────────────────
        binding.recordingNameZone.setOnClickListener { showRenameDialog() }
    }

    // ── Multi-line waveform ───────────────────────────────────────────
    private fun setupMultiLineWaveform() {
        binding.multiLineWaveformView.secondsPerLine  = 300   // 5-minute lines
        binding.multiLineWaveformView.showPlayedSplit = false // no playhead on Record tab
        // onTimeSelected intentionally not wired — tapping the waveform during
        // recording has no defined action yet. Wire here when mark-seek is added.
    }

    // ── Timer text shadow (legibility over waveform) ──────────────────
    private fun setupTimerShadow() {
        binding.tvTimer.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        binding.tvTimer.setShadowLayer(
            16f, 0f, 0f,
            requireContext().themeColor(R.attr.colorBackground)
        )
    }

    // ── Topic header ───────────────────────────────────────────────────
    private fun setupTopicHeader() {
        updateRecordTopicHeader(null, getString(R.string.label_unsorted), Icons.INBOX)

        childFragmentManager.setFragmentResultListener(
            TopicPickerBottomSheet.REQUEST_KEY, viewLifecycleOwner
        ) { _, bundle ->
            val topicId = TopicPickerBottomSheet.topicIdFromBundle(bundle)
            selectedTopicId = topicId
            viewModel.setRecordingTopicId(topicId)
            val topic = viewModel.allTopics.value.firstOrNull { it.id == topicId }
            updateRecordTopicHeader(
                topicId,
                topic?.name ?: getString(R.string.label_unsorted),
                topic?.icon ?: Icons.INBOX
            )
            val svc = recordingService
            if (svc != null && svc.state.value != RecordingService.State.IDLE) {
                svc.setTopic(topicId)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.recordingTopicId.collect { topicId ->
                        selectedTopicId = topicId
                        val svc = recordingService
                        if (svc != null && svc.state.value != RecordingService.State.IDLE) {
                            svc.setTopic(topicId)
                        }
                        val topic = viewModel.allTopics.value.firstOrNull { it.id == topicId }
                        updateRecordTopicHeader(
                            topicId,
                            topic?.name ?: getString(R.string.label_unsorted),
                            topic?.icon ?: Icons.INBOX
                        )
                    }
                }
                launch {
                    viewModel.allTopics.collect { topics ->
                        if (selectedTopicId != null && topics.none { it.id == selectedTopicId }) {
                            selectedTopicId = null
                            updateRecordTopicHeader(null, "Uncategorised", Icons.INBOX)
                            recordingService?.setTopic(null)
                        }
                    }
                }
            }
        }

        binding.topicHeader.setOnClickListener {
            TopicPickerBottomSheet.newInstance(selectedTopicId)
                .show(childFragmentManager, "topic_picker")
        }
    }

    /** Syncs the topic header views with the given topic state. */
    private fun updateRecordTopicHeader(topicId: Long?, name: String, icon: String) {
        binding.tvRecordTopicIcon.text = icon
        binding.tvRecordTopicName.text = name
    }

    private fun observeServiceState() {
        val svc = recordingService ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    svc.state.collect { updateUiForState(it) }
                }

                // ── Push recording state to ViewModel (drives Mini Recorder) ─
                launch {
                    svc.state.collect { state ->
                        viewModel.setRecordingState(state)
                        if (state == RecordingService.State.IDLE) {
                            viewModel.setRecordingElapsedMs(0L)
                            viewModel.setRecordingMarks(emptyList())
                            viewModel.resetMarkNudgeLock()
                        }
                    }
                }

                // ── Elapsed time → timer display + ViewModel ──────────
                launch {
                    svc.elapsedMs.collect { ms ->
                        binding.tvTimer.text = formatDuration(ms)
                        viewModel.setRecordingElapsedMs(ms)
                    }
                }

                // ── Push pending marks list to ViewModel ──────────────
                launch {
                    svc.pendingMarksFlow.collect { marks ->
                        viewModel.setRecordingMarks(marks)
                    }
                }

                // ── Keep mark controls timestamp live after nudges ─────
                // Whenever the selected mark's position changes (i.e. after a
                // nudge), update the timestamp chip in the panel to reflect
                // its new location.
                launch {
                    combine(
                        viewModel.recordingMarks,
                        viewModel.selectedRecordingMarkIndex
                    ) { marks, idx ->
                        if (idx >= 0 && idx < marks.size) marks[idx] else null
                    }.collect { stampMs ->
                        if (stampMs != null &&
                            binding.markExtendedControls.visibility == View.VISIBLE) {
                            binding.tvMarkTimestamp.text = formatDuration(stampMs)
                        }
                    }
                }

                // ── Bridge toggle-pause events from Mini Recorder ─────
                launch {
                    viewModel.toggleRecordingPauseEvent.collect {
                        when (recordingService?.state?.value) {
                            RecordingService.State.RECORDING -> pauseRecording()
                            RecordingService.State.PAUSED    -> resumeRecording()
                            else -> { /* IDLE handled in MainActivity via triggerQuickRecord() */ }
                        }
                    }
                }

                // ── Bridge nudge events (carry target mark index) ─────
                launch {
                    viewModel.nudgeBackEvent.collect { event ->
                        recordingService?.nudgeMarkBack(event.secs, event.markIndex)
                    }
                }
                launch {
                    viewModel.nudgeForwardEvent.collect { event ->
                        recordingService?.nudgeMarkForward(event.secs, event.markIndex)
                    }
                }

                // ── Notification save → post-save navigation ──────────
                launch {
                    svc.notificationSaveEvent.collect { saved ->
                        if (viewModel.jumpToLibraryOnSave.value) {
                            viewModel.selectRecording(saved.recordingId)
                            (requireActivity() as? MainActivity)
                                ?.navigateToLibraryForRecording(saved.topicId)
                        }
                    }
                }

                // ── Bridge drop/delete mark events from Mini Recorder ─
                launch {
                    viewModel.dropMarkEvent.collect {
                        recordingService?.dropMark()
                    }
                }
                launch {
                    viewModel.deleteMarkEvent.collect { index ->
                        recordingService?.removeMarkAt(index)
                    }
                }

                // ── MultiLineWaveformView: live amplitude feed ────────
                launch {
                    svc.amplitude.collect { amp ->
                        val elapsedMs = svc.elapsedMs.value
                        binding.multiLineWaveformView.pushAmplitude(
                            amplitude = amp / 32767f,
                            elapsedMs = elapsedMs
                        )
                        // Legacy scrolling waveform — kept until replaced.
                        binding.waveformView.pushAmplitude(amp)
                    }
                }

                // ── MultiLineWaveformView: mark overlays ──────────────
                launch {
                    svc.pendingMarksFlow.collect { timestamps ->
                        binding.multiLineWaveformView.setMarks(
                            timestamps.map { ts -> WaveformMark(id = ts, positionMs = ts) }
                        )
                    }
                }

                // ── MultiLineWaveformView: selected mark highlight ─────
                launch {
                    combine(
                        viewModel.recordingMarks,
                        viewModel.selectedRecordingMarkIndex
                    ) { marks, idx ->
                        marks.getOrNull(idx)
                    }.collect { selectedTs ->
                        binding.multiLineWaveformView.setSelectedMarkId(selectedTs)
                    }
                }

                launch {
                    svc.amplitude.collect { amp ->
                        // Guard against stale replay emissions arriving after clearLiveData().
                        if (svc.state.value == RecordingService.State.IDLE) return@collect
                        binding.multiLineWaveformView.pushAmplitude(
                            amplitude = amp / 32767f,
                            elapsedMs = svc.elapsedMs.value
                        )
                        binding.waveformView.pushAmplitude(amp)
                    }
                }
            }
        }
    }

    private fun observeLock() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isLocked.collect { locked ->
                        binding.btnLockScreen.alpha = if (locked) 0.3f else 1f
                    }
                }
            }
        }
    }

    // ── Recording state UI ────────────────────────────────────────────
    private fun toDp(pixels: Float): Int {
        return (pixels * resources.displayMetrics.density).toInt()
    }
    private fun updateUiForState(state: RecordingService.State) {
        val density = resources.displayMetrics.density

        when (state) {
            RecordingService.State.IDLE -> {
                // Circle: red, bordered
                with(binding.btnRecord) {
                    backgroundTintList = ColorStateList.valueOf(requireContext().themeColor(R.attr.colorRecordActive))

                    icon = null
                    //iconTint = ColorStateList.valueOf(Color.WHITE)

                    text = "REC"
                    textSize = 18f
                }
                // Flanking buttons + hint label hidden
                binding.btnCancel.visibility    = View.GONE
                binding.tvCancelHint.visibility = View.GONE
                binding.btnSave.visibility      = View.GONE

                // Mark/lock row hidden; mark extended controls collapsed
                binding.markLockContainer.visibility = View.GONE
                hideMarkControls()

                // Header: topic zone only
                binding.headerDivider.visibility     = View.GONE
                binding.recordingNameZone.visibility = View.GONE

                lastCancelTapMs = 0L
                binding.tvTimer.text = "0:00"

                binding.multiLineWaveformView.clearLiveData()
            }

            RecordingService.State.RECORDING -> {
                // Circle: yellow (pause colour), no border
                with(binding.btnRecord) {
                    backgroundTintList = ColorStateList.valueOf(requireContext().themeColor(R.attr.colorRecordPause))

                    setIconResource(R.drawable.ic_pause)
                    iconSize = toDp(48f)
                    iconTint = ColorStateList.valueOf(Color.WHITE)
                    iconPadding = 0

                    text = null
                }

                // Flanking buttons + hint label visible
                binding.btnCancel.visibility    = View.VISIBLE
                binding.tvCancelHint.visibility = View.VISIBLE
                binding.btnSave.visibility      = View.VISIBLE

                // Mark/lock row visible
                binding.markLockContainer.visibility = View.VISIBLE

                // Header: show recording name
                binding.headerDivider.visibility     = View.VISIBLE
                binding.recordingNameZone.visibility = View.VISIBLE
                binding.tvRecordingName.text = currentRecordingDisplayName
            }

            RecordingService.State.PAUSED -> {
                // Circle: accent blue, bordered
                with(binding.btnRecord) {
                    backgroundTintList = ColorStateList.valueOf(Color.BLUE)

                    setIconResource(R.drawable.ic_resume_circle)
                    iconSize = toDp(24f)
                    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_TOP
                    iconPadding = toDp(5f)
                    iconTint = ColorStateList.valueOf(Color.WHITE)

                    text = "RESUME"
                    textSize = 9f
                }

                // Flanking buttons + hint remain visible (set in RECORDING, left as-is)
                // Mark/lock row remains visible
                // Header name zone remains visible
            }
        }
    }

    // ── Mark extended controls ────────────────────────────────────────
    private fun showMarkControls(stampMs: Long) {
        binding.tvMarkTimestamp.text = formatDuration(stampMs)
        binding.markExtendedControls.visibility = View.VISIBLE
    }

    private fun hideMarkControls() {
        binding.markExtendedControls.visibility = View.GONE
    }

    // ── Rename dialog ─────────────────────────────────────────────────
    private fun showRenameDialog() {
        val editText = EditText(requireContext()).apply {
            setText(currentRecordingDisplayName)
            selectAll()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Rename Recording")
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    // User provided a name — use it verbatim on save.
                    currentRecordingDisplayName = newName
                    userHasRenamedRecording = true
                    binding.tvRecordingName.text = newName
                } else {
                    // User cleared the field — fall back to a fresh auto-generated
                    // timestamp and treat the recording as un-renamed so stopAndSave()
                    // will prepend the "Recording – " prefix.
                    val generated = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                        .format(Date(System.currentTimeMillis()))
                    currentRecordingDisplayName = generated
                    userHasRenamedRecording = false
                    binding.tvRecordingName.text = generated
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Recording actions ─────────────────────────────────────────────
    private fun checkPermissionAndRecord() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun bindRecordingService() {
        val intent = Intent(requireContext(), RecordingService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun startRecording() {
        val volume = viewModel.resolveRecordingVolume()

        ContextCompat.startForegroundService(
            requireContext(),
            Intent(requireContext(), RecordingService::class.java)
        )

        val svc = recordingService ?: run {
            bindRecordingService()
            pendingQuickRecord = true
            return
        }
        svc.setStorageDir(volume.rootDir, volume.uuid)

        if (!svc.hasSufficientFreeSpace()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Low Storage")
                .setMessage(
                    "Only ${AppVolume.formatBytes(volume.freeBytes)} free on " +
                            "\"${volume.label}\". The recording may be cut short.\n\n" +
                            "Continue anyway?"
                )
                .setPositiveButton("Continue") { _, _ ->
                    doStartRecording(svc)
                }
                .setNegativeButton("Cancel") { _, _ ->
                    svc.setStorageDir(
                        StorageVolumeHelper.getDefaultVolume(requireContext()).rootDir,
                        StorageVolumeHelper.UUID_PRIMARY
                    )
                }
                .show()
        } else {
            doStartRecording(svc)
        }
    }

    /** Calls through to the service after all pre-flight checks pass. */
    private fun doStartRecording(svc: RecordingService) {
        // Reset the display name to the current timestamp and clear any prior rename.
        currentRecordingDisplayName = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
        userHasRenamedRecording = false
        svc.startRecording(topicId = viewModel.recordingTopicId.value)
    }

    private fun pauseRecording()  { recordingService?.pauseRecording() }
    private fun resumeRecording() { recordingService?.resumeRecording() }

    private fun stopAndSave() {
        val svc = recordingService ?: return
        val result = svc.stopRecording()
        if (result.filePath != null && File(result.filePath).exists()) {
            val stamp = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                .format(Date(System.currentTimeMillis()))

            val resolvedTopicId = viewModel.recordingTopicId.value?.takeIf { id ->
                viewModel.allTopics.value.any { it.id == id }
            }

            // If the user renamed the recording, use their title verbatim.
            // If they didn't rename (auto-timestamp default), apply the "Recording – " prefix.
            val title = when {
                userHasRenamedRecording && currentRecordingDisplayName.isNotEmpty() ->
                    currentRecordingDisplayName
                currentRecordingDisplayName.isNotEmpty() ->
                    "Recording – $currentRecordingDisplayName"
                else ->
                    "Recording – $stamp"
            }

            val savedDeferred = viewModel.saveRecordingWithMarks(
                filePath          = result.filePath,
                durationMs        = result.durationMs,
                fileSizeBytes     = File(result.filePath).length(),
                title             = title,
                topicId           = resolvedTopicId,
                markTimestamps    = result.markTimestamps,
                storageVolumeUuid = result.storageVolumeUuid
            )
            Toast.makeText(requireContext(), "Saved!", Toast.LENGTH_SHORT).show()

            if (viewModel.jumpToLibraryOnSave.value) {
                lifecycleScope.launch {
                    val newId = savedDeferred.await()
                    viewModel.selectRecording(newId)
                    (requireActivity() as? MainActivity)
                        ?.navigateToLibraryForRecording(resolvedTopicId)
                }
            }
        } else {
            Toast.makeText(requireContext(), "Nothing recorded", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelRecording() {
        val svc = recordingService ?: return
        val result = svc.stopRecording()
        if (result.filePath != null) {
            File(result.filePath).delete()
        }
        binding.multiLineWaveformView.clearLiveData()
        binding.waveformView.clear()
        selectedTopicId = null
        lastCancelTapMs = 0L
        currentRecordingDisplayName = ""
        userHasRenamedRecording = false
        Toast.makeText(requireContext(), "Recording cancelled", Toast.LENGTH_SHORT).show()
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}