package com.treecast.app.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.treecast.app.R
import com.treecast.app.data.entities.TopicEntity
import com.treecast.app.data.repository.TreeBuilder
import com.treecast.app.data.repository.TreeNode
import com.treecast.app.util.Icons

class TopicPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    var onTopicSelected: ((Long?) -> Unit)? = null

    private var isExpanded = false
    private var selectedTopicId: Long? = null
    private var selectedTopicName: String = context.getString(R.string.topic_label_unsorted)
    private var selectedTopicIcon: String = Icons.UNSORTED
    private val collapsedNodeIds = mutableSetOf<Long>()
    private var lastRoots: List<TreeNode> = emptyList()

    // Views — IDs from view_topic_picker.xml
    private val headerRow: LinearLayout
    private val tvSelected: TextView
    private val ivChevron: ImageView
    private val recyclerView: RecyclerView
    private val tvUnsorted: TextView
    private val dropdownContainer: LinearLayout

    private val treeAdapter = TopicTreeAdapter(
        onNodeClick  = { node -> handleNodeClick(node) },
        onNodeToggle = { id   -> toggleNode(id) }
    )

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_topic_picker, this, true)

        headerRow         = findViewById(R.id.headerRow)
        tvSelected        = findViewById(R.id.tvSelectedTopic)
        ivChevron         = findViewById(R.id.ivChevron)
        recyclerView      = findViewById(R.id.recyclerTopics)
        tvUnsorted         = findViewById(R.id.tvUnsorted)
        dropdownContainer = findViewById(R.id.dropdownContainer)

        recyclerView.adapter       = treeAdapter
        recyclerView.layoutManager = LinearLayoutManager(context)

        headerRow.setOnClickListener { toggle() }
        tvUnsorted.setOnClickListener {
            selectTopic(null, context.getString(R.string.topic_label_unsorted), Icons.UNSORTED)
        }

        updateHeader()
    }

    // ── Public API ─────────────────────────────────────────────────────

    fun setTopics(topics: List<TopicEntity>) {
        lastRoots = TreeBuilder.build(topics, emptyList())
        refreshAdapterList()
    }

    fun setSelectedTopic(topicId: Long?, topicName: String, topicIcon: String = "📥") {
        selectedTopicId   = topicId
        selectedTopicName = topicName
        selectedTopicIcon = topicIcon
        updateHeader()
    }

    fun collapse() {
        if (isExpanded) toggle()
    }

    // ── Private ────────────────────────────────────────────────────────

    private fun toggle() {
        isExpanded = !isExpanded
        val fromAngle = if (isExpanded) 0f else -180f
        val toAngle   = if (isExpanded) -180f else 0f
        ValueAnimator.ofFloat(fromAngle, toAngle).apply {
            duration = 200
            addUpdateListener { ivChevron.rotation = it.animatedValue as Float }
            start()
        }
        dropdownContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
    }

    private fun updateHeader() {
        tvSelected.text = "$selectedTopicIcon  $selectedTopicName"
    }

    private fun handleNodeClick(node: TreeNode) =
        selectTopic(node.topic.id, node.topic.name, node.topic.icon)

    private fun toggleNode(topicId: Long) {
        if (!collapsedNodeIds.add(topicId)) collapsedNodeIds.remove(topicId)
        refreshAdapterList()
    }

    private fun refreshAdapterList() {
        treeAdapter.submitList(buildAdapterItems(lastRoots, 0))
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

    private fun selectTopic(id: Long?, name: String, icon: String) {
        selectedTopicId   = id
        selectedTopicName = name
        selectedTopicIcon = icon
        updateHeader()
        collapse()
        onTopicSelected?.invoke(id)
    }
}

// ── Data model ───────────────────────────────────────────────────────────────
data class PickerItem(val node: TreeNode, val depth: Int, val isCollapsed: Boolean)

// ── Adapter ──────────────────────────────────────────────────────────────────

class TopicTreeAdapter(
    private val onNodeClick:  (TreeNode) -> Unit,
    private val onNodeToggle: (Long) -> Unit
) : androidx.recyclerview.widget.ListAdapter<PickerItem, TopicTreeAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : androidx.recyclerview.widget.DiffUtil.ItemCallback<PickerItem>() {
            override fun areItemsTheSame(a: PickerItem, b: PickerItem) =
                a.node.topic.id == b.node.topic.id
            override fun areContentsTheSame(a: PickerItem, b: PickerItem) = a == b
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_topic_picker_node, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvIcon:   TextView  = v.findViewById(R.id.tvIcon)
        private val tvName:   TextView  = v.findViewById(R.id.tvName)
        private val ivToggle: ImageView = v.findViewById(R.id.ivToggle)

        fun bind(item: PickerItem) {
            val topic    = item.node.topic
            val density  = itemView.resources.displayMetrics.density
            val indentPx = (item.depth * 18 * density).toInt() + (12 * density).toInt()
            itemView.setPaddingRelative(
                indentPx, itemView.paddingTop, itemView.paddingEnd, itemView.paddingBottom)

            tvIcon.text = topic.icon
            tvName.text = topic.name

            val hasChildren = item.node.children.isNotEmpty()
            ivToggle.visibility = if (hasChildren) View.VISIBLE else View.INVISIBLE
            ivToggle.rotation   = if (item.isCollapsed) -90f else 0f

            ivToggle.setOnClickListener { onNodeToggle(topic.id) }
            itemView.setOnClickListener { onNodeClick(item.node) }
        }
    }
}