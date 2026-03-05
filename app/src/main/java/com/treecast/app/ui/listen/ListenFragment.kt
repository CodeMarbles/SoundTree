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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.treecast.app.R
import com.treecast.app.data.entities.MarkEntity
import com.treecast.app.databinding.FragmentListenBinding
import com.treecast.app.ui.MainViewModel
import com.treecast.app.ui.NowPlayingState
import com.treecast.app.util.Icons
import kotlinx.coroutines.launch

import com.treecast.app.util.themeColor


class ListenFragment : Fragment() {

    private var _binding: FragmentListenBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private var isSeeking = false

    private var selectedTab = 0
    private var defaultTabRecordingId: Long? = null
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
        setupMiniTabs()
        setupTopicPicker()
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
        binding.tabCategorize.setTextColor(
            if (tab == 0) requireContext().themeColor(R.attr.colorTextPrimary)
            else requireContext().themeColor(R.attr.colorTextSecondary)
        )
        binding.tabMarks.setTextColor(
            if (tab == 1) requireContext().themeColor(R.attr.colorTextPrimary)
            else requireContext().themeColor(R.attr.colorTextSecondary)
        )

        val indicator = binding.tabIndicator
        val parent = indicator.parent as View
        parent.post {
            val tabWidth    = parent.width / 2
            val targetWidth = (tabWidth * 0.6f).toInt()
            val offset = if (tab == 0) (tabWidth - targetWidth) / 2
            else tabWidth + (tabWidth - targetWidth) / 2
            val params = indicator.layoutParams
            params.width = targetWidth
            indicator.layoutParams = params
            indicator.translationX = offset.toFloat()
        }

        // fragment_listen.xml: binding.topicPicker (was categoryPicker — step-3 XML edit)
        binding.topicPicker.visibility = if (tab == 0) View.VISIBLE else View.GONE
        binding.marksPanel.visibility  = if (tab == 1) View.VISIBLE else View.GONE
    }

    // ── Topic picker ───────────────────────────────────────────────────
    private fun setupTopicPicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.allTopics.collect { topics ->
                        binding.topicPicker.setTopics(topics)
                    }
                }
            }
        }
        binding.topicPicker.onTopicSelected = { topicId ->
            viewModel.nowPlaying.value?.recording?.id?.let { recId ->
                viewModel.moveRecording(recId, topicId)
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
                setOnClickListener {
                    viewModel.seekTo(mark.positionMs)
                    viewModel.selectMark(mark.id)
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
        markChips.forEach { (markId, chip) ->
            val isSelected   = markId == selectedId
            val isLastPassed = markId == lastPassedMarkId
            val ctx = requireContext()
            (chip.background as? GradientDrawable)?.setColor(when {
                isSelected   -> ctx.themeColor(R.attr.colorMarkSelected)
                isLastPassed -> ctx.themeColor(R.attr.colorMarkDefault)
                else         -> ctx.themeColor(R.attr.colorSurfaceElevated)
            })
            chip.setTextColor(
                if (isSelected || isLastPassed) 0xFF_FFFFFF.toInt()
                else ctx.themeColor(R.attr.colorTextSecondary)
            )
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

        // Auto-select default tab once per recording load
        if (defaultTabRecordingId != state.recording.id) {
            defaultTabRecordingId = state.recording.id

            // Show fake waveform instantly, then replace with real data
            binding.waveformView.setSeed(state.recording.id)
            viewModel.loadWaveform(
                recordingId = state.recording.id,
                filePath    = state.recording.filePath
            )

            selectTab(if (state.recording.topicId == null) 0 else 1)

            val topic = viewModel.allTopics.value.find { it.id == state.recording.topicId }
            binding.topicPicker.setSelectedTopic(
                state.recording.topicId,
                topic?.name ?: "Uncategorised",
                topic?.icon ?: Icons.INBOX
            )
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