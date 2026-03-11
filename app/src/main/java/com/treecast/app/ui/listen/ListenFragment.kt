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
import com.treecast.app.util.Icons
import kotlinx.coroutines.launch
import com.treecast.app.util.themeColor


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
        observeNowPlaying()
        observeMarks()
        observeWaveform()
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
        binding.btnJumpPrev.setOnClickListener { viewModel.jumpToPrevMark() }
        binding.btnJumpNext.setOnClickListener { viewModel.jumpToNextMark() }

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

    // ── Topic header ───────────────────────────────────────────────────
    private fun setupTopicHeader() {
        childFragmentManager.setFragmentResultListener(
            TopicPickerBottomSheet.REQUEST_KEY, viewLifecycleOwner
        ) { _, bundle ->
            val topicId = TopicPickerBottomSheet.topicIdFromBundle(bundle)
            viewModel.nowPlaying.value?.recording?.id?.let { recId ->
                viewModel.moveRecording(recId, topicId)
            }
        }

        binding.topicHeader.setOnClickListener {
            val currentTopicId = viewModel.nowPlaying.value?.recording?.topicId
            TopicPickerBottomSheet.newInstance(currentTopicId)
                .show(childFragmentManager, "topic_picker")
        }
    }

    // ── Marks panel ────────────────────────────────────────────────────
    private fun setupMarksPanel() {
        binding.btnAddMark.setOnClickListener    { viewModel.addMark() }
        binding.btnDeleteMark.setOnClickListener { viewModel.deleteSelectedMark() }
    }

    private fun observeMarks() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.marks.collect { marks -> renderMarkTimestamps(marks) }
                }
                launch {
                    viewModel.selectedMarkId.collect { selectedId ->
                        binding.waveformView.setSelectedMark(selectedId)
                        val hasSelection = selectedId != null
                        binding.btnDeleteMark.isEnabled = hasSelection
                        binding.btnDeleteMark.alpha = if (hasSelection) 1f else 0.4f
                        updateMarkChipStyles()
                    }
                }
            }
        }
    }

    private fun observeWaveform() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.waveformState.collect { pair ->
                        val currentId = viewModel.nowPlaying.value?.recording?.id ?: return@collect
                        if (pair != null && pair.first == currentId) {
                            binding.waveformView.setAmplitudes(pair.second)
                        }
                    }
                }
            }
        }
    }

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
                            return true
                        }
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            viewModel.seekTo(mark.positionMs)
                            viewModel.selectMark(mark.id)
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

        binding.waveformView.setMarks(
            marks.map { it.positionMs.toFloat() / duration.toFloat() to it.id }
        )
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

            // Fill reflects playback progress (unchanged from before)
            bg.setColor(when {
                isLastPassed -> ctx.themeColor(R.attr.colorMarkDefault)
                else         -> ctx.themeColor(R.attr.colorSurfaceElevated)
            })

            // Text colour follows fill
            chip.setTextColor(
                if (isLastPassed) 0xFF_FFFFFF.toInt()
                else ctx.themeColor(R.attr.colorTextSecondary)
            )

            // Selection = accent stroke, not fill
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

    // ── Now Playing observer ───────────────────────────────────────────
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
        // ── Show/hide empty state vs player ──────────────────────────────
        binding.emptyState.visibility    = if (state == null) View.VISIBLE else View.GONE
        binding.playerContent.visibility = if (state == null) View.GONE    else View.VISIBLE

        if (state == null) {
            binding.tvTitle.text     = "Nothing playing"
            binding.tvPosition.text  = "0:00"
            binding.tvRemaining.text = "-0:00"
            binding.btnPlayPause.setImageResource(R.drawable.ic_play)
            binding.seekBar.progress = 0
            binding.waveformView.setProgress(0f)
            return
        }

        binding.tvTitle.text = state.recording.title

        // Populate the topic header
        val topic = viewModel.allTopics.value.firstOrNull { it.id == state.recording.topicId }
        binding.tvTopicIcon.text = topic?.icon ?: Icons.INBOX
        binding.tvCategory.text  = topic?.name ?: "Uncategorised"

        binding.btnPlayPause.setImageResource(
            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

        if (!isSeeking) {
            val dur = state.durationMs.coerceAtLeast(1L)
            val pos = state.positionMs
            binding.seekBar.max      = dur.toInt()
            binding.seekBar.progress = pos.toInt()
            binding.tvPosition.text  = formatMs(pos)
            binding.tvRemaining.text = "-${formatMs(dur - pos)}"
            binding.waveformView.setProgress(pos.toFloat() / dur.toFloat())
        }

        // Highlight the last-passed mark
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

    private fun formatMs(ms: Long): String {
        val s = ms / 1000; val m = s / 60
        return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s % 60)
        else "%d:%02d".format(m, s % 60)
    }
}