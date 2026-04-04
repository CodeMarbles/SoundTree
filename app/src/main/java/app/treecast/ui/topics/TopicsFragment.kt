package app.treecast.ui.topics

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
import app.treecast.databinding.FragmentTopicsBinding
import app.treecast.ui.MainActivity
import app.treecast.ui.MainViewModel
import app.treecast.ui.common.EmojiPickerBottomSheet
import app.treecast.ui.createTopic
import app.treecast.ui.deleteRecording
import app.treecast.ui.deleteTopic
import app.treecast.ui.library.LibraryFragment
import app.treecast.ui.moveRecording
import app.treecast.ui.play
import app.treecast.ui.refreshStorageVolumes
import app.treecast.ui.renameRecording
import app.treecast.ui.selectRecording
import app.treecast.ui.toggleCollapse
import app.treecast.ui.togglePlayPause
import app.treecast.ui.updateTopic
import app.treecast.util.emojiToColor
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
            onTopicClick  = { node, isCollapsed ->
                viewModel.toggleCollapse(node.topic.id, isCollapsed)
            },
            onTopicRename = { topic, newName ->
                viewModel.updateTopic(topic.copy(name = newName))
            },
            onTopicDelete = { topic ->
                viewModel.deleteTopic(topic)
            },
            onDetailsClick = { topicId ->
                (parentFragment as? LibraryFragment)?.openTopicDetails(topicId)
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
            onTopicIconChange = { topic ->
                EmojiPickerBottomSheet { emoji ->
                    viewModel.updateTopic(topic.copy(icon = emoji, color = emojiToColor(emoji)))
                }.show(childFragmentManager, "emoji_picker")
            },
            onRename = { id, title -> viewModel.renameRecording(id, title) },
            onMove   = { id, topicId -> viewModel.moveRecording(id, topicId) },
            onDelete = { rec -> viewModel.deleteRecording(rec) },
            onSelect = { id -> viewModel.selectRecording(id) },
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
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.allTopics.collect { topics -> topicItemAdapter.topics = topics }
                }
                launch {
                    viewModel.treeItems.collect { items -> topicItemAdapter.submitList(items) }
                }
                launch {
                    viewModel.nowPlaying.collect { state ->
                        topicItemAdapter.nowPlayingId = state?.recording?.id ?: -1L
                        topicItemAdapter.isPlaying = state?.isPlaying ?: false
                    }
                }
                launch {
                    viewModel.selectedRecordingId.collect { id ->
                        topicItemAdapter.selectedRecordingId = id
                    }
                }
                launch {
                    viewModel.orphanVolumeUuids.collect { uuids ->
                        topicItemAdapter.orphanVolumeUuids = uuids
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStorageVolumes()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}