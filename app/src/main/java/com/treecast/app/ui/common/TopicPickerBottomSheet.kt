package com.treecast.app.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.treecast.app.R
import com.treecast.app.data.repository.TreeBuilder
import com.treecast.app.data.repository.TreeNode
import com.treecast.app.ui.MainViewModel

/**
 * A [BottomSheetDialogFragment] that presents the full topic tree for selection.
 *
 * Uses the Fragment Result API so it survives configuration changes (theme
 * switches, rotation) without losing its callback. The caller registers a
 * [androidx.fragment.app.FragmentResultListener] *before* showing the sheet,
 * and the sheet fires [setFragmentResult] on selection.
 *
 * Usage (from a Fragment):
 * ```
 * // Register once in onViewCreated:
 * childFragmentManager.setFragmentResultListener(
 *     TopicPickerBottomSheet.REQUEST_KEY, viewLifecycleOwner
 * ) { _, bundle ->
 *     val topicId = TopicPickerBottomSheet.topicIdFromBundle(bundle)
 *     // handle selection
 * }
 *
 * // Show when needed:
 * TopicPickerBottomSheet.newInstance(currentTopicId)
 *     .show(childFragmentManager, "topic_picker")
 * ```
 *
 * Usage (from an Activity):
 * ```
 * // Register once in onCreate/onStart:
 * supportFragmentManager.setFragmentResultListener(
 *     TopicPickerBottomSheet.REQUEST_KEY, this
 * ) { _, bundle ->
 *     val topicId = TopicPickerBottomSheet.topicIdFromBundle(bundle)
 * }
 *
 * // Show with a unique tag per call-site to avoid FragmentManager collisions:
 * TopicPickerBottomSheet.newInstance(currentTopicId)
 *     .show(supportFragmentManager, "mini_rec_topic_picker")
 * ```
 */
class TopicPickerBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val REQUEST_KEY   = "TopicPickerBottomSheet"   // default / Record tab
        const val KEY_TOPIC_ID  = "topicId"
        const val KEY_REQUEST   = "requestKey"
        const val TOPIC_ID_NONE = -1L

        fun newInstance(selectedTopicId: Long?, requestKey: String = REQUEST_KEY): TopicPickerBottomSheet =
            TopicPickerBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong(KEY_TOPIC_ID, selectedTopicId ?: TOPIC_ID_NONE)
                    putString(KEY_REQUEST, requestKey)
                }
            }

        fun topicIdFromBundle(bundle: Bundle): Long? =
            bundle.getLong(KEY_TOPIC_ID, TOPIC_ID_NONE).takeIf { it != TOPIC_ID_NONE }
    }

    private val requestKey: String
        get() = arguments?.getString(KEY_REQUEST) ?: REQUEST_KEY

    private val viewModel: MainViewModel by activityViewModels()

    private val selectedTopicId: Long?
        get() = arguments?.getLong(KEY_TOPIC_ID, TOPIC_ID_NONE)
            ?.takeIf { it != TOPIC_ID_NONE }

    private val collapsedNodeIds = mutableSetOf<Long>()
    private var recyclerView: RecyclerView? = null

    private val treeAdapter = TopicTreeAdapter(
        onNodeClick = { node -> deliverResult(node.topic.id) },
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
            deliverResult(null)
        }

        refreshList()
    }

    private fun deliverResult(topicId: Long?) {
        parentFragmentManager.setFragmentResult(requestKey, Bundle().apply {
            putLong(KEY_TOPIC_ID, topicId ?: TOPIC_ID_NONE)
        })
        dismiss()
    }

    private fun refreshList() {
        val topics = viewModel.allTopics.value
        val roots  = TreeBuilder.build(topics, emptyList())
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