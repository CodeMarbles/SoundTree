package app.soundtree.ui.common

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.soundtree.R
import app.soundtree.data.entities.TopicEntity
import app.soundtree.data.repository.TreeBuilder
import app.soundtree.data.repository.TreeNode
import app.soundtree.topics.FrequentTopic
import app.soundtree.ui.MainViewModel
import app.soundtree.ui.createTopicReturningId
import app.soundtree.ui.frequentTopics
import app.soundtree.ui.recordTopicPickerUse
import app.soundtree.ui.topics.NewTopicDialog
import app.soundtree.util.themeColor
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
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
            viewModel.frequentTopics.collect { frequentList ->
                if (frequentList.isEmpty()) {
                    section.visibility = View.GONE
                    return@collect
                }
                section.visibility = View.VISIBLE
                renderFrequentTopics(container, frequentList)
            }
        }
    }

    private fun renderFrequentTopics(
        container: LinearLayout,
        frequentList: List<FrequentTopic>
    ) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        frequentList.forEach { frequent ->
            val entryView = inflater.inflate(R.layout.item_frequent_topic, container, false)
            bindFrequentEntry(entryView, frequent)
            container.addView(entryView)
        }
    }

    private fun bindFrequentEntry(view: View, frequent: FrequentTopic) {
        val entryRoot         = view.findViewById<LinearLayout>(R.id.frequentEntryRoot)
        val lineageScrollView = view.findViewById<View>(R.id.lineageScrollView)
        val lineageStrip      = view.findViewById<LinearLayout>(R.id.lineageStrip)
        val tvIcon            = view.findViewById<TextView>(R.id.tvFrequentIcon)
        val tvName            = view.findViewById<TextView>(R.id.tvFrequentName)
        val tvScore           = view.findViewById<TextView>(R.id.tvFrequentScore)
        val ivChevron         = view.findViewById<ImageView>(R.id.ivFrequentChevron)

        // ── Lineage strip ─────────────────────────────────────────────
        if (frequent.lineage.isEmpty()) {
            lineageScrollView.visibility = View.GONE
        } else {
            lineageScrollView.visibility = View.VISIBLE
            buildLineageStrip(lineageStrip, frequent.lineage)
        }

        // ── Topic row content ─────────────────────────────────────────
        tvIcon.text = frequent.topic.icon
        tvName.text = frequent.topic.name

        if (viewModel.devOptions.value && frequent.topic.topicScore > 0.0) {
            tvScore.text      = "%.2f".format(frequent.topic.topicScore)
            tvScore.isVisible = true
        } else {
            tvScore.isVisible = false
        }

        if (frequent.hasChildren) {
            ivChevron.visibility = View.VISIBLE
            ivChevron.rotation   = if (expandedFrequentTopicId == frequent.topic.id) 0f else -90f
        } else {
            ivChevron.visibility = View.INVISIBLE
        }

        // ── Unified touch target ──────────────────────────────────────
        entryRoot.setOnClickListener {
            deliverResult(frequent.topic.id)
        }

        // ── Chevron intercepts independently ──────────────────────────
        ivChevron.setOnClickListener {
            toggleFrequentExpansion(frequent.topic.id, view)
        }
    }

    private fun buildLineageStrip(strip: LinearLayout, lineage: List<TopicEntity>) {
        strip.removeAllViews()
        val ctx     = strip.context
        val density = ctx.resources.displayMetrics.density

        // 2/3 scale relative to the main picker icon row (22dp circles, 16sp text)
        val circleSize  = (15 * density).toInt()   // was 22dp
        val iconSizeSp  = 10f                       // was 16sp (approx 2/3 of 16 = 10.7)
        val arrowMargin = (3 * density).toInt()     // was 4dp

        lineage.forEachIndexed { index, ancestor ->
            val circle = TextView(ctx).apply {
                text     = ancestor.icon
                textSize = iconSizeSp
                gravity  = android.view.Gravity.CENTER
                isClickable = false
                isFocusable = false
                layoutParams = LinearLayout.LayoutParams(circleSize, circleSize).also { lp ->
                    if (index > 0) lp.marginStart = arrowMargin
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(
                        runCatching { Color.parseColor(ancestor.color) }
                            .getOrDefault(Color.parseColor("#6C63FF"))
                    )
                }
            }
            strip.addView(circle)

            // Arrow after each node including the last — acts as "points toward" the topic row
            val arrow = TextView(ctx).apply {
                text     = "›"
                textSize = 10f
                isClickable = false
                isFocusable = false
                setTextColor(ctx.themeColor(R.attr.colorTextSecondary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginStart = arrowMargin }
            }
            strip.addView(arrow)
        }
    }

    private fun toggleFrequentExpansion(topicId: Long, entryView: View) {
        val ivChevron      = entryView.findViewById<ImageView>(R.id.ivFrequentChevron)
        val container      = entryView.parent as? LinearLayout ?: return
        val existingInline = container.findViewWithTag<RecyclerView>("inline_$topicId")

        if (existingInline != null) {
            // Collapse: remove the inline list
            container.removeView(existingInline)
            expandedFrequentTopicId = -1L
            ivChevron.rotation = -90f
        } else {
            // Collapse any previously expanded entry first
            if (expandedFrequentTopicId != -1L) {
                val prev = container.findViewWithTag<RecyclerView>("inline_$expandedFrequentTopicId")
                prev?.let { container.removeView(it) }
                // Reset the chevron on the previously expanded row
                for (i in 0 until container.childCount) {
                    val child = container.getChildAt(i)
                    val tag = child.tag as? String
                    if (tag == "entry_$expandedFrequentTopicId") {
                        child.findViewById<ImageView>(R.id.ivFrequentChevron)?.rotation = -90f
                        break
                    }
                }
            }

            // Expand: insert inline child list after this entry
            expandedFrequentTopicId = topicId
            ivChevron.rotation = 0f
            entryView.tag = "entry_$topicId"

            val children = viewModel.allTopics.value
                .filter { it.parentId == topicId }
                .sortedBy { it.sortOrder }

            if (children.isEmpty()) return

            val childRoots = children.map { child ->
                TreeNode(
                    topic    = child,
                    children = emptyList(),
                    depth    = 0
                )
            }
            val childItems = childRoots.map { PickerItem(it, 1, false) }

            val inlineAdapter = TopicTreeAdapter(
                onNodeClick  = { node -> deliverResult(node.topic.id) },
                onNodeToggle = { /* single-level inline — no further nesting */ }
            ).also {
                it.showScores = viewModel.devOptions.value
                it.submitList(childItems)
            }

            val inlineRv = RecyclerView(requireContext()).apply {
                tag          = "inline_$topicId"
                adapter      = inlineAdapter
                layoutManager = LinearLayoutManager(requireContext())
                isNestedScrollingEnabled = false
            }

            val entryIndex = container.indexOfChild(entryView)
            container.addView(inlineRv, entryIndex + 1)
        }
    }

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