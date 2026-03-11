package com.treecast.app.ui.library.manage

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
import com.treecast.app.databinding.FragmentTopicsManageBinding
import com.treecast.app.data.repository.TreeItem
import com.treecast.app.ui.MainViewModel
import com.treecast.app.ui.common.TopicPickerBottomSheet
import com.treecast.app.ui.library.LibraryFragment
import com.treecast.app.ui.topics.NewTopicDialog
import kotlinx.coroutines.launch

/**
 * TOPICS tab — topic tree management surface.
 *
 * Shows only the topic tree (no recording leaves). Each row has a DETAILS
 * button. Single tap = select (enables + SUBTOPIC FAB). Tapping the selected
 * row again = deselect.
 *
 * Dual FABs in the bottom-right corner:
 *   [+ SUBTOPIC] — disabled until a topic is selected; creates under selected parent
 *   [+ TOPIC]    — always active; creates a root-level topic
 */
class TopicsManageFragment : Fragment() {

    private var _binding: FragmentTopicsManageBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: TopicsManageAdapter

    companion object {
        private const val REQUEST_REPARENT = "TopicsManage_reparent"
    }

    // Tracks which topic ID the pending reparent picker was opened for
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

        adapter = TopicsManageAdapter(
            onCollapseToggle = { topicId, isCollapsed ->
                viewModel.toggleCollapse(topicId, isCollapsed)
            },
            onTopicSelect = { topicId ->
                // Enable/disable + SUBTOPIC FAB based on whether anything is selected
                val hasSelection = topicId != null
                binding.fabAddSubtopic.isEnabled = hasSelection
                binding.fabAddSubtopic.alpha = if (hasSelection) 1f else 0.4f
            },
            onDetailsClick = { topicId ->
                (requireParentFragment() as? LibraryFragment)?.openTopicDetails(topicId)
            },
            onMoveClick = { topicId ->
                pendingReparentTopicId = topicId
                val excluded = viewModel.getTopicWithDescendantIds(topicId)
                TopicPickerBottomSheet.newInstance(
                    selectedTopicId = null,
                    requestKey      = REQUEST_REPARENT,
                    excludedIds     = excluded
                ).show(childFragmentManager, "reparent_picker")
            }
        )

        binding.recyclerTopicsManage.apply {
            this.adapter = this@TopicsManageFragment.adapter
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(false)
        }

        // ── FAB: + TOPIC ──────────────────────────────────────────────
        binding.fabAddTopic.setOnClickListener {
            NewTopicDialog(parentId = null) { name, icon, color ->
                viewModel.createTopic(name, null, icon, color)
            }.show(childFragmentManager, "new_topic")
        }

        // ── FAB: + SUBTOPIC (only active when a topic is selected) ────
        binding.fabAddSubtopic.setOnClickListener {
            val selectedId = adapter.selectedTopicId.takeIf { it >= 0 } ?: return@setOnClickListener
            NewTopicDialog(parentId = selectedId) { name, icon, color ->
                viewModel.createTopic(name, selectedId, icon, color)
            }.show(childFragmentManager, "new_subtopic")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.treeItems.collect { items ->
                    // Only show Node items — no recording leaves in this view
                    val nodes = items.filterIsInstance<TreeItem.Node>()
                    adapter.submitList(nodes)
                    binding.tvEmptyTopics.visibility = if (nodes.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}