package com.treecast.app.ui.listen

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
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
        setupTopicHeader()
        setupMarksPanel()
        setupMultiLineWaveform()   // ← new
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
                viewModel.waveformState.collect { pair ->
                    val currentId = viewModel.nowPlaying.value?.recording?.id ?: return@collect
                    if (pair != null && pair.first == currentId) {
                        // Feed both the old and new waveform views.
                        binding.multiLineWaveform.setAmplitudes(pair.second)
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
        binding.emptyState.visibility    = if (state == null) View.VISIBLE else View.GONE
        binding.playerContent.visibility = if (state == null) View.GONE    else View.VISIBLE

        if (state == null) {
            binding.tvTitle.text     = "Nothing playing"
            binding.tvPosition.text  = "0:00"
            binding.tvRemaining.text = "-0:00"
            binding.btnPlayPause.setImageResource(R.drawable.ic_play)
            binding.seekBar.progress = 0
            binding.multiLineWaveform.setPlayheadMs(-1L)
            return
        }

        binding.tvTitle.text = state.recording.title

        val topic = viewModel.allTopics.value.firstOrNull { it.id == state.recording.topicId }
        binding.tvTopicIcon.text = topic?.icon ?: Icons.INBOX
        binding.tvCategory.text  = topic?.name ?: getString(R.string.label_unsorted)

        binding.btnPlayPause.setImageResource(
            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

        if (!isSeeking) {
            val dur = state.durationMs.coerceAtLeast(1L)
            val pos = state.positionMs

            binding.seekBar.max      = dur.toInt()
            binding.seekBar.progress = pos.toInt()
            binding.tvPosition.text  = formatMs(pos)
            binding.tvRemaining.text = "-${formatMs(dur - pos)}"

            // New waveform (ms-based) — also keep duration in sync
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
                // Mark list
                launch {
                    viewModel.marks.collect { marks ->
                        renderMarkTimestamps(marks)

                        // Feed the new waveform component.
                        binding.multiLineWaveform.setMarks(
                            marks.map { WaveformMark(id = it.id, positionMs = it.positionMs) }
                        )
                    }
                }

                // Selected mark
                launch {
                    combine(
                        viewModel.selectedMarkId,
                        viewModel.marks
                    ) { selectedId, marks ->
                        selectedId to marks
                    }.collect { (selectedId, marks) ->
                        val selectedMark = marks.firstOrNull { it.id == selectedId }

                        binding.multiLineWaveform.setSelectedMarkId(selectedId)

                        // Nudge controls — unchanged logic from before
                        val canNudge   = viewModel.playbackMarkNudgeLocked.value.not()
                        val nudgeColor = if (canNudge)
                            requireContext().themeColor(R.attr.colorMarkSelected)
                        else
                            requireContext().themeColor(R.attr.colorTextSecondary)

                        listOf(
                            binding.btnMarkNudgeBack,
                            binding.btnMarkNudgeForward,
                            binding.btnMarkCommit
                        ).forEach { v ->
                            v.isEnabled = canNudge
                            v.imageTintList = android.content.res.ColorStateList.valueOf(nudgeColor)
                            v.jumpDrawablesToCurrentState()
                        }

                        updateMarkChipStyles()
                    }
                }

                // Nudge lock state (kept separate so nudge-lock changes don't
                // force a full mark re-render)
                launch {
                    viewModel.playbackMarkNudgeLocked.collect {
                        updateMarkChipStyles()
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
        binding.btnMarkNudgeBack.setOnClickListener    { viewModel.requestNudgeBack() }
        binding.btnMarkNudgeForward.setOnClickListener { viewModel.requestNudgeForward() }
        binding.btnMarkCommit.setOnClickListener       { viewModel.commitMarkNudge() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatMs(ms: Long): String {
        val s = ms / 1000; val m = s / 60
        return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s % 60)
        else "%d:%02d".format(m, s % 60)
    }
}