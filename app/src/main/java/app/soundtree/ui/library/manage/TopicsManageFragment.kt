package app.soundtree.ui.library.manage

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import app.soundtree.R
import app.soundtree.databinding.FragmentTopicsManageBinding
import app.soundtree.data.repository.TreeItem
import app.soundtree.ui.MainViewModel
import app.soundtree.ui.common.EmojiPickerBottomSheet
import app.soundtree.ui.common.TopicPickerBottomSheet
import app.soundtree.ui.createTopic
import app.soundtree.ui.deleteTopic
import app.soundtree.ui.getTopicWithDescendantIds
import app.soundtree.ui.library.LibraryFragment
import app.soundtree.ui.reparentTopic
import app.soundtree.ui.toggleCollapse
import app.soundtree.ui.topics.NewTopicDialog
import app.soundtree.ui.updateTopic
import app.soundtree.util.emojiToColor
import kotlinx.coroutines.launch

/**
 * TOPICS tab — topic tree management surface.
 *
 * Shows a static Unsorted row (via [UnsortedRowAdapter]) followed by the full
 * topic tree (via [TopicsManageAdapter]), composed with [ConcatAdapter].
 * Single tap on a topic row navigates to the Details tab.
 * Long-press opens a PopupMenu: New Subtopic / Move / Rename / Icon / Delete.
 *
 * One FAB: [+ TOPIC] — always active; creates a root-level topic.
 */
class TopicsManageFragment : Fragment() {

    private var _binding: FragmentTopicsManageBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var unsortedRowAdapter: UnsortedRowAdapter
    private lateinit var topicsAdapter: TopicsManageAdapter

    companion object {
        private const val REQUEST_REPARENT = "TopicsManage_reparent"
    }

    private var pendingReparentTopicId: Long = -1L

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTopicsManageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── Reparent picker result ────────────────────────────────────
        childFragmentManager.setFragmentResultListener(
            REQUEST_REPARENT, viewLifecycleOwner
        ) { _, bundle ->
            val newParentId = TopicPickerBottomSheet.topicIdFromBundle(bundle)
            val topicId = pendingReparentTopicId.takeIf { it >= 0 } ?: return@setFragmentResultListener
            viewModel.reparentTopic(topicId, newParentId)
            pendingReparentTopicId = -1L
        }

        unsortedRowAdapter = UnsortedRowAdapter(
            onUnsortedClick = {
                (requireParentFragment() as? LibraryFragment)?.navigateToUnsorted()
            }
        )

        topicsAdapter = TopicsManageAdapter(
            onCollapseToggle = { topicId, isCollapsed ->
                viewModel.toggleCollapse(topicId, isCollapsed)
            },
            onTopicClick = { topicId ->
                (requireParentFragment() as? LibraryFragment)?.openTopicDetails(topicId)
            },
            onNewSubtopic = { parentId ->
                NewTopicDialog(
                    mode            = NewTopicDialog.Mode.CREATE,
                    initialParentId = parentId,
                ) { name, newParentId, icon, color ->
                    viewModel.createTopic(name, newParentId, icon, color)
                }.show(childFragmentManager, "new_subtopic")
            },

            onMoveClick = { topicId ->
                pendingReparentTopicId = topicId
                val excluded = viewModel.getTopicWithDescendantIds(topicId)
                TopicPickerBottomSheet.newInstance(
                    selectedTopicId = null,
                    requestKey      = REQUEST_REPARENT,
                    excludedIds     = excluded,
                    mode            = TopicPickerBottomSheet.Mode.REPARENT
                ).show(childFragmentManager, "reparent_picker")
            },
            onRenameClick = { topicId ->
                showRenameDialog(topicId)
            },
            onIconClick = { topicId ->
                EmojiPickerBottomSheet { emoji ->
                    val topic = viewModel.allTopics.value.firstOrNull { it.id == topicId }
                        ?: return@EmojiPickerBottomSheet
                    viewModel.updateTopic(topic.copy(icon = emoji, color = emojiToColor(emoji)))
                }.show(childFragmentManager, "emoji_picker_manage")
            },
            onDeleteClick = { topicId ->
                val topic = viewModel.allTopics.value.firstOrNull { it.id == topicId }
                    ?: return@TopicsManageAdapter
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.topic_dialog_delete_title, topic.name))
                    .setMessage(R.string.topic_dialog_delete_message)
                    .setPositiveButton(R.string.common_btn_delete) { _, _ -> viewModel.deleteTopic(topic) }
                    .setNegativeButton(R.string.common_btn_cancel, null)
                    .show()
            }
        )

        binding.recyclerTopicsManage.apply {
            adapter = ConcatAdapter(unsortedRowAdapter, topicsAdapter)
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(false)
        }

        // ── FAB: + TOPIC ──────────────────────────────────────────────
        binding.fabAddTopic.setOnClickListener {
            NewTopicDialog(mode = NewTopicDialog.Mode.CREATE) { name, parentId, icon, color ->
                viewModel.createTopic(name, parentId, icon, color)
            }.show(childFragmentManager, "new_topic")
        }



        // ── Observe tree items + unsorted count ───────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.treeItems.collect { items ->
                        val nodes = items.filterIsInstance<TreeItem.Node>()
                        topicsAdapter.submitList(nodes)
                        binding.tvEmptyTopics.visibility =
                            if (nodes.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.allRecordings.collect { recordings ->
                        unsortedRowAdapter.unsortedCount = recordings.count { it.topicId == null }
                    }
                }
            }
        }
    }

    // ── Rename dialog ──────────────────────────────────────────────────

    private fun showRenameDialog(topicId: Long) {
        val topic = viewModel.allTopics.value.firstOrNull { it.id == topicId } ?: return
        NewTopicDialog(
            mode        = NewTopicDialog.Mode.RENAME,
            initialName = topic.name,
            initialIcon = topic.icon,
        ) { name, _, icon, color ->
            viewModel.updateTopic(topic.copy(name = name, icon = icon, color = color))
        }.show(childFragmentManager, "rename_topic")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}