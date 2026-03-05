package com.treecast.app.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle                    // ← new import
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle            // ← new import
import androidx.recyclerview.widget.LinearLayoutManager
import com.treecast.app.databinding.FragmentInboxTileBinding
import com.treecast.app.ui.MainActivity
import com.treecast.app.ui.MainViewModel
import com.treecast.app.ui.topics.RecordingsAdapter
import kotlinx.coroutines.launch

class InboxTileFragment : Fragment() {

    private var _binding: FragmentInboxTileBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: RecordingsAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInboxTileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = RecordingsAdapter(
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

        binding.recyclerInbox.apply {
            this.adapter = this@InboxTileFragment.adapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.allTopics.collect { topics ->
                        adapter.topics = topics
                    }
                }
                launch {
                    viewModel.nowPlaying.collect { state ->
                        adapter.nowPlayingId = state?.recording?.id ?: -1L
                        adapter.isPlaying    = state?.isPlaying ?: false
                    }
                }
                launch {
                    viewModel.selectedRecordingId.collect { id ->
                        adapter.selectedRecordingId = id
                    }
                }
                launch {
                    viewModel.inbox.collect { recs ->
                        adapter.submitList(recs)
                        binding.tvEmpty.visibility = if (recs.isEmpty()) View.VISIBLE else View.GONE
                        binding.tvCount.text = if (recs.isEmpty()) "" else "${recs.size} unorganised"
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}