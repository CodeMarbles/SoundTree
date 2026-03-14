package com.treecast.app.ui.library.all

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.treecast.app.databinding.FragmentAllRecordingsBinding
import com.treecast.app.data.entities.RecordingEntity
import com.treecast.app.ui.MainActivity
import com.treecast.app.ui.MainViewModel
import kotlinx.coroutines.launch

/**
 * ALL tab — flat chronological list of every recording.
 *
 * A sort toggle button at the top switches between newest-first and oldest-first.
 * Each row shows the topic icon to the left of the recording info.
 */
class AllRecordingsFragment : Fragment() {

    private var _binding: FragmentAllRecordingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: AllRecordingsAdapter

    /** true = newest first (default), false = oldest first */
    private var newestFirst = true

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAllRecordingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = AllRecordingsAdapter(
            onPlayPause = { rec ->
                val nowPlaying = viewModel.nowPlaying.value
                if (nowPlaying?.recording?.id == rec.id) {
                    viewModel.togglePlayPause()
                } else {
                    viewModel.play(rec)
                    if (viewModel.autoNavigateToListen.value) {
                        (requireActivity() as? MainActivity)?.navigateTo(MainActivity.PAGE_LISTEN)
                    }
                }
            },
            onRename = { id, title -> viewModel.renameRecording(id, title) },
            onMove   = { id, topicId -> viewModel.moveRecording(id, topicId) },
            onDelete = { rec -> viewModel.deleteRecording(rec) },
            onSelect = { id -> viewModel.selectRecording(id) },
        )

        binding.recyclerAllRecordings.apply {
            this.adapter = this@AllRecordingsFragment.adapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        binding.btnSortOrder.setOnClickListener {
            newestFirst = !newestFirst
            updateSortButtonLabel()
            submitSorted(viewModel.allRecordings.value)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.allTopics.collect { topics ->
                        adapter.topics = topics
                    }
                }
                launch {
                    viewModel.allRecordings.collect { recordings ->
                        submitSorted(recordings)
                    }
                }
                launch {
                    viewModel.nowPlaying.collect { state ->
                        adapter.nowPlayingId = state?.recording?.id ?: -1L
                        adapter.isPlaying    = state?.isPlaying ?: false
                    }
                }
                launch {
                    viewModel.orphanVolumeUuids.collect { uuids ->
                        adapter.orphanVolumeUuids = uuids
                    }
                }
                launch {
                    viewModel.selectedRecordingId.collect { id ->
                        adapter.selectedRecordingId = id
                    }
                }
            }
        }
    }

    private fun submitSorted(recordings: List<RecordingEntity>) {
        val sorted = if (newestFirst) {
            recordings.sortedByDescending { it.createdAt }
        } else {
            recordings.sortedBy { it.createdAt }
        }
        adapter.submitList(sorted)
    }

    private fun updateSortButtonLabel() {
        binding.btnSortOrder.text = if (newestFirst) "↓ NEWEST FIRST" else "↑ OLDEST FIRST"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStorageVolumes()
    }
}