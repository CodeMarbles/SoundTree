package com.treecast.app.ui.topics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.treecast.app.R
import com.treecast.app.databinding.BottomSheetPlaybackBinding
import com.treecast.app.ui.MainViewModel
import com.treecast.app.ui.play
import com.treecast.app.ui.seekTo
import com.treecast.app.ui.skipBack
import com.treecast.app.ui.skipForward
import com.treecast.app.ui.togglePlayPause
import com.treecast.app.util.Icons
import kotlinx.coroutines.launch

class PlaybackBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_ID = "recording_id"
        fun newInstance(id: Long) = PlaybackBottomSheet().apply {
            arguments = Bundle().apply { putLong(ARG_ID, id) }
        }
    }

    private var _binding: BottomSheetPlaybackBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetPlaybackBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recordingId = arguments?.getLong(ARG_ID) ?: return
        val recording   = viewModel.allRecordings.value.find { it.id == recordingId }
            ?: run { dismiss(); return }

        viewModel.play(recording)

        binding.btnPlayPause.setOnClickListener { viewModel.togglePlayPause() }
        binding.btnRewind.setOnClickListener    { viewModel.skipBack() }
        binding.btnForward.setOnClickListener   { viewModel.skipForward() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allTopics.collect { topics ->
                    binding.topicPicker.setTopics(topics)
                    val topic = recording.topicId?.let { id -> topics.find { it.id == id } }
                    binding.topicPicker.setSelectedTopic(
                        recording.topicId,
                        topic?.name ?: getString(R.string.topic_label_unsorted),
                        topic?.icon ?: Icons.UNSORTED
                    )
                }
            }
        }
        binding.topicPicker.onTopicSelected = { topicId ->
            viewModel.moveRecording(recordingId, topicId)
        }

        // Seek bar
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { viewModel.seekTo(sb.progress.toLong()) }
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) viewModel.seekTo(progress.toLong())
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.nowPlaying.collect { state ->
                if (state == null) return@collect
                val dur = state.durationMs.coerceAtLeast(1L)
                val pos = state.positionMs
                binding.seekBar.max      = dur.toInt()
                binding.seekBar.progress = pos.toInt()
                binding.tvPosition.text  = formatMs(pos)
                binding.tvRemaining.text = "-${formatMs(dur - pos)}"
                binding.btnPlayPause.text = if (state.isPlaying) "⏸" else "▶"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000; val m = s / 60
        return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s % 60)
        else "%d:%02d".format(m, s % 60)
    }
}