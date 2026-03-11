package com.treecast.app.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.treecast.app.R
import com.treecast.app.data.entities.TopicEntity
import com.treecast.app.data.repository.TreeBuilder
import com.treecast.app.data.repository.TreeNode

/**
 * A [BottomSheetDialogFragment] that presents the full topic tree for selection.
 *
 * Replaces the inline [TopicPickerView] on the Listen and Record tabs. The tree
 * rendering reuses [TopicTreeAdapter] and [PickerItem] from TopicPickerView.kt.
 *
 * Usage:
 * ```
 * TopicPickerBottomSheet(viewModel.allTopics.value, currentTopicId) { topicId ->
 *     // handle selection
 * }.show(childFragmentManager, "topic_picker")
 * ```
 */
class TopicPickerBottomSheet(
    private val topics: List<TopicEntity>,
    private val selectedTopicId: Long?,
    private val onTopicSelected: (Long?) -> Unit
) : BottomSheetDialogFragment() {

    private val collapsedNodeIds = mutableSetOf<Long>()
    private val roots: List<TreeNode> by lazy { TreeBuilder.build(topics, emptyList()) }
    private var recyclerView: RecyclerView? = null

    private val treeAdapter = TopicTreeAdapter(
        onNodeClick = { node ->
            onTopicSelected(node.topic.id)
            dismiss()
        },
        onNodeToggle = { id ->
            if (!collapsedNodeIds.add(id)) collapsedNodeIds.remove(id)
            refreshList()
        }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_topic_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById<RecyclerView>(R.id.recyclerTopics).also {
            it.adapter = treeAdapter
            it.layoutManager = LinearLayoutManager(requireContext())
        }

        view.findViewById<View>(R.id.rowUncategorised).setOnClickListener {
            onTopicSelected(null)
            dismiss()
        }

        refreshList()
    }

    private fun refreshList() {
        treeAdapter.submitList(buildAdapterItems(roots, 0))
    }

    private fun buildAdapterItems(nodes: List<TreeNode>, depth: Int): List<PickerItem> {
        val out = mutableListOf<PickerItem>()
        for (node in nodes) {
            val collapsed = node.topic.id in collapsedNodeIds
            out.add(PickerItem(node, depth, collapsed))
            if (!collapsed) out.addAll(buildAdapterItems(node.children, depth + 1))
        }
        return out
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recyclerView = null
    }
}