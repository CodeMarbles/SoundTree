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
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.GestureDetectorCompat
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

    private enum class SplitterState { SNAP_DOWN, SNAP_UP }
    private var splitterState = SplitterState.SNAP_DOWN

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
        setupTransportControls()
        setupSplitter()
        setupTopicHeader()
        setupMarksPanel()
        setupMultiLineWaveform()
        observeNowPlaying()
        observeMarks()
        observeWaveform()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Multi-line waveform setup ──────────────────────────────────────────────

    private fun setupMultiLineWaveform() {
        binding.multiLineWaveform.scaleToFill = true
        // 5-minute lines on the Listen tab — adjust when zoom controls arrive.
        binding.multiLineWaveform.secondsPerLine   = 300
        binding.multiLineWaveform.showPlayedSplit  = true

        binding.multiLineWaveform.onTimeSelected = { positionMs, type ->
            when (type) {
                WaveformTapType.TAP        -> viewModel.seekTo(positionMs)
                WaveformTapType.LONG_PRESS -> { /* reserved for future use */ }
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
    }

    private fun snapTo(state: SplitterState) {
        val target = if (state == SplitterState.SNAP_DOWN) 0.62f else 0.18f
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
                    splitterState = state
                    binding.multiLineWaveform.fadesEnabled = (state == SplitterState.SNAP_DOWN)
                }
            })
        }.start()
    }

    // ── Transport ──────────────────────────────────────────────────────────────

    private fun setupTransportControls() {
        binding.btnPlayPause.setOnClickListener   { viewModel.togglePlayPause() }
        binding.btnSkipBack.setOnClickListener    { viewModel.skipBack() }
        binding.btnSkipForward.setOnClickListener { viewModel.skipForward() }
        binding.btnJumpPrev.setOnClickListener    { viewModel.jumpToPrevMark() }
        binding.btnJumpNext.setOnClickListener    { viewModel.jumpToNextMark() }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) { isSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                isSeeking = false
                viewModel.seekTo(sb.progress.toLong())
            }
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = viewModel.nowPlaying.value?.durationMs ?: return
                    binding.tvPosition.text  = formatMs(progress.toLong())
                    binding.tvRemaining.text = "-${formatMs(dur - progress)}"
                }
            }
        })
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
            binding.tvTitle.text     = "Nothing playing"
            binding.tvPosition.text  = "0:00"
            binding.tvRemaining.text = "-0:00"
            // btnPlayPause is now a MaterialButton — use setIconResource, not setImageResource
            binding.btnPlayPause.setIconResource(R.drawable.ic_play)
            binding.seekBar.progress = 0
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

            binding.seekBar.max      = dur.toInt()
            binding.seekBar.progress = pos.toInt()
            binding.tvPosition.text  = formatMs(pos)
            binding.tvRemaining.text = "-${formatMs(dur - pos)}"

            binding.multiLineWaveform.setDurationMs(dur)
            binding.multiLineWaveform.setPlayheadMs(pos)
        }

        // Highlight the last-passed mark chip
        val marks = viewModel.marks.value
        if (marks.isNotEmpty()) {
            val pos    = state.positionMs
            val passed = marks.filter { it.positionMs <= pos }.maxByOrNull { it.positionMs }
            if (passed?.id != lastPassedMarkId) {
                lastPassedMarkId = passed?.id
                updateMarkChipStyles()
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

                // ── Nudge controls + chip styles + card show/hide ──────────
                // Combines selectedMarkId and playbackMarkNudgeLocked so both
                // control states are evaluated together in one pass.
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

                        // ── Zone 7b row: nudge buttons ────────────────────
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

                        // Zone 7b: delete (selection required, nudge lock irrelevant)
                        binding.btnMarkDelete.isEnabled = hasSelection
                        binding.btnMarkDelete.imageTintList = ColorStateList.valueOf(
                            if (hasSelection) tealColor else greyColor
                        )
                        binding.btnMarkDelete.jumpDrawablesToCurrentState()

                        // Zone 7b: confirm card (requires canNudge to avoid committing
                        // while the lock is active)
                        binding.btnMarkConfirm.isEnabled = canNudge
                        binding.btnMarkConfirm.setCardBackgroundColor(
                            if (canNudge) tealColor else greyColor
                        )

                        // ── Zone 7a card: show/hide + timestamp ───────────
                        if (hasSelection) {
                            val mark = viewModel.marks.value
                                .firstOrNull { it.id == selectedId }
                            if (mark != null) {
                                binding.tvMarkTimestamp.text = formatMs(mark.positionMs)
                            }
                            binding.markExtendedControls.visibility = View.VISIBLE
                        } else {
                            binding.markExtendedControls.visibility = View.GONE
                        }

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
            }
        }
    }

    // ── Mark chips ────────────────────────────────────────────────────────────

    private fun renderMarkTimestamps(marks: List<MarkEntity>) {
        val container = binding.markTimestampList
        container.removeAllViews()
        markChips.clear()

        val density  = resources.displayMetrics.density
        val duration = viewModel.nowPlaying.value?.durationMs ?: 1L

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
                            viewModel.seekTo(mark.positionMs)
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

        // ── Zone 7a extended controls card ───────────────────────────────────
        // Card buttons share the same ViewModel calls as the row. The card is
        // only visible when a mark is selected, so no grey/active styling needed.
        binding.cardMarkNudgeBack.setOnClickListener    { viewModel.nudgePlaybackMarkBack() }
        binding.cardMarkNudgeForward.setOnClickListener { viewModel.nudgePlaybackMarkForward() }
        binding.cardMarkDelete.setOnClickListener       { viewModel.deleteSelectedMark() }
        binding.cardMarkConfirm.setOnClickListener      { viewModel.commitPlaybackMarkNudge() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatMs(ms: Long): String {
        val s = ms / 1000; val m = s / 60
        return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s % 60)
        else "%d:%02d".format(m, s % 60)
    }
}