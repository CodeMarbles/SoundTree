package app.treecast.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import app.treecast.R
import app.treecast.data.repository.TreeBuilder
import app.treecast.data.repository.TreeNode
import app.treecast.ui.MainViewModel

/**
 * A [BottomSheetDialogFragment] that presents the full topic tree for selection.
 *
 * ── Modes ─────────────────────────────────────────────────────────────────────
 *
 * [Mode.PICK] (default) — used when moving a recording to a topic.
 *   Null destination row reads: 📥  Unsorted
 *   Sheet title: "Select Topic"
 *
 * [Mode.REPARENT] — used when moving a topic to a new parent.
 *   Null destination row reads: 🌳  Top level
 *   Sheet title: "Move to…"
 *   The semantic distinction matters: null in PICK means "no topic assigned";
 *   null in REPARENT means "make this a root-level topic".
 *
 * ── Exclusions ────────────────────────────────────────────────────────────────
 *
 * Supports an optional [excludedIds] set — any topic whose ID is in that set,
 * and its entire subtree, will be hidden from the list. Used in REPARENT mode
 * to prevent selecting the topic being moved or any of its descendants.
 */
class TopicPickerBottomSheet : BottomSheetDialogFragment() {

    enum class Mode { PICK, REPARENT }

    companion object {
        const val REQUEST_KEY      = "TopicPickerBottomSheet"
        const val KEY_TOPIC_ID     = "topicId"
        const val KEY_REQUEST      = "requestKey"
        const val KEY_EXCLUDED_IDS = "excludedIds"
        const val KEY_MODE         = "mode"
        const val TOPIC_ID_NONE    = -1L

        fun newInstance(
            selectedTopicId: Long?,
            requestKey: String  = REQUEST_KEY,
            excludedIds: Set<Long> = emptySet(),
            mode: Mode          = Mode.PICK
        ): TopicPickerBottomSheet =
            TopicPickerBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong(KEY_TOPIC_ID, selectedTopicId ?: TOPIC_ID_NONE)
                    putString(KEY_REQUEST, requestKey)
                    putLongArray(KEY_EXCLUDED_IDS, excludedIds.toLongArray())
                    putString(KEY_MODE, mode.name)
                }
            }

        fun topicIdFromBundle(bundle: Bundle): Long? =
            bundle.getLong(KEY_TOPIC_ID, TOPIC_ID_NONE).takeIf { it != TOPIC_ID_NONE }
    }

    private val requestKey: String
        get() = arguments?.getString(KEY_REQUEST) ?: REQUEST_KEY

    private val excludedIds: Set<Long>
        get() = arguments?.getLongArray(KEY_EXCLUDED_IDS)?.toSet() ?: emptySet()

    private val mode: Mode
        get() = arguments?.getString(KEY_MODE)
            ?.let { runCatching { Mode.valueOf(it) }.getOrNull() }
            ?: Mode.PICK

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

        view.findViewById<View>(R.id.rowUnsorted).setOnClickListener {
            deliverResult(null)
        }

        // ── Mode-specific presentation ────────────────────────────────
        if (mode == Mode.REPARENT) {
            view.findViewById<TextView>(R.id.tvPickerTitle).setText(R.string.topic_picker_title_move)
            view.findViewById<TextView>(R.id.tvNullRowIcon).text = "🌳"
            view.findViewById<TextView>(R.id.tvNullRowLabel).setText(R.string.topic_picker_label_top_level)
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
        val topics   = viewModel.allTopics.value
        val roots    = TreeBuilder.build(topics, emptyList())
        val excluded = excludedIds
        treeAdapter.submitList(buildAdapterItems(roots, 0, excluded))
    }

    /**
     * Builds the flat list for the adapter, skipping any node (and its entire
     * subtree) whose ID appears in [excluded].
     */
    private fun buildAdapterItems(
        nodes: List<TreeNode>,
        depth: Int,
        excluded: Set<Long>
    ): List<PickerItem> {
        val out = mutableListOf<PickerItem>()
        for (node in nodes) {
            // Skip this node and its entire subtree if excluded
            if (node.topic.id in excluded) continue

            val collapsed = node.topic.id in collapsedNodeIds
            out.add(PickerItem(node, depth, collapsed))
            if (!collapsed) out.addAll(buildAdapterItems(node.children, depth + 1, excluded))
        }
        return out
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recyclerView = null
    }
}