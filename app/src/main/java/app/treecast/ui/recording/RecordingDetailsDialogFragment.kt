package app.treecast.ui.recording

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import androidx.core.view.doOnLayout
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import app.treecast.R
import app.treecast.data.entities.RecordingEntity
import app.treecast.databinding.DialogRecordingDetailsBinding
import app.treecast.ui.MainViewModel
import app.treecast.ui.common.TopicPickerBottomSheet
import app.treecast.ui.deleteRecording
import app.treecast.ui.play
import app.treecast.ui.recording.RecordingDetailsDialogFragment.Companion.newInstance
import app.treecast.ui.renameRecording
import app.treecast.ui.stopAndClear
import app.treecast.ui.togglePlayPause
import app.treecast.ui.waveform.WaveformMark
import app.treecast.util.Icons
import app.treecast.storage.StorageVolumeHelper
import app.treecast.ui.getMarksForRecording
import app.treecast.ui.moveRecording
import app.treecast.util.WaveformCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen dialog showing details for a single recording.
 *
 * Sections (top → bottom):
 *   1. Top bar     — close (X) left, overflow (⋮) right
 *   2. Topic zone  — icon + name, tappable to change topic
 *   3. Title row   — play/pause toggle + recording name (tappable to rename)
 *   4. Metadata    — duration · date
 *   5. Waveform    — condensed, read-only MultiLineWaveformView overview
 *   6. [Future]    — clips list
 *
 * The play/pause button always reflects the state of *this* recording:
 *   - If this recording is now-playing and playing  → shows ic_pause
 *   - If this recording is now-playing but paused   → shows ic_play
 *   - If this recording is not the active one       → shows ic_play
 * Tapping it either starts playback (if not active) or toggles pause
 * (if already active). The dialog does not dismiss on play.
 *
 * Waveform loading:
 *   The waveform is loaded directly from [WaveformCache] on the IO dispatcher
 *   so this dialog is not coupled to the now-playing flow. It also observes
 *   [MainViewModel.waveformState] as a reactive update path, which handles
 *   the case where the waveform for this recording is freshly generated while
 *   the dialog is open (e.g. if WaveformWorker finishes during this session).
 *
 * Theme: [R.style.Theme_TreeCast_FullscreenDialog]
 *
 * Launched via [newInstance]; pass the recording's database ID.
 * Show via: RecordingDetailsDialogFragment.newInstance(id).show(fm, TAG)
 */
class RecordingDetailsDialogFragment : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.Theme_TreeCast_BottomSheet

    // ── Companion ─────────────────────────────────────────────────────

    companion object {
        const val TAG = "recording_details"

        private const val ARG_RECORDING_ID = "recording_id"

        /**
         * Request key used with [TopicPickerBottomSheet] for topic-change
         * actions launched from both the topic header tap and the overflow
         * menu. A single key is safe here because at most one picker can be
         * open at a time from this dialog.
         */
        private const val MOVE_REQUEST_KEY = "RecordingDetails_move"

        fun newInstance(recordingId: Long) = RecordingDetailsDialogFragment().apply {
            arguments = Bundle().apply { putLong(ARG_RECORDING_ID, recordingId) }
        }
    }

    // ── Fields ────────────────────────────────────────────────────────

    private var _binding: DialogRecordingDetailsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    /** Stable ID for the recording being displayed. Extracted once from args. */
    private val recordingId: Long get() = requireArguments().getLong(ARG_RECORDING_ID)

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogRecordingDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Guard: dismiss immediately if the recording no longer exists.
        // This should only happen if the recording was deleted externally
        // between the tap that opened this dialog and onViewCreated firing.
        if (viewModel.allRecordings.value.none { it.id == recordingId }) {
            dismissAllowingStateLoss()
            return
        }

        setupTopBar()
        setupTopicHeader()
        setupTitleRow()
        setupWaveform()
        setupMoveResultListener()
        observeState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Setup ─────────────────────────────────────────────────────────

    private fun setupTopBar() {
        binding.btnClose.setOnClickListener { dismissAllowingStateLoss() }
        binding.btnOverflow.setOnClickListener { showOverflowMenu() }
    }

    private fun setupTopicHeader() {
        // Seed with current snapshot; the observer will keep it live.
        renderTopicHeader()

        binding.topicHeader.setOnClickListener {
            val current = viewModel.allRecordings.value.find { it.id == recordingId }
            TopicPickerBottomSheet.newInstance(
                selectedTopicId = current?.topicId,
                requestKey      = MOVE_REQUEST_KEY
            ).show(childFragmentManager, "details_topic_picker")
        }
    }

    private fun setupTitleRow() {
        // Seed with current snapshot; the allRecordings observer keeps it live.
        val recording = viewModel.allRecordings.value.find { it.id == recordingId }
        if (recording != null) {
            binding.tvRecordingName.text = recording.title
            bindMetadata(recording)
        }

        // Name tap → rename dialog.
        binding.tvRecordingName.setOnClickListener {
            val rec = viewModel.allRecordings.value.find { it.id == recordingId } ?: return@setOnClickListener
            showRenameDialog(rec.title)
        }

        // Play/pause tap: if this is the active recording, toggle; otherwise start.
        binding.btnPlayPause.setOnClickListener {
            if (viewModel.nowPlaying.value?.recording?.id == recordingId) {
                viewModel.togglePlayPause()
            } else {
                val rec = viewModel.allRecordings.value.find { it.id == recordingId } ?: return@setOnClickListener
                viewModel.play(rec)
            }
        }
    }

    private fun setupWaveform() {
        val recording = viewModel.allRecordings.value.find { it.id == recordingId } ?: return

        // secondsPerLine targets ~4 lines visible in the fixed 180dp waveform
        // height, giving a "postage stamp" overview of the whole recording shape.
        // Minimum 30 s/line so very short recordings still render cleanly.
        val durationSecs = (recording.durationMs / 1000L).coerceAtLeast(1L)
        val secondsPerLine = (durationSecs / 1L).toInt().coerceAtLeast(30) + 1

        with(binding.multiLineWaveform) {
            scaleToFill           = true
            this.secondsPerLine   = secondsPerLine
            showPlayedSplit       = false   // no played/unplayed split in overview
            showLineRail          = true
            onTimeSelected        = null    // read-only; taps do nothing
            onMarkTapped          = null
            // Apply user's waveform style immediately from the current snapshot.
            waveformStyle         = viewModel.waveformStyle.value
            waveformDisplayConfig = viewModel.waveformDisplayConfig.value
            setDurationMs(recording.durationMs)
        }

        // Load amplitude data directly from the on-disk cache on the IO
        // dispatcher. This path works whether or not the recording is currently
        // playing, keeping the dialog decoupled from the now-playing state.
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val amplitudes = WaveformCache(requireContext()).load(recordingId)
            if (amplitudes != null) {
                withContext(Dispatchers.Main) {
                    val waveform = _binding?.multiLineWaveform ?: return@withContext
                    if (waveform.isLaidOut) {
                        waveform.setAmplitudes(amplitudes)
                    } else {
                        waveform.doOnLayout { waveform.setAmplitudes(amplitudes) }
                    }
                }
            }
        }
    }

    private fun setupMoveResultListener() {
        childFragmentManager.setFragmentResultListener(
            MOVE_REQUEST_KEY, viewLifecycleOwner
        ) { _, bundle ->
            val newTopicId = TopicPickerBottomSheet.topicIdFromBundle(bundle)
            viewModel.moveRecording(recordingId, newTopicId)
            // Optimistic update so the header changes before the flow re-emits.
            val topic = viewModel.allTopics.value.firstOrNull { it.id == newTopicId }
            binding.tvTopicIcon.text = topic?.icon ?: Icons.UNSORTED
            binding.tvTopicName.text = topic?.name ?: getString(R.string.topic_label_unsorted)
        }
    }

    // ── Observation ───────────────────────────────────────────────────

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // ── Play/pause button icon ─────────────────────────────────
                // Reflects the state of *this* recording, not the global player.
                // isPlaying = true only when this recording is both active AND
                // in the playing (not paused) state.
                launch {
                    viewModel.nowPlaying.collect { state ->
                        val isThisRecording = state?.recording?.id == recordingId
                        val isPlaying       = isThisRecording && (state?.isPlaying == true)
                        binding.btnPlayPause.setImageResource(
                            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        )
                    }
                }

                // ── Recording metadata (title, duration, date) ────────────
                // Keeps the title row current if the recording is renamed or
                // otherwise mutated while the dialog is open.
                launch {
                    viewModel.allRecordings.collect { recordings ->
                        val recording = recordings.find { it.id == recordingId }
                        if (recording == null) {
                            // Recording was deleted externally — close the dialog.
                            dismissAllowingStateLoss()
                            return@collect
                        }
                        binding.tvRecordingName.text = recording.title
                        bindMetadata(recording)
                    }
                }

                // ── Topic header ───────────────────────────────────────────
                // Re-renders when topics are renamed / reordered, or when this
                // recording is moved to a different topic.
                launch {
                    viewModel.allTopics.collect { _ -> renderTopicHeader() }
                }

                // ── Waveform data (reactive update path) ───────────────────
                // The primary load is done in setupWaveform() via WaveformCache.
                // This observer handles the case where WaveformWorker finishes
                // generating the waveform for this recording while the dialog
                // is already open — the ViewModel will emit a new waveformState
                // pair and we apply it here.
                launch {
                    viewModel.waveformState.collect { pair ->
                        if (pair != null && pair.first == recordingId) {
                            binding.multiLineWaveform.setAmplitudes(pair.second)
                        }
                    }
                }

                // ── Waveform appearance ────────────────────────────────────
                // Follows the user's live style preference in case they change
                // it in Settings while this dialog is open.
                launch {
                    viewModel.waveformStyle.collect { style ->
                        binding.multiLineWaveform.waveformStyle = style
                    }
                }
                launch {
                    viewModel.waveformDisplayConfig.collect { cfg ->
                        binding.multiLineWaveform.waveformDisplayConfig = cfg
                    }
                }

                // ── Waveform marks ─────────────────────────────────────────────
                // Loaded for this specific recording ID regardless of whether it
                // is currently playing. Read-only — no selection state needed.
                launch {
                    viewModel.getMarksForRecording(recordingId).collect { marks ->
                        binding.multiLineWaveform.setMarks(
                            marks.map { WaveformMark(id = it.id, positionMs = it.positionMs) }
                        )
                    }
                }
            }
        }
    }

    // ── Overflow menu ─────────────────────────────────────────────────

    private fun showOverflowMenu() {
        val recording = viewModel.allRecordings.value.find { it.id == recordingId } ?: return
        PopupMenu(requireContext(), binding.btnOverflow).apply {
            menuInflater.inflate(R.menu.menu_recording_options, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_rename -> {
                        showRenameDialog(recording.title)
                        true
                    }
                    R.id.action_move -> {
                        TopicPickerBottomSheet.newInstance(
                            selectedTopicId = recording.topicId,
                            requestKey      = MOVE_REQUEST_KEY
                        ).show(childFragmentManager, "details_overflow_move")
                        true
                    }
                    R.id.action_delete -> {
                        showDeleteDialog(recording.title)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────

    private fun showRenameDialog(currentTitle: String) {
        val input = EditText(requireContext()).apply {
            setText(currentTitle)
            selectAll()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.recording_dialog_rename_title)
            .setView(input)
            .setPositiveButton(R.string.common_btn_ok) { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotEmpty()) viewModel.renameRecording(recordingId, newTitle)
            }
            .setNegativeButton(R.string.common_btn_cancel, null)
            .show()
    }

    private fun showDeleteDialog(title: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.recording_dialog_delete_title)
            .setMessage(getString(R.string.recording_dialog_delete_message, title))
            .setPositiveButton(R.string.common_btn_delete) { _, _ ->
                // Stop playback first if this recording is currently active.
                if (viewModel.nowPlaying.value?.recording?.id == recordingId) {
                    viewModel.stopAndClear()
                }
                val recording = viewModel.allRecordings.value.find { it.id == recordingId }
                if (recording != null) viewModel.deleteRecording(recording)
                dismissAllowingStateLoss()
            }
            .setNegativeButton(R.string.common_btn_cancel, null)
            .show()
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /**
     * Reads the current topic for this recording from the ViewModel snapshot
     * and updates the topic header views. Safe to call from any coroutine
     * collector because it only touches [binding] views (main thread only).
     */
    private fun renderTopicHeader() {
        val recording = viewModel.allRecordings.value.find { it.id == recordingId } ?: return
        val topic     = viewModel.allTopics.value.firstOrNull { it.id == recording.topicId }
        binding.tvTopicIcon.text = topic?.icon ?: Icons.UNSORTED
        binding.tvTopicName.text = topic?.name ?: getString(R.string.topic_label_unsorted)
    }

    private fun bindMetadata(recording: RecordingEntity) {
        binding.tvMetaDuration.text   = formatDuration(recording.durationMs)
        binding.tvMetaFileSize.text   = formatFileSize(recording.fileSizeBytes)
        binding.tvMetaRecordedAt.text = "${formatDate(recording.createdAt)}  ·  ${formatTime(recording.createdAt)}"
        binding.tvMetaFilePath.text   = formatFilePath(recording)
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    private fun formatDate(epochMs: Long): String =
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochMs))

    private fun formatTime(epochMs: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(epochMs))

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000     -> "%.1f KB".format(bytes / 1_000.0)
        else               -> "$bytes B"
    }

    private fun formatFilePath(recording: RecordingEntity): String {
        val file   = File(recording.filePath)
        val volume = StorageVolumeHelper.getVolumeByUuid(requireContext(), recording.storageVolumeUuid)
            ?: return file.name
        val rootCanon = runCatching { volume.rootDir.canonicalPath }.getOrElse { volume.rootDir.absolutePath }
        val fileCanon = runCatching { file.canonicalPath           }.getOrElse { file.absolutePath }
        // rootDir is already named "recordings", so strip the rootDir prefix to get
        // just the filename, then re-add the directory name for display clarity.
        val relative = if (fileCanon.startsWith(rootCanon)) {
            fileCanon.removePrefix(rootCanon).trimStart('/')
        } else {
            file.name
        }
        return "${volume.label} / recordings/$relative"
    }
}