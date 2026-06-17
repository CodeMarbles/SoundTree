package app.soundtree.ui.common

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.soundtree.R
import app.soundtree.data.repository.TreeBuilder
import app.soundtree.data.repository.TreeNode
import app.soundtree.topics.FrequentTopic
import app.soundtree.ui.MainViewModel
import app.soundtree.ui.createTopicReturningId
import app.soundtree.ui.frequentTopics
import app.soundtree.ui.frequentTopicsEnabled
import app.soundtree.ui.frequentTopicsLimit
import app.soundtree.ui.frequentTopicsShowLabels
import app.soundtree.ui.frequentTopicsShowLineage
import app.soundtree.ui.recordTopicPickerUse
import app.soundtree.ui.topics.NewTopicDialog
import app.soundtree.util.themeColor
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

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

    /**
     * PICK        — recording assignment. Null row = "📥 Unsorted". "+" visible.
     * REPARENT    — move an existing topic. Null row = "🌳 Top level". "+" hidden.
     * PICK_PARENT — choose a parent for a *new* topic (from NewTopicDialog).
     *               Null row = "🌳 Top level". "+" hidden.
     *               Same presentation as REPARENT but distinct semantics and title.
     */
    enum class Mode { PICK, REPARENT, PICK_PARENT }

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

    private val collapsedNodeIds = mutableSetOf<Long>()
    private var recyclerView: RecyclerView? = null

    // Frequent-section expand state: which frequent topic (by id) is showing
    // its inline children. Only one can be expanded at a time.
    private var expandedFrequentTopicId: Long = -1L

    private val treeAdapter = TopicTreeAdapter(
        onNodeClick = { node -> deliverResult(node.topic.id) },
        onNodeToggle = { id ->
            if (!collapsedNodeIds.add(id)) collapsedNodeIds.remove(id)
            refreshList()
        }
    )

    // ── Lifecycle ─────────────────────────────────────────────────────

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

        treeAdapter.showScores = viewModel.devOptions.value

        view.findViewById<View>(R.id.rowUnsorted).setOnClickListener {
            deliverResult(null)
        }

        view.findViewById<ImageButton>(R.id.btnAddTopic).apply {
            // Hidden in REPARENT mode — the user is reorganising an existing
            // topic, not assigning a recording; adding a topic here would be
            // confusing. Show it only in PICK mode (recording assignment).
            visibility = if (mode == Mode.PICK) View.VISIBLE else View.GONE
            setOnClickListener {
                NewTopicDialog(mode = NewTopicDialog.Mode.CREATE) { name, parentId, icon, color ->
                    lifecycleScope.launch {
                        val newId = viewModel.createTopicReturningId(name, parentId, icon, color)
                        deliverResult(newId)
                    }
                }.show(childFragmentManager, "picker_add_topic")
            }
        }

        // ── Mode-specific presentation ────────────────────────────────
        when (mode) {
            Mode.PICK -> {
                // Title and null-row already correct from the layout defaults.
            }
            Mode.REPARENT -> {
                view.findViewById<TextView>(R.id.tvPickerTitle).setText(R.string.topic_picker_title_move)
                view.findViewById<TextView>(R.id.tvNullRowIcon).text = "🌳"
                view.findViewById<TextView>(R.id.tvNullRowLabel).setText(R.string.topic_picker_label_top_level)
            }
            Mode.PICK_PARENT -> {
                view.findViewById<TextView>(R.id.tvPickerTitle).setText(R.string.topic_picker_title_pick_parent)
                view.findViewById<TextView>(R.id.tvNullRowIcon).text = "🌳"
                view.findViewById<TextView>(R.id.tvNullRowLabel).setText(R.string.topic_picker_label_top_level)
            }
        }

        // Frequent topics section — only in PICK mode
        if (mode == Mode.PICK) {
            setupFrequentTopicsSection(view)
        }

        refreshList()
    }

    // ── Frequent topics ───────────────────────────────────────────────

    private fun setupFrequentTopicsSection(view: View) {
        val section   = view.findViewById<LinearLayout>(R.id.sectionFrequentTopics)
        val container = view.findViewById<LinearLayout>(R.id.containerFrequentTopics)

        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                viewModel.frequentTopics,
                viewModel.frequentTopicsEnabled,
                viewModel.frequentTopicsLimit,
                viewModel.frequentTopicsShowLineage,
                viewModel.frequentTopicsShowLabels,
            ) { list, enabled, limit, showLineage, showLabels ->
                // Bundle everything the render step needs into one emission.
                // If the feature is disabled we pass an empty list so the
                // section collapses without needing a separate branch below.
                if (enabled) list.take(limit) to Pair(showLineage, showLabels)
                else emptyList<FrequentTopic>() to Pair(showLineage, showLabels)
            }.collect { (frequentList, display) ->
                val (showLineage, showLabels) = display
                if (frequentList.isEmpty()) {
                    section.visibility = View.GONE
                    return@collect
                }
                section.visibility = View.VISIBLE
                renderFrequentTopics(container, frequentList, showLineage, showLabels)
            }
        }
    }

    private fun renderFrequentTopics(
        container: LinearLayout,
        frequentList: List<FrequentTopic>,
        showLineage: Boolean,
        showLabels: Boolean,
    ) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        frequentList.forEach { frequent ->
            val entryView = inflater.inflate(R.layout.item_frequent_topic, container, false)
            bindFrequentEntry(entryView, frequent, showLineage, showLabels)
            container.addView(entryView)
        }
    }

    private fun bindFrequentEntry(
        view: View,
        frequent: FrequentTopic,
        showLineage: Boolean,
        showLabels: Boolean,
    ) {
        val entryRoot = view.findViewById<LinearLayout>(R.id.frequentEntryRoot)
        val flow      = view.findViewById<FlowLayout>(R.id.flowLineageAndTopic)
        val tvScore   = view.findViewById<TextView>(R.id.tvFrequentScore)
        val ivChevron = view.findViewById<ImageView>(R.id.ivFrequentChevron)

        // ── Build the unified flow content ────────────────────────────
        buildFrequentEntryFlow(flow, frequent, showLineage, showLabels)

        // ── Score (dev only) ──────────────────────────────────────────
        if (viewModel.devOptions.value && frequent.topic.topicScore > 0.0) {
            tvScore.text      = "%.2f".format(frequent.topic.topicScore)
            tvScore.isVisible = true
        } else {
            tvScore.isVisible = false
        }

        // ── Chevron ───────────────────────────────────────────────────
        if (frequent.hasChildren) {
            ivChevron.visibility = View.VISIBLE
            ivChevron.rotation   = if (expandedFrequentTopicId == frequent.topic.id) 0f else -90f
        } else {
            ivChevron.visibility = View.INVISIBLE
        }

        // ── Touch targets ─────────────────────────────────────────────
        entryRoot.setOnClickListener {
            deliverResult(frequent.topic.id)
        }
        ivChevron.setOnClickListener {
            toggleFrequentExpansion(frequent.topic.id, view)
        }
    }

    /**
     * Populates [flow] with the inline lineage + topic node sequence:
     *
     *   [🎵] [›] [🎸] [›] [🎤 Vocal Takes]        // showLineage=true,  showLabels=false
     *   [🎵 Music] [›] [🎸 Guitar] [›] [🎤 Vocal Takes]  // showLineage=true,  showLabels=true
     *   [🎤 Vocal Takes]                            // showLineage=false
     *
     * Each ancestor and the topic itself is a small compound TextView (emoji +
     * optional name). Arrows are lightweight TextViews between each node.
     * The FlowLayout wraps naturally when nodes overflow the available width.
     */
    private fun buildFrequentEntryFlow(
        flow: FlowLayout,
        frequent: FrequentTopic,
        showLineage: Boolean,
        showLabels: Boolean,
    ) {
        flow.removeAllViews()
        val ctx     = flow.context
        val density = ctx.resources.displayMetrics.density

        val arrowMarginPx = (3 * density).toInt()
        val nodeMarginPx  = (2 * density).toInt()

        fun arrow(): TextView = TextView(ctx).apply {
            text     = "›"
            textSize = 11f
            isClickable = false
            isFocusable = false
            setTextColor(ctx.themeColor(R.attr.colorTextSecondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also {
                it.marginStart = arrowMarginPx
                it.marginEnd   = arrowMarginPx
            }
        }

        if (showLineage && frequent.lineage.isNotEmpty()) {
            frequent.lineage.forEachIndexed { index, ancestor ->
                if (index > 0) flow.addView(arrow())
                flow.addView(buildLineageNode(ctx, ancestor.icon, ancestor.name, showLabels, density, nodeMarginPx, isAncestor = true))
            }
            flow.addView(arrow())
        }

        // Topic node — always shown, always with name (it's the destination)
        flow.addView(buildLineageNode(ctx, frequent.topic.icon, frequent.topic.name, showName = true, density, nodeMarginPx, isAncestor = false))
    }

    /**
     * Builds a single flow node for one topic (ancestor or the topic itself).
     *
     * Ancestor nodes with [showName]=false show only the emoji in a small
     * circle. Ancestor nodes with [showName]=true and the topic node always
     * show "[emoji] [name]" as a compact horizontal pair, the name in 12sp
     * secondary text.
     *
     * We use a single TextView for the emoji-only case (simpler, avoids an
     * extra measure pass) and a horizontal LinearLayout for the labelled case.
     */
    private fun buildLineageNode(
        ctx: android.content.Context,
        icon: String,
        name: String,
        showName: Boolean,
        density: Float,
        marginPx: Int,
        isAncestor: Boolean,
    ): View {
        return if (!showName && isAncestor) {
            // Emoji-only circle — 2/3 scale for ancestors
            // val circleSize = (15 * density).toInt()
            // Emoji-only circle
            val circleSize = (22 * density).toInt()
            TextView(ctx).apply {
                text     = icon
                textSize = 10f
                gravity  = android.view.Gravity.CENTER
                isClickable = false
                isFocusable = false
                layoutParams = LinearLayout.LayoutParams(circleSize, circleSize).also { lp ->
                    lp.marginStart = marginPx
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(
                        runCatching { "#6C63FF".toColorInt() }
                            .getOrDefault("#6C63FF".toColorInt())
                    )
                }
            }
        } else {
            // [emoji] [name] pair
            val iconSizeSp = if (isAncestor) 10f else 14f
            val nameSizeSp = if (isAncestor) 11f else 13f

            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = android.view.Gravity.CENTER_VERTICAL
                isClickable = false
                isFocusable = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { it.marginStart = marginPx }

                addView(TextView(ctx).apply {
                    text     = icon
                    textSize = iconSizeSp
                    isClickable = false
                    isFocusable = false
                })
                addView(TextView(ctx).apply {
                    text      = name
                    textSize  = nameSizeSp
                    isClickable = false
                    isFocusable = false
                    maxLines  = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(ctx.themeColor(
                        if (isAncestor) R.attr.colorTextSecondary else R.attr.colorTextPrimary
                    ))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).also { it.marginStart = (3 * ctx.resources.displayMetrics.density).toInt() }
                })
            }
        }
    }

    private fun toggleFrequentExpansion(topicId: Long, entryView: View) {
        val ivChevron      = entryView.findViewById<ImageView>(R.id.ivFrequentChevron)
        val container      = entryView.parent as? LinearLayout ?: return
        val existingInline = container.findViewWithTag<RecyclerView>("inline_$topicId")

        if (existingInline != null) {
            // Collapse: remove the inline tree
            container.removeView(existingInline)
            expandedFrequentTopicId = -1L
            ivChevron.rotation = -90f
            return
        }

        // Collapse any previously expanded entry first
        if (expandedFrequentTopicId != -1L) {
            val prev = container.findViewWithTag<RecyclerView>("inline_$expandedFrequentTopicId")
            prev?.let { container.removeView(it) }
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (child.tag == "entry_$expandedFrequentTopicId") {
                    child.findViewById<ImageView>(R.id.ivFrequentChevron)?.rotation = -90f
                    break
                }
            }
        }

        expandedFrequentTopicId = topicId
        ivChevron.rotation = 0f
        entryView.tag = "entry_$topicId"

        // Build the full subtree rooted at this frequent topic.
        // TreeBuilder.build() uses allTopics and produces a proper recursive
        // TreeNode tree; we then find the root node for topicId and render its
        // children using the same buildAdapterItems() that drives the main tree,
        // so collapse state, depth indentation, and further expand/collapse all
        // work identically.
        val allTopics = viewModel.allTopics.value
        val fullTree  = TreeBuilder.build(allTopics, emptyList())

        fun findNode(nodes: List<TreeNode>, id: Long): TreeNode? {
            for (node in nodes) {
                if (node.topic.id == id) return node
                findNode(node.children, id)?.let { return it }
            }
            return null
        }

        val rootNode = findNode(fullTree, topicId) ?: return
        if (rootNode.children.isEmpty()) return

        // Use depth=1 so the inline rows are indented one level relative to the
        // frequent entry header (matching the visual expectation that these are
        // children). buildAdapterItems() adds further depth for each nesting level.
        val inlineItems = buildAdapterItems(rootNode.children, depth = 1, excluded = excludedIds)

        val inlineAdapter = TopicTreeAdapter(
            onNodeClick  = { node -> deliverResult(node.topic.id) },
            onNodeToggle = { id ->
                // Toggle in the shared collapsedNodeIds set and refresh this
                // adapter directly — no need to touch the main treeAdapter.
                if (!collapsedNodeIds.add(id)) collapsedNodeIds.remove(id)
                val refreshed = buildAdapterItems(rootNode.children, depth = 1, excluded = excludedIds)
                (inlineRvRef?.adapter as? TopicTreeAdapter)?.submitList(refreshed)
            }
        ).also {
            it.showScores = viewModel.devOptions.value
            it.submitList(inlineItems)
        }

        // Store a reference so the toggle lambda above can refresh the adapter
        // without capturing a `lateinit` or nullable.
        val inlineRv = RecyclerView(requireContext()).apply {
            tag                      = "inline_$topicId"
            adapter                  = inlineAdapter
            layoutManager            = LinearLayoutManager(requireContext())
            isNestedScrollingEnabled = false
        }
        // Give the adapter closure access to the RV via a local alias.
        // (The lambda above uses `inlineRvRef` — set it before addView so any
        // immediate layout pass can find it.)
        inlineRvRef = inlineRv

        val entryIndex = container.indexOfChild(entryView)
        container.addView(inlineRv, entryIndex + 1)
    }

    // Holds a reference to the currently visible inline RecyclerView so the
    // onNodeToggle lambda can refresh it without a second view-tree search.
    // Reset to null whenever the inline list is removed.
    private var inlineRvRef: RecyclerView? = null

    // ── deliverResult ─────────────────────────────────────────────────

    private fun deliverResult(topicId: Long?) {
        // Record the use for scoring (Mode.PICK only, non-null topic only)
        if (mode == Mode.PICK && topicId != null) {
            viewModel.recordTopicPickerUse(topicId)
        }
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