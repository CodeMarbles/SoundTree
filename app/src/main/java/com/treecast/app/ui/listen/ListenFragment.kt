package com.treecast.app.ui.listen

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.treecast.app.R
import com.treecast.app.data.entities.MarkEntity
import com.treecast.app.databinding.FragmentListenBinding
import com.treecast.app.ui.MainViewModel
import com.treecast.app.ui.NowPlayingState
import com.treecast.app.ui.common.TopicPickerBottomSheet
import com.treecast.app.ui.waveform.WaveformMark
import com.treecast.app.ui.waveform.WaveformTapType
import com.treecast.app.util.Icons
import com.treecast.app.util.UiConstants
import com.treecast.app.util.themeColor

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch


class ListenFragment : Fragment() {

    private var _binding: FragmentListenBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private var isSeeking = false

    private val markChips = mutableMapOf<Long, TextView>()
    private var lastPassedMarkId: Long? = null

    // ── Splitter state ────────────────────────────────────────────────────────

    private enum class SplitterState { SNAP_DOWN, SNAP_UP, FREE }
    private var splitterState = SplitterState.SNAP_DOWN

    // ── Drag state ────────────────────────────────────────────────────────────

    private var dragActive    = false
    private var dragStartRawY = 0f
    private val touchSlop by lazy {
        android.view.ViewConfiguration.get(requireContext()).scaledTouchSlop
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListenBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTopicHeader()
        setupTransportControls()
        setupMultiLineWaveform()

        setupSplitter()
        setupSnapUpTracking()

        setupMarksPanel()
        observeNowPlaying()
        observeMarks()
        observeWaveform()

        // Initialise chip layout once the scroller has been measured.
        binding.markChipScroller.doOnLayout { applyChipLayoutMode(SplitterState.SNAP_DOWN) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Multi-line waveform setup ──────────────────────────────────────────────

    private fun setupMultiLineWaveform() {
        binding.multiLineWaveform.scaleToFill = true
        binding.multiLineWaveform.secondsPerLine   = 300 // 5 minutes
        binding.multiLineWaveform.showPlayedSplit  = true
        binding.multiLineWaveform.showLineRail     = true

        binding.multiLineWaveform.onTimeSelected = { positionMs, type ->
            when (type) {
                WaveformTapType.TAP        -> viewModel.seekTo(positionMs)
                WaveformTapType.LONG_PRESS -> { /* reserved */ }
            }
        }
    }

    // ── Splitter ───────────────────────────────────────────────────────────────

    private fun setupSplitter() {
        binding.btnSnapDown.setOnClickListener { snapTo(SplitterState.SNAP_DOWN) }
        binding.btnSnapUp.setOnClickListener   { snapTo(SplitterState.SNAP_UP) }

        binding.btnPlaybackSpeed.setOnClickListener {
            Toast.makeText(requireContext(), "Playback Speed", Toast.LENGTH_SHORT).show()
        }
        binding.btnSleepTimer.setOnClickListener {
            Toast.makeText(requireContext(), "Sleep Timer", Toast.LENGTH_SHORT).show()
        }
        binding.splitterBar.setOnTouchListener { _, event -> handleSplitterTouch(event) }
    }

    private fun setupSnapUpTracking() {
        // Re-anchor the SNAP_UP guideline if root height changes (e.g. mini-recorder
        // widget appears or disappears while we're in marks-dominant mode).
        binding.root.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom == oldBottom) return@addOnLayoutChangeListener
            when (splitterState) {
                SplitterState.SNAP_UP -> {
                    binding.splitterGuideline.setGuidelinePercent(calcSnapUpPercent())
                }
                SplitterState.FREE -> {
                    // Preserve the pixel-space position of the bar when chrome
                    // appears/disappears (e.g. mini-recorder) during free drag.
                    val currentPercent = (binding.splitterGuideline.layoutParams
                            as ConstraintLayout.LayoutParams).guidePercent
                    val pixelY  = currentPercent * oldBottom
                    binding.splitterGuideline.setGuidelinePercent(
                        (pixelY / bottom).coerceIn(0.05f, 0.85f)
                    )
                }
                else -> { /* SNAP_DOWN — guideline is at a calculated position, no adjustment needed */ }
            }
        }
    }

    private fun snapTo(state: SplitterState) {
        if (state == SplitterState.SNAP_DOWN) {
            applyChipLayoutMode(SplitterState.SNAP_DOWN)
            val density       = resources.displayMetrics.density
            val scaledDensity = resources.displayMetrics.scaledDensity
            val chipRowPx = (12f * scaledDensity) + (28f * density)
            val parentH = binding.root.height.toFloat()
            val target = if (parentH > 0f) {
                val belowPx = binding.splitterBar.height + chipRowPx +
                        binding.seekBarAreaBottom.height +   // 0 since it's GONE — explicit for clarity
                        binding.pinnedBottomArea.height
                ((parentH - belowPx) / parentH).coerceIn(0.30f, 0.85f)
            } else {
                0.62f
            }
            animateGuideline(target, SplitterState.SNAP_DOWN)
        } else {
            applyChipLayoutMode(SplitterState.SNAP_UP)
            // Zone A has just shrunk (seekBar + labels gone). Wait for a layout
            // pass so pinnedTopArea.height reflects its new compressed size.
            binding.pinnedTopArea.doOnNextLayout {
                animateGuideline(calcSnapUpPercent(), SplitterState.SNAP_UP)
            }
        }
    }

    private fun handleSplitterTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                dragActive = false
                dragStartRawY = event.rawY
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragActive) {
                    if (Math.abs(event.rawY - dragStartRawY) < touchSlop) return true
                    // Crossed slop — commit to drag
                    dragActive = true
                    // Switch layout to marks-dominant immediately, no animation
                    if (splitterState == SplitterState.SNAP_DOWN) {
                        applyChipLayoutMode(SplitterState.SNAP_UP)
                    }
                    binding.multiLineWaveform.fadesEnabled = false
                    splitterState = SplitterState.FREE
                    binding.multiLineWaveform.autoScrollEnabled = false
                }

                // Track finger → guideline percent
                val loc = IntArray(2)
                binding.root.getLocationOnScreen(loc)
                val fingerY = event.rawY - loc[1]
                val percent = (fingerY / binding.root.height).coerceIn(0.15f, 0.85f)
                binding.splitterGuideline.setGuidelinePercent(percent)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragActive = false
                return true
            }
        }
        return false
    }

    /**
     * Calculates the guideline percent for SNAP_UP: Zone A (now seekbar-free) +
     * splitter bar + one waveform row, expressed as a fraction of root height.
     *
     * Using pinnedTopArea.height rather than .bottom makes this independent of
     * activity-level chrome (mini-recorder, mini-player) that can shift the
     * fragment's position within the window.
     */
    private fun calcSnapUpPercent(): Float {
        val parentH = binding.root.height.toFloat()
        if (parentH <= 0f) return 0.30f
        val rowH = binding.multiLineWaveform.lineHeightPx
            .takeIf { it > 0 } ?: (64 * resources.displayMetrics.density).toInt()
        val targetPx = binding.pinnedTopArea.height.toFloat() +
                binding.splitterBar.height.toFloat() +
                rowH.toFloat()
        return (targetPx / parentH).coerceIn(0.15f, 0.65f)
    }

    private fun animateGuideline(target: Float, endState: SplitterState) {
        val guideline = binding.splitterGuideline
        val currentPercent =
            (guideline.layoutParams as ConstraintLayout.LayoutParams).guidePercent
        ValueAnimator.ofFloat(currentPercent, target).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                guideline.setGuidelinePercent(anim.animatedValue as Float)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    splitterState = endState
                    binding.multiLineWaveform.fadesEnabled = (endState == SplitterState.SNAP_DOWN)
                    binding.multiLineWaveform.autoScrollEnabled = true
                }
            })
        }.start()
    }

    /**
     * Switches the chip FlowLayout between single-row (snap-down) and
     * multi-row wrapping (snap-up) modes.
     *
     * In snap-down mode (waveform dominant), Zone D is small — the strip
     * uses WRAP_CONTENT so it can exceed the scroller width and scroll
     * horizontally as a single row.
     *
     * In snap-up mode (marks dominant), Zone D is large — the strip width
     * is fixed to the scroller's pixel width so the FlowLayout can wrap
     * correctly into multiple rows. (HorizontalScrollView measures children
     * with UNSPECIFIED width, which would cause FlowLayout to see 0dp and
     * never wrap without this override.)
     */
    private fun applyChipLayoutMode(state: SplitterState) {
        val scrollerParams = binding.markChipScroller.layoutParams
                as ConstraintLayout.LayoutParams

        if (state == SplitterState.SNAP_DOWN) {
            // Show Zone A seek strip; hide Zone D.5
            binding.seekBar.visibility        = View.VISIBLE
            binding.timeLabelsRow.visibility  = View.VISIBLE
            binding.seekBarAreaBottom.visibility = View.GONE

            // Waveform dominant — let the scroller shrink to its content height
            // and pin it to the bottom of Zone D so it hugs Zone E.
            scrollerParams.height = ConstraintLayout.LayoutParams.WRAP_CONTENT
            scrollerParams.verticalBias = 1.0f
            binding.markChipScroller.layoutParams = scrollerParams

            binding.markTimestampList.singleRow = true
            binding.markTimestampList.updateLayoutParams {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            binding.markTimestampList.post { scrollToLastPassedChip() }
        } else {
            // Hide Zone A seek strip; show Zone D.5
            binding.seekBar.visibility        = View.GONE
            binding.timeLabelsRow.visibility  = View.GONE
            binding.seekBarAreaBottom.visibility = View.VISIBLE

            // Marks dominant — restore fill so Zone D uses all available space.
            scrollerParams.height = 0 // MATCH_CONSTRAINT
            scrollerParams.verticalBias = 0.5f
            binding.markChipScroller.layoutParams = scrollerParams

            binding.markTimestampList.singleRow = false
            binding.markTimestampList.updateLayoutParams {
                width = binding.markChipScroller.width
            }
            binding.markChipScroller.scrollTo(0, 0)
        }
    }

    // ── Transport ──────────────────────────────────────────────────────────────

    private fun setupTransportControls() {
        binding.btnPlayPause.setOnClickListener   { viewModel.togglePlayPause() }
        binding.btnSkipBack.setOnClickListener    { viewModel.skipBack() }
        binding.btnSkipForward.setOnClickListener { viewModel.skipForward() }
        binding.btnJumpPrev.setOnClickListener { viewModel.jumpMark(forward = false, select = false) }
        binding.btnJumpNext.setOnClickListener { viewModel.jumpMark(forward = true,  select = false) }

        val seekListener = fun(seekBar: SeekBar) = object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) { isSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                isSeeking = false
                viewModel.seekTo(sb.progress.toLong())
            }
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = viewModel.nowPlaying.value?.durationMs ?: return
                    // Keep whichever sibling seekbar is currently visible in sync.
                    syncSeekUi(progress.toLong(), dur)
                }
            }
        }
        binding.seekBar.setOnSeekBarChangeListener(seekListener(binding.seekBar))
        binding.seekBarBottom.setOnSeekBarChangeListener(seekListener(binding.seekBarBottom))
    }

    private fun syncSeekMax(durationMs: Long) {
        binding.seekBar.max       = durationMs.toInt()
        binding.seekBarBottom.max = durationMs.toInt()
    }

    /** Updates both seekbars and both label pairs from a single position value. */
    private fun syncSeekUi(positionMs: Long, durationMs: Long) {
        val dur = durationMs.coerceAtLeast(1L)
        binding.seekBar.progress       = positionMs.toInt()
        binding.seekBarBottom.progress = positionMs.toInt()
        binding.tvPosition.text        = formatMs(positionMs)
        binding.tvRemaining.text       = "-${formatMs(dur - positionMs)}"
        binding.tvPositionBottom.text  = formatMs(positionMs)
        binding.tvRemainingBottom.text = "-${formatMs(dur - positionMs)}"
    }

    // ── Waveform observers ────────────────────────────────────────────────────

    private fun observeWaveform() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.waveformState.collect { pair ->
                        val currentId = viewModel.nowPlaying.value?.recording?.id ?: return@collect
                        if (pair != null && pair.first == currentId) {
                            binding.multiLineWaveform.setAmplitudes(pair.second)
                        }
                    }
                }
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
            }
        }
    }

    // ── Now Playing observer ───────────────────────────────────────────────────

    private fun observeNowPlaying() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.nowPlaying.collect { state -> updateUi(state) }
                }
                launch {
                    viewModel.markJumpMs.collect { targetMs ->
                        binding.multiLineWaveform.jumpScrollToMs(targetMs)
                    }
                }
            }
        }
    }

    private fun updateUi(state: NowPlayingState?) {
        // ── Show/hide empty state vs player ───────────────────────────────────
        // playerContent is a ConstraintLayout Group referencing all five zones;
        // setting its visibility propagates to all referenced views atomically.
        binding.emptyState.visibility    = if (state == null) View.VISIBLE else View.GONE
        binding.playerContent.visibility = if (state == null) View.GONE    else View.VISIBLE

        if (state == null) {
            binding.tvTitle.text           = "Nothing playing"
            binding.tvRecordedAt.text      = ""
            binding.tvDuration.text        = ""
            binding.btnPlayPause.setIconResource(R.drawable.ic_play)
            binding.seekBar.max            = 1
            binding.seekBar.progress       = 0
            binding.seekBarBottom.max      = 1
            binding.seekBarBottom.progress = 0
            binding.seekBarAreaBottom.visibility = View.GONE
            binding.tvPosition.text        = "0:00"
            binding.tvRemaining.text       = "-0:00"
            binding.tvPositionBottom.text  = "0:00"
            binding.tvRemainingBottom.text = "-0:00"
            binding.multiLineWaveform.setPlayheadMs(-1L)
            return
        }

        binding.tvTitle.text = state.recording.title

        val topic = viewModel.allTopics.value.firstOrNull { it.id == state.recording.topicId }
        binding.tvTopicIcon.text = topic?.icon ?: Icons.INBOX
        binding.tvCategory.text  = topic?.name ?: getString(R.string.label_unsorted)

        binding.btnPlayPause.setIconResource(
            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

        if (!isSeeking) {
            val dur = state.durationMs.coerceAtLeast(1L)
            val pos = state.positionMs

            syncSeekMax(dur)
            syncSeekUi(state.positionMs, dur)

            binding.tvRecordedAt.text = formatDate(state.recording.createdAt)
            binding.tvDuration.text   = formatMs(state.recording.durationMs)

            binding.multiLineWaveform.setDurationMs(dur)
            binding.multiLineWaveform.setPlayheadMs(pos)
        }

        // Highlight the last-passed mark chip and scroll to it in snap-up mode.
        val marks = viewModel.marks.value
        if (marks.isNotEmpty()) {
            val pos    = state.positionMs
            val passed = marks.filter { it.positionMs <= pos }.maxByOrNull { it.positionMs }
            if (passed?.id != lastPassedMarkId) {
                lastPassedMarkId = passed?.id
                updateMarkChipStyles()
                scrollToLastPassedChip()
            }
        }
    }

    // ── Marks observer ────────────────────────────────────────────────────────

    private fun observeMarks() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Mark list → re-render chips
                launch {
                    viewModel.marks.collect { marks ->
                        renderMarkTimestamps(marks)
                        // MLWV mark rendering is handled atomically in the
                        // combined marks+selectedId collector below.
                    }
                }

                // Nudge controls + chip styles.
                launch {
                    combine(
                        viewModel.selectedMarkId,
                        viewModel.playbackMarkNudgeLocked
                    ) { selectedId, locked ->
                        selectedId to locked
                    }.collect { (selectedId, locked) ->
                        val hasSelection = selectedId != null
                        val canNudge     = hasSelection && !locked
                        val tealColor    = requireContext().themeColor(R.attr.colorMarkSelected)
                        val greyColor    = requireContext().themeColor(R.attr.colorTextSecondary)

                        // ── Zone 7 row: nudge buttons ────────────────────
                        listOf(
                            binding.btnMarkNudgeBack,
                            binding.btnMarkNudgeForward
                        ).forEach { v ->
                            v.isEnabled = canNudge
                            v.imageTintList = ColorStateList.valueOf(
                                if (canNudge) tealColor else greyColor
                            )
                            v.jumpDrawablesToCurrentState()
                        }

                        // Zone 7: delete (selection required, nudge lock irrelevant)
                        binding.btnMarkDelete.isEnabled = hasSelection
                        binding.btnMarkDelete.imageTintList = ColorStateList.valueOf(
                            if (hasSelection) tealColor else greyColor
                        )
                        binding.btnMarkDelete.jumpDrawablesToCurrentState()

                        // Zone 7: confirm card (requires canNudge to avoid committing
                        // while the lock is active)
                        binding.btnMarkConfirm.isEnabled = canNudge
                        binding.btnMarkConfirm.setCardBackgroundColor(
                            if (canNudge) tealColor else greyColor
                        )

                        updateMarkChipStyles()
                    }
                }

                // ── MultiLineWaveform: marks + selection (atomic) ──────────
                // Combine both flows so setMarksAndSelectedId is called in one
                // pass — prevents a frame where a newly selected mark renders
                // unselected between setMarks and setSelectedMarkId calls.
                launch {
                    combine(
                        viewModel.marks,
                        viewModel.selectedMarkId
                    ) { marks, selectedId ->
                        marks to selectedId
                    }.collect { (marks, selectedId) ->
                        binding.multiLineWaveform.setMarksAndSelectedId(
                            marks.map { WaveformMark(id = it.id, positionMs = it.positionMs) },
                            selectedId
                        )
                    }
                }

                launch {
                    combine(viewModel.hasMarkBehind, viewModel.hasMarkAhead) { behind, ahead -> behind to ahead }
                        .collect { (hasBehind, hasAhead) ->
                            val prevColor = requireContext().themeColor(if (hasBehind) R.attr.colorMarkDefault else R.attr.colorTextPrimary)
                            binding.btnJumpPrev.imageTintList = ColorStateList.valueOf(prevColor)

                            binding.btnJumpNext.isEnabled     = hasAhead
                            binding.btnJumpNext.alpha         = if (hasAhead) 1f else UiConstants.ALPHA_DISABLED
                            val nextColor = requireContext().themeColor(if (hasAhead) R.attr.colorMarkDefault else R.attr.colorTextPrimary)
                            binding.btnJumpNext.imageTintList = ColorStateList.valueOf(nextColor)
                        }
                }
            }
        }
    }

    // ── Mark chips ────────────────────────────────────────────────────────────

    private fun renderMarkTimestamps(marks: List<MarkEntity>) {
        val container = binding.markTimestampList
        container.removeAllViews()
        markChips.clear()

        val density = resources.displayMetrics.density

        marks.forEach { mark ->
            val chip = TextView(requireContext()).apply {
                text = formatMs(mark.positionMs)
                textSize = 12f
                setPadding(
                    (10 * density).toInt(), (4 * density).toInt(),
                    (10 * density).toInt(), (4 * density).toInt()
                )
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12 * density
                    setColor(context.themeColor(R.attr.colorSurfaceElevated))
                }
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    val m = (4 * density).toInt()
                    setMargins(m, m, m, m)
                }
                val gd = GestureDetectorCompat(requireContext(),
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            viewModel.selectMark(mark.id)
                            viewModel.unlockPlaybackMarkNudge()
                            return true
                        }
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            viewModel.seekToMark(mark.positionMs)
                            viewModel.selectMark(mark.id)
                            viewModel.unlockPlaybackMarkNudge()
                            return true
                        }
                    })
                setOnTouchListener { _, event ->
                    gd.onTouchEvent(event)
                    true
                }
            }
            markChips[mark.id] = chip
            container.addView(chip)
        }

        updateMarkChipStyles()
    }

    private fun updateMarkChipStyles() {
        val selectedId = viewModel.selectedMarkId.value
        val density    = resources.displayMetrics.density
        markChips.forEach { (markId, chip) ->
            val isSelected   = markId == selectedId
            val isLastPassed = markId == lastPassedMarkId
            val ctx = requireContext()

            val bg = chip.background as? GradientDrawable ?: return@forEach

            bg.setColor(when {
                isLastPassed -> ctx.themeColor(R.attr.colorMarkDefault)
                else         -> ctx.themeColor(R.attr.colorSurfaceElevated)
            })

            chip.setTextColor(
                if (isLastPassed) 0xFF_FFFFFF.toInt()
                else ctx.themeColor(R.attr.colorTextSecondary)
            )

            if (isSelected) {
                bg.setStroke(
                    (2.5f * density).toInt(),
                    ctx.themeColor(R.attr.colorMarkSelected)
                )
            } else {
                bg.setStroke(0, 0)
            }
        }
    }

    /**
     * Scrolls the chip strip so the last-passed chip is visible.
     * Only acts in snap-up (single-row) mode — in snap-down multi-row mode
     * chips fill the full width and no horizontal scroll is needed.
     */
    private fun scrollToLastPassedChip() {
        if (splitterState != SplitterState.SNAP_DOWN) return
        val chip = markChips[lastPassedMarkId] ?: return
        val leadPx = (32 * resources.displayMetrics.density).toInt()
        val scrollX = (chip.left - leadPx).coerceAtLeast(0)
        binding.markChipScroller.smoothScrollTo(scrollX, 0)
    }

    // ── Topic header ──────────────────────────────────────────────────────────

    private fun setupTopicHeader() {
        // Result listener must be registered before the sheet is shown.
        childFragmentManager.setFragmentResultListener(
            TopicPickerBottomSheet.REQUEST_KEY, viewLifecycleOwner
        ) { _, bundle ->
            val topicId = TopicPickerBottomSheet.topicIdFromBundle(bundle)
            val recId = viewModel.nowPlaying.value?.recording?.id ?: return@setFragmentResultListener
            viewModel.moveRecording(recId, topicId)
            // Optimistic icon update — nowPlaying observer will also sync it.
            val topic = viewModel.allTopics.value.firstOrNull { it.id == topicId }
            binding.tvTopicIcon.text = topic?.icon ?: Icons.INBOX
        }

        binding.tvTopicIcon.setOnClickListener {
            val recording = viewModel.nowPlaying.value?.recording ?: return@setOnClickListener
            TopicPickerBottomSheet.newInstance(recording.topicId)
                .show(childFragmentManager, "topic_picker")
        }
        binding.layoutTitleArea.setOnClickListener {
            showRenameDialog()
        }
    }

    private fun showRenameDialog() {
        val recording = viewModel.nowPlaying.value?.recording ?: return
        val editText = android.widget.EditText(requireContext()).apply {
            setText(recording.title)
            selectAll()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Rename Recording")
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) viewModel.renameRecording(recording.id, newName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Marks panel ───────────────────────────────────────────────────────────

    private fun setupMarksPanel() {
        // ── Zone 7b persistent row ────────────────────────────────────────────
        binding.btnMarkNudgeBack.setOnClickListener    { viewModel.nudgePlaybackMarkBack() }
        binding.btnMarkNudgeForward.setOnClickListener { viewModel.nudgePlaybackMarkForward() }
        binding.btnMarkDelete.setOnClickListener       { viewModel.deleteSelectedMark() }
        binding.btnMarkConfirm.setOnClickListener      { viewModel.commitPlaybackMarkNudge() }
        binding.btnAddMark.setOnClickListener          { viewModel.addMark() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatMs(ms: Long): String {
        val s = ms / 1000; val m = s / 60
        return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s % 60)
        else "%d:%02d".format(m, s % 60)
    }

    private fun formatDate(epochMs: Long): String =
        java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(epochMs))
}