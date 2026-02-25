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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.treecast.app.R
import com.treecast.app.data.entities.CategoryEntity
import com.treecast.app.data.repository.TreeBuilder
import com.treecast.app.data.repository.TreeNode

/**
 * Reusable collapsible category-picker widget.
 *
 * Drop this into any layout. Feed it categories via [setCategories].
 * Listen for selections via [onCategorySelected].
 *
 * When collapsed: shows a single row "📁 Category name  ▼"
 * When expanded:  shows the full indented tree, scrollable.
 */
class CategoryPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    // ── Public interface ───────────────────────────────────────────────
    /** Invoked with the selected category ID, or null for Uncategorised */
    var onCategorySelected: ((Long?) -> Unit)? = null

    // ── State ──────────────────────────────────────────────────────────
    private var isExpanded = false
    private var selectedCategoryId: Long? = null
    private var selectedCategoryName: String = "Uncategorised"
    private val collapsedNodeIds = mutableSetOf<Long>()

    // Kept so we can rebuild the adapter list when a node is toggled
    private var lastRoots: List<TreeNode> = emptyList()

    // ── Views ──────────────────────────────────────────────────────────
    private val headerRow: LinearLayout
    private val tvSelected: TextView
    private val ivChevron: ImageView
    private val recyclerView: RecyclerView
    private val tvUncategorised: TextView

    // ── Adapter ────────────────────────────────────────────────────────
    // NOT inner — Kotlin forbids companion objects inside inner classes
    private val treeAdapter = CategoryTreeAdapter(
        onNodeClick  = { node -> handleNodeClick(node) },
        onNodeToggle = { id   -> toggleNode(id) }
    )

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_category_picker, this, true)

        headerRow       = findViewById(R.id.headerRow)
        tvSelected      = findViewById(R.id.tvSelectedCategory)
        ivChevron       = findViewById(R.id.ivChevron)
        recyclerView    = findViewById(R.id.recyclerCategories)
        tvUncategorised = findViewById(R.id.tvUncategorised)

        recyclerView.adapter       = treeAdapter
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.visibility    = View.GONE

        headerRow.setOnClickListener { toggle() }

        tvUncategorised.setOnClickListener {
            selectCategory(null, "Uncategorised")
        }

        updateHeader()
    }

    // ── Public API ─────────────────────────────────────────────────────
    fun setCategories(categories: List<CategoryEntity>) {
        lastRoots = TreeBuilder.build(categories, emptyList())
        refreshAdapterList()
    }

    fun setSelectedCategory(id: Long?, name: String) {
        selectedCategoryId   = id
        selectedCategoryName = name
        updateHeader()
    }

    fun collapse() {
        if (isExpanded) toggle()
    }

    // ── Private helpers ────────────────────────────────────────────────
    private fun toggle() {
        isExpanded = !isExpanded

        val fromAngle = if (isExpanded) 0f else -180f
        val toAngle   = if (isExpanded) -180f else 0f
        ValueAnimator.ofFloat(fromAngle, toAngle).apply {
            duration = 200
            addUpdateListener { ivChevron.rotation = it.animatedValue as Float }
            start()
        }

        recyclerView.visibility = if (isExpanded) View.VISIBLE else View.GONE
    }

    private fun updateHeader() {
        tvSelected.text = "📁  $selectedCategoryName"
    }

    private fun handleNodeClick(node: TreeNode) {
        selectCategory(node.category.id, node.category.name)
    }

    private fun selectCategory(id: Long?, name: String) {
        selectedCategoryId   = id
        selectedCategoryName = name
        updateHeader()
        collapse()
        onCategorySelected?.invoke(id)
    }

    private fun toggleNode(categoryId: Long) {
        if (!collapsedNodeIds.add(categoryId)) {
            collapsedNodeIds.remove(categoryId)
        }
        refreshAdapterList()
    }

    private fun refreshAdapterList() {
        treeAdapter.submitList(buildAdapterItems(lastRoots, 0))
    }

    private fun buildAdapterItems(nodes: List<TreeNode>, depth: Int): List<PickerItem> {
        val result = mutableListOf<PickerItem>()
        for (node in nodes) {
            val isCollapsed = node.category.id in collapsedNodeIds
            result.add(PickerItem(node = node, depth = depth, isCollapsed = isCollapsed))
            if (!isCollapsed) {
                result.addAll(buildAdapterItems(node.children, depth + 1))
            }
        }
        return result
    }
}

// ── Data model ─────────────────────────────────────────────────────────────
data class PickerItem(
    val node: TreeNode,
    val depth: Int,
    val isCollapsed: Boolean
)

// ── Adapter — top-level so companion object compiles without issue ───────────
class CategoryTreeAdapter(
    private val onNodeClick:  (TreeNode) -> Unit,
    private val onNodeToggle: (Long) -> Unit
) : ListAdapter<PickerItem, CategoryTreeAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<PickerItem>() {
            override fun areItemsTheSame(a: PickerItem, b: PickerItem) =
                a.node.category.id == b.node.category.id
            override fun areContentsTheSame(a: PickerItem, b: PickerItem) = a == b
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_picker_node, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvIcon:   TextView  = v.findViewById(R.id.tvIcon)
        private val tvName:   TextView  = v.findViewById(R.id.tvName)
        private val ivToggle: ImageView = v.findViewById(R.id.ivToggle)

        fun bind(item: PickerItem) {
            val cat     = item.node.category
            val density = itemView.resources.displayMetrics.density
            val indentPx = (item.depth * 18 * density).toInt() + (12 * density).toInt()

            itemView.setPaddingRelative(
                indentPx,
                itemView.paddingTop,
                itemView.paddingEnd,
                itemView.paddingBottom
            )

            tvIcon.text = cat.icon
            tvName.text = cat.name

            val hasChildren = item.node.children.isNotEmpty()
            ivToggle.visibility = if (hasChildren) View.VISIBLE else View.INVISIBLE
            ivToggle.rotation   = if (item.isCollapsed) -90f else 0f

            ivToggle.setOnClickListener { onNodeToggle(cat.id) }
            itemView.setOnClickListener { onNodeClick(item.node) }
        }
    }
}
