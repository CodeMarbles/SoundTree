package com.treecast.app.ui.listen

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.isVisible
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
        binding.btnPlayPause.setOnClickListener  { viewModel.togglePlayPause() }
        binding.btnSkipBack.setOnClickListener   { viewModel.skipBack15() }
        binding.btnSkipForward.setOnClickListener { viewModel.skipForward15() }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) { isSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                isSeeking = false
                viewModel.seekTo(sb.progress.toLong())
            }
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = viewModel.nowPlaying.value?.durationMs ?: 1L
                    binding.tvPosition.text = formatMs(progress.toLong())
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

        // Label colours
        binding.tabCategorize.setTextColor(
            if (tab == 0) requireContext().getColor(R.color.text_primary)
            else requireContext().getColor(R.color.text_dim)
        )
        binding.tabMarks.setTextColor(
            if (tab == 1) requireContext().getColor(R.color.text_primary)
            else requireContext().getColor(R.color.text_dim)
        )

        // Slide the indicator to the active tab
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

        // Show / hide panels
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
        binding.btnAddMark.setOnClickListener { viewModel.addMark() }
        binding.btnDeleteMark.setOnClickListener { viewModel.deleteSelectedMark() }
    }

    private fun observeMarks() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.marks.collect { marks -> renderMarkTimestamps(marks) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedMarkId.collect { selectedId ->
                // Update waveform highlight
                binding.waveformView.setSelectedMark(selectedId)
                // Enable / disable delete button
                val hasSelection = selectedId != null
                binding.btnDeleteMark.isEnabled = hasSelection
                binding.btnDeleteMark.alpha = if (hasSelection) 1f else 0.4f
            }
        }
    }

    private fun renderMarkTimestamps(marks: List<MarkEntity>) {
        val container = binding.markTimestampList
        container.removeAllViews()

        val density = resources.displayMetrics.density
        val state = viewModel.nowPlaying.value
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

        val selectedId = viewModel.selectedMarkId.value
        for (mark in marks) {
            val chip = TextView(requireContext()).apply {
                text = formatMs(mark.positionMs)
                textSize = 12f
                val isSelected = mark.id == selectedId
                setTextColor(
                    if (isSelected) requireContext().getColor(R.color.accent)
                    else requireContext().getColor(R.color.text_primary)
                )
                val pad = (10 * density).toInt()
                val padV = (4 * density).toInt()
                setPadding(pad, padV, pad, padV)
                background = if (isSelected) {
                    // Tinted chip background
                    androidx.core.content.ContextCompat.getDrawable(
                        requireContext(), R.drawable.bg_picker
                    )
                } else null
                setOnClickListener {
                    val alreadySelected = viewModel.selectedMarkId.value == mark.id
                    viewModel.selectMark(if (alreadySelected) null else mark.id)
                    // Seek to the mark position
                    if (!alreadySelected) viewModel.seekTo(mark.positionMs)
                }
            }
            container.addView(chip)

            // Small spacer between chips
            val spacer = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (4 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(spacer)
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
        binding.waveformView.setSeed(rec.id)
        val frac = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
        binding.waveformView.setProgress(frac)

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

        // Category picker
        val catName = rec.categoryId?.let { id ->
            viewModel.allCategories.value.find { it.id == id }?.name
        } ?: "Uncategorised"
        binding.categoryPicker.setSelectedCategory(rec.categoryId, catName)
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    private fun formatDateTime(epochMs: Long): String =
        SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault()).format(Date(epochMs))
}
