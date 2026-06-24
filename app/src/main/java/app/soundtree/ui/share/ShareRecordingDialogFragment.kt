package app.soundtree.ui.share

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import app.soundtree.R
import app.soundtree.data.entities.RecordingEntity
import app.soundtree.databinding.DialogShareRecordingBinding
import app.soundtree.share.ShareContent
import app.soundtree.share.ShareManager
import app.soundtree.ui.MainViewModel
import app.soundtree.ui.getMarksForRecording
import app.soundtree.ui.share.ShareRecordingDialogFragment.Companion.MAX_FILENAME_LENGTH
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Bottom-sheet dialog for sharing a single recording.
 *
 * ## Lifecycle
 * On open, the dialog loads the recording synchronously from the ViewModel's
 * in-memory [MainViewModel.allRecordings] StateFlow (no DB query needed — the
 * list is always loaded). Marks are loaded once from [MainViewModel.getMarksForRecording].
 *
 * ## Share flow
 * 1. User selects content type and filename options.
 * 2. User taps Share.
 * 3. Dialog launches a coroutine that calls [ShareManager.prepareIntent] on IO.
 * 4. On success the system chooser is fired; dialog dismisses.
 * 5. On failure (file missing) a toast is shown and the dialog stays open.
 *
 * ## Filename modes
 * [FilenameMode.FROM_TITLE]  — sanitized title, 80-char max.
 * [FilenameMode.CUSTOM]      — user-editable, pre-filled with sanitized title.
 * [FilenameMode.ORIGINAL]    — on-disk stem unchanged; no date injection.
 *
 * ## Future extension — clips
 * The [ShareContent] sealed class is the extension point. The content
 * RadioGroup grows a clip section; [ShareManager] grows matching branches.
 * No structural changes needed in this dialog.
 */
class ShareRecordingDialogFragment : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.Theme_SoundTree_BottomSheet

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        const val TAG = "share_recording"

        private const val ARG_RECORDING_ID = "recording_id"

        /** Maximum characters in the filename stem before the extension. */
        private const val MAX_FILENAME_LENGTH = 80

        fun newInstance(recordingId: Long) = ShareRecordingDialogFragment().apply {
            arguments = Bundle().apply { putLong(ARG_RECORDING_ID, recordingId) }
        }
    }

    // ── Enums ─────────────────────────────────────────────────────────────────

    private enum class ContentType { AUDIO_ONLY, AUDIO_AND_METADATA, METADATA_ONLY }
    private enum class FilenameMode { FROM_TITLE, CUSTOM, ORIGINAL }
    private enum class DatePosition { PREPEND, NONE, APPEND }

    // ── Fields ────────────────────────────────────────────────────────────────

    private var _binding: DialogShareRecordingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    private val recordingId: Long get() = requireArguments().getLong(ARG_RECORDING_ID)

    // ── UI state ──────────────────────────────────────────────────────────────

    private var contentType  = ContentType.AUDIO_ONLY
    private var filenameMode = FilenameMode.FROM_TITLE
    private var datePosition = DatePosition.NONE
    private var includeTime  = true

    // ── Recording data (populated in onViewCreated) ───────────────────────────

    /** Sanitized, length-capped title — used as the FROM_TITLE stem and CUSTOM pre-fill. */
    private var sanitizedTitle: String = ""

    /** On-disk stem (no extension) — used as the ORIGINAL stem. */
    private var originalStem: String = ""

    /**
     * The recording's own creation timestamp, used for date injection.
     * Stamped from the recording rather than "now" so the filename reflects
     * when the recording was made, not when it was shared.
     */
    private var recordingCreatedAt: LocalDateTime = LocalDateTime.now()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogShareRecordingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state         = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Load recording from the in-memory StateFlow — always available, no suspend needed.
        val recording = viewModel.allRecordings.value.find { it.id == recordingId }
        if (recording == null) {
            // Guard: recording deleted between tap and dialog open.
            dismissAllowingStateLoss()
            return
        }

        sanitizedTitle     = sanitizeFilename(recording.title)
        originalStem       = File(recording.filePath).nameWithoutExtension
        recordingCreatedAt = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(recording.createdAt),
            ZoneId.systemDefault(),
        )

        binding.etCustomFilename.setText(sanitizedTitle)

        wireContentRadios()
        wireFilenameRadios()
        wireDateToggle()
        wireIncludeTimeCheckbox()
        wireCustomFilenameField()
        wireShareButton(recording)

        updatePreview()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Wiring ────────────────────────────────────────────────────────────────

    private fun wireContentRadios() {
        binding.rgShareContent.setOnCheckedChangeListener { _, checkedId ->
            contentType = when (checkedId) {
                R.id.rbAudioOnly        -> ContentType.AUDIO_ONLY
                R.id.rbAudioAndMetadata -> ContentType.AUDIO_AND_METADATA
                R.id.rbMetadataOnly     -> ContentType.METADATA_ONLY
                else                    -> ContentType.AUDIO_ONLY
            }
            // The filename section applies to every content type, including
            // metadata-only — it names the JSON export just as it names the audio.
            // Re-run applyFilenameMode so the ORIGINAL hint's extension tracks the
            // newly selected content type (.m4a ↔ .json).
            applyFilenameMode()
            updatePreview()
        }
    }

    private fun wireFilenameRadios() {
        binding.rgFilename.setOnCheckedChangeListener { _, checkedId ->
            filenameMode = when (checkedId) {
                R.id.rbFilenameTitle    -> FilenameMode.FROM_TITLE
                R.id.rbFilenameCustom   -> FilenameMode.CUSTOM
                R.id.rbFilenameOriginal -> FilenameMode.ORIGINAL
                else                    -> FilenameMode.FROM_TITLE
            }
            applyFilenameMode()
            updatePreview()
        }
    }

    private fun wireDateToggle() {
        binding.toggleDatePosition.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            datePosition = when (checkedId) {
                R.id.btnDatePrepend -> DatePosition.PREPEND
                R.id.btnDateNone    -> DatePosition.NONE
                R.id.btnDateAppend  -> DatePosition.APPEND
                else                -> DatePosition.NONE
            }
            binding.cbIncludeTime.isVisible = (datePosition != DatePosition.NONE)
            updatePreview()
        }
        binding.toggleDatePosition.check(R.id.btnDateNone)
    }

    private fun wireIncludeTimeCheckbox() {
        binding.cbIncludeTime.setOnCheckedChangeListener { _, checked ->
            includeTime = checked
            updatePreview()
        }
    }

    private fun wireCustomFilenameField() {
        binding.etCustomFilename.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { updatePreview() }
        })
    }

    private fun wireShareButton(recording: RecordingEntity) {
        binding.btnShare.setOnClickListener {
            val stem         = buildFilenameStem()
            val shareContent = when (contentType) {
                ContentType.AUDIO_ONLY         -> ShareContent.AudioOnly
                ContentType.AUDIO_AND_METADATA -> ShareContent.AudioWithMetadata
                ContentType.METADATA_ONLY      -> ShareContent.MetadataOnly
            }

            // Disable while the intent is being prepared to prevent double-taps.
            // Re-enabled only in the error branch; success dismisses the dialog.
            binding.btnShare.isEnabled = false

            viewLifecycleOwner.lifecycleScope.launch {
                val marks     = viewModel.getMarksForRecording(recordingId).first()
                val intent = ShareManager.prepareIntent(
                    context    = requireContext(),
                    recording  = recording,
                    marks      = marks,
                    allTopics  = viewModel.allTopics.value,
                    content    = shareContent,
                    outputStem = stem,
                )

                if (intent == null) {
                    Toast.makeText(
                        requireContext(),
                        R.string.share_error_file_missing,
                        Toast.LENGTH_LONG,
                    ).show()
                    binding.btnShare.isEnabled = true
                } else {
                    startActivity(intent)
                    dismissAllowingStateLoss()
                }
            }
        }
    }

    // ── Mode visibility ───────────────────────────────────────────────────────

    private fun applyFilenameMode() {
        val isTitle    = filenameMode == FilenameMode.FROM_TITLE
        val isCustom   = filenameMode == FilenameMode.CUSTOM
        val isOriginal = filenameMode == FilenameMode.ORIGINAL

        binding.tilCustomFilename.isVisible      = isCustom
        binding.tvOriginalFilenameHint.isVisible = isOriginal
        binding.layoutDateOptions.isVisible      = isTitle || isCustom

        if (isOriginal) {
            binding.toggleDatePosition.check(R.id.btnDateNone)
            datePosition = DatePosition.NONE
            binding.cbIncludeTime.isVisible = false
            binding.tvOriginalFilenameHint.text =
                outputFilenames(originalStem).joinToString("\n")
        }
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    private fun updatePreview() {
        binding.tvFilenamePreview.text = outputFilenames(buildFilenameStem()).joinToString("\n")
    }

    // ── Filename building ─────────────────────────────────────────────────────

    /**
     * Builds the filename stem from the current UI state.
     * Single source of truth used by both [updatePreview] and [wireShareButton] —
     * guarantees the preview and the actual shared filename are always identical.
     */
    private fun buildFilenameStem(): String {
        val base = when (filenameMode) {
            FilenameMode.FROM_TITLE -> sanitizedTitle
            FilenameMode.CUSTOM     -> {
                val typed = binding.etCustomFilename.text?.toString()?.trim() ?: ""
                typed.ifEmpty { sanitizedTitle }
            }
            FilenameMode.ORIGINAL -> return originalStem
        }

        if (datePosition == DatePosition.NONE) return base

        val stamp = buildDateStamp()
        return when (datePosition) {
            DatePosition.PREPEND -> "$stamp - $base"
            DatePosition.APPEND  -> "$base - $stamp"
            DatePosition.NONE    -> base
        }
    }

    /**
     * The output filename(s) produced for [stem] under the current [contentType].
     *
     * Single source of truth for what actually leaves the dialog — both the preview
     * and the ORIGINAL-mode hint render this, and it mirrors exactly what
     * [ShareManager.buildIntent] attaches:
     *   AUDIO_ONLY          → [stem].m4a
     *   METADATA_ONLY       → [stem].json
     *   AUDIO_AND_METADATA  → [stem].m4a + [stem].json   (shared stem)
     *
     * When clip support lands, add its branch here and every surface updates for free.
     */
    private fun outputFilenames(stem: String): List<String> = when (contentType) {
        ContentType.AUDIO_ONLY         -> listOf("$stem.m4a")
        ContentType.METADATA_ONLY      -> listOf("$stem.json")
        ContentType.AUDIO_AND_METADATA -> listOf("$stem.m4a", "$stem.json")
    }

    /**
     * Formats [recordingCreatedAt] as a date or datetime string for filename injection.
     *
     * Uses the recording's creation time (not the current time) so
     * "Interview with Dad - 2026-06-23.m4a" reflects when the recording
     * was made, not when it was shared.
     *
     * Date only:    2026-06-23
     * Date + time:  2026-06-23_11-12-13
     */
    private fun buildDateStamp(): String = if (includeTime) {
        recordingCreatedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
    } else {
        recordingCreatedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    }

    // ── Filename sanitization ─────────────────────────────────────────────────

    /**
     * Strips characters illegal or problematic in filenames across Android,
     * Windows, and macOS; collapses runs of whitespace and hyphens; caps at
     * [MAX_FILENAME_LENGTH].
     *
     * Allowed: letters, digits, spaces, hyphens, underscores, dots.
     *
     * Falls back to [originalStem] when sanitization produces an empty string
     * (e.g. an all-emoji title). [originalStem] may not be set yet on the
     * very first call, so the innermost fallback is the raw input.
     */
    private fun sanitizeFilename(raw: String): String {
        val cleaned = raw
            .replace(Regex("[^\\w\\s\\-.]"), "")  // strip illegal chars
            .replace(Regex("\\s+"), " ")           // collapse whitespace runs
            .replace(Regex("-{2,}"), "-")          // collapse hyphen runs
            .trim()
            .take(MAX_FILENAME_LENGTH)
        return cleaned.ifEmpty { originalStem.ifEmpty { raw } }
    }
}