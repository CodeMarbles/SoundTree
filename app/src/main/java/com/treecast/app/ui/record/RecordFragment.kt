package com.treecast.app.ui.record

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.treecast.app.R
import com.treecast.app.databinding.FragmentRecordBinding
import com.treecast.app.service.RecordingService
import com.treecast.app.ui.MainActivity
import com.treecast.app.ui.MainViewModel
import com.treecast.app.util.AppVolume
import com.treecast.app.util.Icons
import com.treecast.app.util.StorageVolumeHelper
import com.treecast.app.util.themeColor
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordFragment : Fragment() {

    private var _binding: FragmentRecordBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    private var selectedTopicId: Long? = null

    // ── Double-tap cancel tracking ────────────────────────────────────
    private var lastCancelTapMs: Long = 0L
    private val doubleTapWindowMs = 500L

    // ── Recording service ─────────────────────────────────────────────
    private var recordingService: RecordingService? = null
    private var isBound = false
    private var pendingQuickRecord = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
//            Log.d("TC_DEBUG", "RecordFragment: onServiceConnected")
            recordingService = (binder as RecordingService.RecordingBinder).getService()
            isBound = true
            observeServiceState()
            if (pendingQuickRecord) {
                pendingQuickRecord = false
                startRecording()
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
//            Log.w("TC_DEBUG", "RecordFragment: onServiceDisconnected")
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
        setupTopicPicker()
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

        binding.btnStopSave.setOnClickListener { stopAndSave() }

        // Drop mark button — in-app counterpart of the notification action.
        // Delegates directly to the service via Binder.
        binding.btnDropMark.setOnClickListener {
            recordingService?.dropMark()
            binding.waveformView.pushMark()
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

    private fun setupTopicPicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.allTopics.collect { topics ->
                        binding.topicPicker.setTopics(topics)
                        // If the selected topic was deleted by the user, reset the picker
                        // and the service back to Uncategorised (null) automatically.
                        if (selectedTopicId != null && topics.none { it.id == selectedTopicId }) {
                            selectedTopicId = null
                            binding.topicPicker.setSelectedTopic(null, "Uncategorised", Icons.INBOX)
                            recordingService?.setTopic(null)
                        }
                    }
                }
            }
        }
        binding.topicPicker.onTopicSelected = { topicId ->
            selectedTopicId = topicId
            val svc = recordingService
            if (svc != null && svc.state.value != RecordingService.State.IDLE) {
                svc.setTopic(topicId)
            }
        }
    }


    private fun observeServiceState() {
        val svc = recordingService ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    svc.state.collect { updateUiForState(it) }
                }
                launch {
                    svc.elapsedMs.collect { ms ->
                        binding.tvTimer.text = formatDuration(ms)
                    }
                }
                launch {
                    svc.amplitude.collect { amp ->
                        binding.waveformView.pushAmplitude(amp)
                    }
                }
                launch {
                    svc.pendingMarkCount.collect { count ->
                        if (count > 0) {
                            binding.tvMarkCount.text = "$count ${if (count == 1) "mark" else "marks"}"
                            binding.tvMarkCount.visibility = View.VISIBLE
                        } else {
                            binding.tvMarkCount.visibility = View.GONE
                        }
                    }
                }
                launch {
                    // Observe saves that were triggered from the notification action button.
                    // The service has already written to the database by the time this
                    // emits, so we only need to handle post-save navigation here — no
                    // second DB write should occur.
                    svc.notificationSaveEvent.collect { saved ->
                        binding.topicPicker.collapse()
                        if (viewModel.jumpToLibraryOnSave.value) {
                            viewModel.selectRecording(saved.recordingId)
                            (requireActivity() as? MainActivity)
                                ?.navigateToLibraryForRecording(saved.topicId)
                        }
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
    private fun updateUiForState(state: RecordingService.State) {
        when (state) {
            RecordingService.State.IDLE -> {
                binding.btnRecord.text = "● REC"
                binding.btnRecord.backgroundTintList =
                    ColorStateList.valueOf(requireContext().themeColor(R.attr.colorRecordActive))
                binding.stopSaveContainer.visibility = View.GONE
                binding.markLockContainer.visibility = View.GONE
                lastCancelTapMs = 0L
                binding.tvTimer.text = "0:00"
                binding.waveformView.clear()
                binding.topicPicker.collapse()
            }
            RecordingService.State.RECORDING -> {
                binding.btnRecord.text = "⏸  Pause"
                binding.btnRecord.backgroundTintList =
                    ColorStateList.valueOf(requireContext().themeColor(R.attr.colorRecordPause))
                //requireContext().getColorStateList(android.R.color.holo_orange_light)
                binding.stopSaveContainer.visibility = View.VISIBLE
                binding.markLockContainer.visibility = View.VISIBLE
            }
            RecordingService.State.PAUSED -> {
                binding.btnRecord.text = "▶  Resume"
                binding.btnRecord.backgroundTintList =
                    ColorStateList.valueOf(requireContext().themeColor(R.attr.colorAccent))
                binding.stopSaveContainer.visibility = View.VISIBLE
                binding.markLockContainer.visibility = View.VISIBLE
            }
        }
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
        // Resolve the target volume before touching the service.
        val volume = viewModel.resolveRecordingVolume()

        // Always explicitly start the service, not just when the binder isn't
        // ready. A bound-only service can be destroyed when its last client
        // unbinds (e.g. during activity recreation on theme change). Calling
        // startForegroundService() here promotes it to a started service, so
        // Android won't destroy it just because RecordFragment temporarily
        // unbound during a configuration change.
        ContextCompat.startForegroundService(
            requireContext(),
            Intent(requireContext(), RecordingService::class.java)
        )

        val svc = recordingService ?: run {
            bindRecordingService()
            pendingQuickRecord = true
            return
        }
        // Wire the volume — must happen before startRecording() opens the file.
        svc.setStorageDir(volume.rootDir, volume.uuid)

        // Free-space check: warn but don't block.
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
                    // Reset the service dir so a later recording to a different
                    // volume isn't accidentally routed here.
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
        svc.startRecording(topicId = selectedTopicId)
    }

    private fun pauseRecording()  { recordingService?.pauseRecording() }
    private fun resumeRecording() { recordingService?.resumeRecording() }

    private fun stopAndSave() {
        val svc = recordingService ?: return
        val result = svc.stopRecording()
        if (result.filePath != null && File(result.filePath).exists()) {
            val stamp = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                .format(Date(System.currentTimeMillis()))

            // Guard against the user having deleted the selected topic while
            // the recording was in progress. Fall back to Inbox (null) rather
            // than producing a foreign key violation on save.
            val resolvedTopicId = selectedTopicId?.takeIf { id ->
                viewModel.allTopics.value.any { it.id == id }
            }

            val savedDeferred = viewModel.saveRecordingWithMarks(
                filePath       = result.filePath,
                durationMs     = result.durationMs,
                fileSizeBytes  = File(result.filePath).length(),
                title          = "Recording – $stamp",
                topicId        = resolvedTopicId,
                markTimestamps = result.markTimestamps,
                storageVolumeUuid = result.storageVolumeUuid
            )
            Toast.makeText(requireContext(), "Saved!", Toast.LENGTH_SHORT).show()
            binding.topicPicker.collapse()

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
        // Delete the audio file — nothing is saved, marks are discarded.
        if (result.filePath != null) {
            File(result.filePath).delete()
        }
        selectedTopicId = null
        lastCancelTapMs = 0L
        Toast.makeText(requireContext(), "Recording cancelled", Toast.LENGTH_SHORT).show()
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}