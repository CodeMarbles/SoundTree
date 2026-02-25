package com.treecast.app.ui.listen

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.treecast.app.R
import com.treecast.app.data.entities.MarkEntity
import com.treecast.app.databinding.FragmentListenBinding
import com.treecast.app.ui.MainViewModel
import com.treecast.app.ui.NowPlayingState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ListenFragment : Fragment() {

    private var _binding: FragmentListenBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private var isSeeking = false

    // Which mini-tab is selected: 0 = Categorize, 1 = Marks
    private var selectedTab = 0

    // Track the last recording ID we set a default tab for, so we only
    // auto-select the tab once per recording load (not on every position tick).
    private var defaultTabRecordingId: Long? = null

    // Chip views keyed by markId — rebuilt when mark list changes.
    private val markChips = mutableMapOf<Long, TextView>()

    // The mark whose position we have most recently passed.
    private var lastPassedMarkId: Long? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListenBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTransportControls()
        setupMiniTabs()
        setupCategoryPicker()
        setupMarksPanel()
        observeNowPlaying()
        observeMarks()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Transport ──────────────────────────────────────────────────────
    private fun setupTransportControls() {
        binding.btnPlayPause.setOnClickListener   { viewModel.togglePlayPause() }
        binding.btnSkipBack.setOnClickListener    { viewModel.skipBack() }
        binding.btnSkipForward.setOnClickListener { viewModel.skipForward() }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) { isSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                isSeeking = false
                viewModel.seekTo(sb.progress.toLong())
            }
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = viewModel.nowPlaying.value?.durationMs ?: 1L
                    binding.tvPosition.text  = formatMs(progress.toLong())
                    binding.tvRemaining.text = "-${formatMs(dur - progress)}"
                    binding.waveformView.setProgress(progress.toFloat() / dur.toFloat())
                }
            }
        })
    }

    // ── Mini tabs ──────────────────────────────────────────────────────
    private fun setupMiniTabs() {
        binding.tabCategorize.setOnClickListener { selectTab(0) }
        binding.tabMarks.setOnClickListener      { selectTab(1) }
        selectTab(0)
    }

    private fun selectTab(tab: Int) {
        selectedTab = tab
        val density = resources.displayMetrics.density

        binding.tabCategorize.setTextColor(
            if (tab == 0) requireContext().getColor(R.color.text_primary)
            else requireContext().getColor(R.color.text_dim)
        )
        binding.tabMarks.setTextColor(
            if (tab == 1) requireContext().getColor(R.color.text_primary)
            else requireContext().getColor(R.color.text_dim)
        )

        val indicator = binding.tabIndicator
        val parent = indicator.parent as View
        parent.post {
            val tabWidth = parent.width / 2
            val targetWidth = (tabWidth * 0.6f).toInt()
            val offset = if (tab == 0) (tabWidth - targetWidth) / 2
            else tabWidth + (tabWidth - targetWidth) / 2
            val params = indicator.layoutParams
            params.width = targetWidth
            indicator.layoutParams = params
            indicator.translationX = offset.toFloat()
        }

        binding.categoryPicker.visibility = if (tab == 0) View.VISIBLE else View.GONE
        binding.marksPanel.visibility     = if (tab == 1) View.VISIBLE else View.GONE
    }

    // ── Category picker ────────────────────────────────────────────────
    private fun setupCategoryPicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.allCategories.collect { cats ->
                binding.categoryPicker.setCategories(cats)
            }
        }
        binding.categoryPicker.onCategorySelected = { catId ->
            viewModel.nowPlaying.value?.recording?.id?.let { recId ->
                viewModel.moveRecording(recId, catId)
            }
        }
    }

    // ── Marks panel ────────────────────────────────────────────────────
    private fun setupMarksPanel() {
        binding.btnAddMark.setOnClickListener    { viewModel.addMark() }
        binding.btnDeleteMark.setOnClickListener { viewModel.deleteSelectedMark() }
    }

    private fun observeMarks() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.marks.collect { marks -> renderMarkTimestamps(marks) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedMarkId.collect { selectedId ->
                binding.waveformView.setSelectedMark(selectedId)
                val hasSelection = selectedId != null
                binding.btnDeleteMark.isEnabled = hasSelection
                binding.btnDeleteMark.alpha = if (hasSelection) 1f else 0.4f
                // Restyle chips without rebuilding them
                updateMarkChipStyles()
            }
        }
    }

    private fun renderMarkTimestamps(marks: List<MarkEntity>) {
        val container = binding.markTimestampList
        container.removeAllViews()
        markChips.clear()

        val density  = resources.displayMetrics.density
        val state    = viewModel.nowPlaying.value
        val duration = state?.durationMs ?: 1L

        // Feed mark fractions to waveform
        binding.waveformView.setMarks(
            marks.map { mark ->
                (mark.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f) to mark.id
            }
        )

        if (marks.isEmpty()) {
            val hint = TextView(requireContext()).apply {
                text = "No marks yet"
                textSize = 12f
                setTextColor(requireContext().getColor(R.color.text_dim))
                gravity = Gravity.CENTER_VERTICAL
                val pad = (8 * density).toInt()
                setPadding(pad, 0, pad, 0)
            }
            container.addView(hint)
            return
        }

        // Circle chips: equal padding so the view forms a circle around the text.
        // FlowLayout handles the gaps between chips automatically.
        val padPx = (9 * density).toInt()

        for (mark in marks) {
            val chip = TextView(requireContext()).apply {
                text = formatMs(mark.positionMs)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(padPx, padPx, padPx, padPx)
                val minSizePx = (38 * density).toInt()
                minWidth  = minSizePx
                minHeight = minSizePx
                setOnClickListener {
                    val alreadySelected = viewModel.selectedMarkId.value == mark.id
                    viewModel.selectMark(if (alreadySelected) null else mark.id)
                    if (!alreadySelected) viewModel.seekTo(mark.positionMs)
                }
            }
            markChips[mark.id] = chip
            container.addView(chip)
        }

        // Apply correct styles after all chips are created
        updateMarkChipStyles()
    }

    /**
     * Style each chip based on three states (mutually exclusive, priority order):
     *  1. Selected → teal circle border, teal text
     *  2. Last-passed → pink circle fill, white text
     *  3. Default → no background, dim text
     *
     * Call this whenever selected mark OR playback position changes.
     */
    private fun updateMarkChipStyles() {
        val marks    = viewModel.marks.value
        val posMs    = viewModel.nowPlaying.value?.positionMs ?: 0L
        val selected = viewModel.selectedMarkId.value

        // Compute last-passed: highest positionMs that is ≤ current position
        val passed = marks
            .filter { it.positionMs <= posMs }
            .maxByOrNull { it.positionMs }
        lastPassedMarkId = passed?.id

        val colorTeal  = requireContext().getColor(R.color.mark_teal)
        val colorPink  = requireContext().getColor(R.color.mark_pink)
        val colorWhite = requireContext().getColor(R.color.white)
        val colorDim   = requireContext().getColor(R.color.text_dim)

        for ((markId, chip) in markChips) {
            val isSelected   = markId == selected
            val isLastPassed = markId == lastPassedMarkId

            when {
                isSelected && isLastPassed -> {
                    // Both: pink solid fill + teal border on top, teal text
                    chip.background = makeBorderedSolidCircle(
                        fillColor   = colorPink,
                        borderColor = colorTeal,
                        strokeDp    = 2f
                    )
                    chip.setTextColor(colorWhite)
                }
                isSelected -> {
                    // Selected only: teal border, transparent fill
                    chip.background = makeBorderCircle(colorTeal, strokeDp = 2f)
                    chip.setTextColor(colorWhite)
                }
                isLastPassed -> {
                    // Last-passed: pink solid fill, white text.
                    // Always shown regardless of whether another mark is selected.
                    chip.background = makeSolidCircle(colorPink)
                    chip.setTextColor(colorWhite)
                }
                else -> {
                    chip.background = null
                    chip.setTextColor(colorDim)
                }
            }
        }
    }

    // ── Drawable helpers ───────────────────────────────────────────────

    private fun makeBorderCircle(color: Int, strokeDp: Float): GradientDrawable {
        val strokePx = (strokeDp * resources.displayMetrics.density).toInt()
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(strokePx, color)
            setColor(android.graphics.Color.TRANSPARENT)
        }
    }

    private fun makeSolidCircle(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    /** Pink fill + teal border — for a mark that is both selected and last-passed. */
    private fun makeBorderedSolidCircle(fillColor: Int, borderColor: Int, strokeDp: Float): GradientDrawable {
        val strokePx = (strokeDp * resources.displayMetrics.density).toInt()
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            setStroke(strokePx, borderColor)
        }
    }

    // ── nowPlaying observation ─────────────────────────────────────────
    private fun observeNowPlaying() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.nowPlaying.collect { state ->
                if (state == null) showEmpty() else showPlayer(state)
            }
        }
    }

    private fun showEmpty() {
        binding.emptyState.visibility    = View.VISIBLE
        binding.playerContent.visibility = View.GONE
    }

    private fun showPlayer(state: NowPlayingState) {
        binding.emptyState.visibility    = View.GONE
        binding.playerContent.visibility = View.VISIBLE

        val rec = state.recording

        // ── Default tab: set once per new recording load ───────────────
        if (rec.id != defaultTabRecordingId) {
            defaultTabRecordingId = rec.id
            // If the recording is already categorised → open Marks panel by default.
            // If it still needs a category → open Categorize.
            selectTab(if (rec.categoryId != null) 1 else 0)
        }

        binding.waveformView.setSeed(rec.id)
        val frac = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
        binding.waveformView.setProgress(frac)

        // ── Category label above title ────────────────────────────────
        val catName = rec.categoryId?.let { id ->
            viewModel.allCategories.value.find { it.id == id }?.name
        }
        if (catName != null) {
            binding.tvCategory.text = catName
            binding.tvCategory.setTextColor(requireContext().getColor(R.color.text_dim))
        } else {
            binding.tvCategory.text = "Uncategorized"
            binding.tvCategory.setTextColor(requireContext().getColor(R.color.uncategorized_label))
        }
        binding.tvCategory.visibility = View.VISIBLE

        binding.tvTitle.text = rec.title
        binding.tvRecordedAt.text = formatDateTime(rec.createdAt)
        binding.tvDuration.text   = formatMs(rec.durationMs)

        if (!isSeeking) {
            binding.seekBar.max      = state.durationMs.toInt()
            binding.seekBar.progress = state.positionMs.toInt()
        }
        binding.tvPosition.text  = formatMs(state.positionMs)
        binding.tvRemaining.text = "-${formatMs(state.durationMs - state.positionMs)}"

        binding.btnPlayPause.setImageResource(
            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )

        // Update category picker selection
        if (rec.categoryId != null) {
            val cn = viewModel.allCategories.value.find { it.id == rec.categoryId }?.name
                ?: "Uncategorised"
            binding.categoryPicker.setSelectedCategory(rec.categoryId, cn)
        } else {
            binding.categoryPicker.setSelectedCategory(null, "Uncategorised")
        }

        // Re-apply last-passed chip styling now that position has changed
        updateMarkChipStyles()
    }

    // ── Formatting helpers ─────────────────────────────────────────────
    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun formatDateTime(epochMs: Long): String =
        SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault()).format(Date(epochMs))
}