package com.treecast.app.ui.topics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.treecast.app.databinding.FragmentTopicsBinding
import com.treecast.app.ui.MainActivity
import com.treecast.app.ui.MainViewModel
import kotlinx.coroutines.launch

class TopicsFragment : Fragment() {

    private var _binding: FragmentTopicsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var topicItemAdapter: TopicItemAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTopicsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        topicItemAdapter = TopicItemAdapter(
            onTopicClick = { node, isCollapsed ->
                viewModel.toggleCollapse(node.topic.id, isCollapsed)
            },
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
            onDelete = { rec -> viewModel.deleteRecording(rec) }
        )

        binding.recyclerTopics.apply {
            adapter = topicItemAdapter
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(false)
        }

        binding.fabAddTopic.setOnClickListener {
            NewTopicDialog(parentId = null) { name, icon, color ->
                viewModel.createTopic(name, null, icon, color)
            }.show(childFragmentManager, "new_topic")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.allTopics.collect { topics -> topicItemAdapter.topics = topics }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.treeItems.collect { items -> topicItemAdapter.submitList(items) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.nowPlaying.collect { state ->
                topicItemAdapter.nowPlayingId = state?.recording?.id ?: -1L
                topicItemAdapter.isPlaying = state?.isPlaying ?: false
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}