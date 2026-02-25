package com.treecast.app.ui.tree

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.treecast.app.databinding.BottomSheetPlaybackBinding
import com.treecast.app.ui.MainViewModel
import kotlinx.coroutines.launch

/**
 * Bottom sheet player — thin wrapper over MainViewModel's shared player.
 * No MediaPlayer here; all playback is delegated so the Mini Player
 * and Listen tab stay perfectly in sync.
 */
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
        val recording = viewModel.allRecordings.value.find { it.id == recordingId }
            ?: run { dismiss(); return }

        // Start playing immediately via shared ViewModel player
        viewModel.play(recording)

        binding.btnPlayPause.setOnClickListener { viewModel.togglePlayPause() }
        binding.btnRewind.setOnClickListener    { viewModel.skipBack() }
        binding.btnForward.setOnClickListener   { viewModel.skipForward() }

        binding.btnFavourite.apply {
            text = if (recording.isFavourite) "💔  Unfavourite" else "❤️  Add to Favourites"
            setOnClickListener {
                val newFav = viewModel.nowPlaying.value?.recording?.isFavourite?.not() ?: false
                viewModel.setFavourite(recordingId, newFav)
                text = if (newFav) "💔  Unfavourite" else "❤️  Add to Favourites"
            }
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { viewModel.seekTo(sb.progress.toLong()) }
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.tvPosition.text = formatMs(progress.toLong())
            }
        })

        // Category picker
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.allCategories.collect { cats -> binding.categoryPicker.setCategories(cats) }
        }
        if (recording.categoryId != null) {
            val catName = viewModel.allCategories.value.find { it.id == recording.categoryId }?.name
                ?: "Uncategorised"
            binding.categoryPicker.setSelectedCategory(recording.categoryId, catName)
        }
        binding.categoryPicker.onCategorySelected = { catId ->
            viewModel.moveRecording(recordingId, catId)
        }

        // Observe shared state to keep UI in sync
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.nowPlaying.collect { state ->
                if (state == null) return@collect
                binding.tvTitle.text    = state.recording.title
                binding.tvDuration.text = formatMs(state.durationMs)
                binding.seekBar.max     = state.durationMs.toInt()
                binding.seekBar.progress = state.positionMs.toInt()
                binding.tvPosition.text = formatMs(state.positionMs)
                binding.btnPlayPause.text = if (state.isPlaying) "⏸" else "▶"
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
