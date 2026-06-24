package app.soundtree.ui.share

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import app.soundtree.R
import app.soundtree.databinding.DialogShareRecordingBinding
import app.soundtree.ui.MainViewModel
import app.soundtree.ui.share.ShareRecordingDialogFragment.Companion.MAX_FILENAME_LENGTH
import app.soundtree.ui.share.ShareRecordingDialogFragment.Companion.newInstance
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Bottom-sheet dialog for sharing a single recording.
 *
 * ## Responsibilities (UI pass — Phase 1)
 * - Render all selection state: content type, filename mode, date injection.
 * - Compute and display the live filename preview.
 * - Expose a wired-but-stubbed Share button (TODO: ShareManager in Phase 2).
 *
 * ## What this does NOT do yet
 * - Actually share anything (ShareManager not yet implemented).
 * - Fetch the recording from the DB (recordingId is stored for Phase 2).
 *
 * ## Filename modes
 * [FilenameMode.FROM_TITLE]  — sanitized title, 80-char max.
 * [FilenameMode.CUSTOM]      — user-editable field, pre-filled with sanitized title.
 * [FilenameMode.ORIGINAL]    — on-disk stem unchanged; no date injection available.
 *
 * ## Date injection
 * Available for FROM_TITLE and CUSTOM only.
 * Three positions: PREPEND / NONE / APPEND.
 * When PREPEND or APPEND: optional "include time" checkbox (default checked).
 *
 * ## JSON filename
 * Always mirrors the audio filename stem (same name, .json extension).
 * The preview shows only the audio filename; the JSON pairing is implied.
 *
 * ## Future extension point — clips
 * The [ContentType] sealed class is the natural place to add clip variants:
 *   object ClipOnly : ContentType()
 *   object ClipWithMetadata : ContentType()
 * The content RadioGroup will grow a clip section once clip support lands.
 *
 * Launched via [newInstance].
 * Show via: ShareRecordingDialogFragment.newInstance(id).show(fm, TAG)
 */
class ShareRecordingDialogFragment : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.Theme_SoundTree_BottomSheet

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        const val TAG = "share_recording"

        private const val ARG_RECORDING_ID = "recording_id"

        fun newInstance(recordingId: Long) = ShareRecordingDialogFragment().apply {
            arguments = Bundle().apply { putLong(ARG_RECORDING_ID, recordingId) }
        }

        /** Maximum characters in the filename stem before the extension. */
        private const val MAX_FILENAME_LENGTH = 80
    }

    // ── Enums ─────────────────────────────────────────────────────────────────

    /** What files will be included in the share intent. */
    private enum class ContentType { AUDIO_ONLY, AUDIO_AND_METADATA, METADATA_ONLY }

    /** How the output filename stem is derived. */
    private enum class FilenameMode { FROM_TITLE, CUSTOM, ORIGINAL }

    /** Where (if anywhere) the date/time stamp is injected. */
    private enum class DatePosition { PREPEND, NONE, APPEND }

    // ── Fields ────────────────────────────────────────────────────────────────

    private var _binding: DialogShareRecordingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    private val recordingId: Long get() = requireArguments().getLong(ARG_RECORDING_ID)

    // ── UI state ──────────────────────────────────────────────────────────────

    private var contentType   = ContentType.AUDIO_ONLY
    private var filenameMode  = FilenameMode.FROM_TITLE
    private var datePosition  = DatePosition.NONE
    private var includeTime   = true

    /**
     * The sanitized, length-capped title used as the default stem.
     * Populated in [onViewCreated] once we have the recording title.
     * Placeholder until Phase 2 wires the DB fetch.
     */
    private var sanitizedTitle: String = ""

    /** The raw on-disk filename stem (no extension). Populated in Phase 2. */
    private var originalStem: String = ""

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

        // TODO (Phase 2): load recording from viewModel by recordingId, then:
        //   sanitizedTitle = sanitizeFilename(recording.title)
        //   originalStem   = File(recording.filePath).nameWithoutExtension
        //   binding.etCustomFilename.setText(sanitizedTitle)
        //
        // For now, use placeholders so the UI is exercisable.
        sanitizedTitle = "Recording Title"
        originalStem   = "ST_20261010_111213"
        binding.etCustomFilename.setText(sanitizedTitle)

        wireContentRadios()
        wireFilenameRadios()
        wireDateToggle()
        wireIncludeTimeCheckbox()
        wireCustomFilenameField()
        wireShareButton()

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
            // The filename section is irrelevant for metadata-only since the
            // JSON name is always title-derived. Hide it to reduce noise.
            binding.layoutFilenameSection.isVisible = (contentType != ContentType.METADATA_ONLY)
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
        // Select "None" as the initial checked button.
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

    private fun wireShareButton() {
        binding.btnShare.setOnClickListener {
            // TODO (Phase 2): invoke ShareManager with current state.
            // shareManager.share(
            //     recordingId  = recordingId,
            //     contentType  = contentType,
            //     filename     = buildFilename(),   // stem only, no extension
            // )
            dismiss()
        }
    }

    // ── Mode visibility ───────────────────────────────────────────────────────

    /**
     * Adjusts visibility of the custom EditText, original hint text, and the
     * date-injection controls to match the current [filenameMode].
     */
    private fun applyFilenameMode() {
        val isTitle    = filenameMode == FilenameMode.FROM_TITLE
        val isCustom   = filenameMode == FilenameMode.CUSTOM
        val isOriginal = filenameMode == FilenameMode.ORIGINAL

        binding.tilCustomFilename.isVisible    = isCustom
        binding.tvOriginalFilenameHint.isVisible = isOriginal
        binding.layoutDateOptions.isVisible    = isTitle || isCustom

        if (isOriginal) {
            // Reset date state when switching to Original so there's no
            // stale state if the user switches back.
            binding.toggleDatePosition.check(R.id.btnDateNone)
            datePosition = DatePosition.NONE
            binding.cbIncludeTime.isVisible = false
        }

        if (isOriginal) {
            binding.tvOriginalFilenameHint.text = "$originalStem.m4a"
        }
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    private fun updatePreview() {
        val stem      = buildFilenameStem()
        val extension = if (contentType == ContentType.METADATA_ONLY) "json" else "m4a"
        binding.tvFilenamePreview.text = "$stem.$extension"
    }

    /**
     * Builds the filename stem (no extension) from current UI state.
     *
     * Called on every state change that affects the preview.
     */
    private fun buildFilenameStem(): String {
        val base = when (filenameMode) {
            FilenameMode.FROM_TITLE -> sanitizedTitle
            FilenameMode.CUSTOM     -> {
                val typed = binding.etCustomFilename.text?.toString()?.trim() ?: ""
                typed.ifEmpty { sanitizedTitle }
            }
            FilenameMode.ORIGINAL   -> return originalStem
        }

        if (datePosition == DatePosition.NONE) return base

        val dateStamp = buildDateStamp()
        return when (datePosition) {
            DatePosition.PREPEND -> "$dateStamp - $base"
            DatePosition.APPEND  -> "$base - $dateStamp"
            DatePosition.NONE    -> base   // unreachable; kept for exhaustiveness
        }
    }

    /**
     * Returns a date or datetime string for the current moment.
     *
     * Format:
     *   Date only:     2026-06-23
     *   Date + time:   2026-06-23_11-12-13
     *
     * Uses the recording's own timestamp in Phase 2 rather than "now".
     * TODO (Phase 2): pass recording.createdAt here instead of LocalDateTime.now().
     */
    private fun buildDateStamp(): String {
        val now = LocalDateTime.now()
        return if (includeTime) {
            now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        } else {
            now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        }
    }

    // ── Filename sanitization (Phase 2 will use recording.title) ─────────────

    /**
     * Strips characters that are illegal or problematic in filenames across
     * Android, Windows, and macOS, collapses runs of spaces and hyphens,
     * and caps the result at [MAX_FILENAME_LENGTH] characters.
     *
     * Kept here rather than in ShareManager so the preview can run it
     * client-side without any async work.
     *
     * ## Character policy
     * Allowed: letters, digits, spaces, hyphens, underscores, dots.
     * Everything else (slashes, colons, quotes, emoji, etc.) is removed.
     * Runs of spaces or hyphens that result from removal are collapsed to one.
     *
     * ## Empty result
     * If sanitization produces an empty string (e.g. a title that is entirely
     * emoji), returns the [originalStem] as a safe fallback.
     */
    private fun sanitizeFilename(raw: String): String {
        val cleaned = raw
            .replace(Regex("[^\\w\\s\\-.]"), "")    // strip illegal chars
            .replace(Regex("[\\s]+"), " ")           // collapse whitespace runs
            .replace(Regex("-{2,}"), "-")            // collapse hyphen runs
            .trim()
            .take(MAX_FILENAME_LENGTH)

        return cleaned.ifEmpty { originalStem }
    }

}