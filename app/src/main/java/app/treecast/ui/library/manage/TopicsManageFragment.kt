package app.treecast.ui.library.manage

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
import app.treecast.R
import app.treecast.databinding.FragmentTopicsManageBinding
import app.treecast.data.repository.TreeItem
import app.treecast.ui.MainViewModel
import app.treecast.ui.common.EmojiPickerBottomSheet
import app.treecast.ui.common.TopicPickerBottomSheet
import app.treecast.ui.createTopic
import app.treecast.ui.deleteTopic
import app.treecast.ui.getTopicWithDescendantIds
import app.treecast.ui.library.LibraryFragment
import app.treecast.ui.reparentTopic
import app.treecast.ui.toggleCollapse
import app.treecast.ui.topics.NewTopicDialog
import app.treecast.ui.updateTopic
import app.treecast.util.emojiToColor
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
                NewTopicDialog(parentId = parentId) { name, icon, color ->
                    viewModel.createTopic(name, parentId, icon, color)
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
            onRenameClick = { topicId, currentName ->
                showRenameDialog(topicId, currentName)
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
            NewTopicDialog(parentId = null) { name, icon, color ->
                viewModel.createTopic(name, null, icon, color)
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

    private fun showRenameDialog(topicId: Long, currentName: String) {
        val editText = EditText(requireContext()).apply {
            setText(currentName)
            selectAll()
            setSingleLine()
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(requireContext()).apply {
            setPadding(padding, 0, padding, 0)
            addView(editText)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.topic_dialog_rename_title)
            .setView(container)
            .setPositiveButton(R.string.common_btn_ok) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val topic = viewModel.allTopics.value.firstOrNull { it.id == topicId }
                        ?: return@setPositiveButton
                    viewModel.updateTopic(topic.copy(name = newName))
                }
            }
            .setNegativeButton(R.string.common_btn_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}