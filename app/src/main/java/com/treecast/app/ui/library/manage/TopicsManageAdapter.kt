package com.treecast.app.ui.library.manage

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.treecast.app.R
import com.treecast.app.data.repository.TreeItem
import com.treecast.app.util.themeColor

/**
 * Adapter for the Topics Management tab.
 *
 * Shows only [TreeItem.Node] items — no recording leaves. Each row has:
 *  - Color bar (topic accent color)
 *  - Icon emoji
 *  - Topic name
 *  - Collapse/expand chevron (if node has children)
 *  - DETAILS button → navigates to the Details tab for that topic
 *
 * Single tap on a row body = select (highlights row, enables + SUBTOPIC FAB).
 * Tapping a selected row again = deselect.
 * Chevron tap = toggle collapse/expand.
 */
class TopicsManageAdapter(
    private val onCollapseToggle: (topicId: Long, currentlyCollapsed: Boolean) -> Unit,
    private val onTopicSelect:    (topicId: Long?) -> Unit,
    private val onDetailsClick:   (topicId: Long) -> Unit,
    private val onMoveClick:      (topicId: Long) -> Unit,
) : ListAdapter<TreeItem.Node, TopicsManageAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<TreeItem.Node>() {
            override fun areItemsTheSame(a: TreeItem.Node, b: TreeItem.Node) =
                a.treeNode.topic.id == b.treeNode.topic.id
            override fun areContentsTheSame(a: TreeItem.Node, b: TreeItem.Node) = a == b
        }

        private const val DEPTH_INDENT_DP = 20f
        private const val BASE_PADDING_DP = 8f
    }

    var selectedTopicId: Long = -1L
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_topic_manage_row, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val colorBar:   View           = v.findViewById(R.id.colorBar)
        private val tvIcon:     TextView       = v.findViewById(R.id.tvIcon)
        private val tvName:     TextView       = v.findViewById(R.id.tvName)
        private val tvCount:    TextView       = v.findViewById(R.id.tvCount)
        private val ivChevron:  ImageView      = v.findViewById(R.id.ivChevron)
        private val btnMove:    android.widget.ImageButton = v.findViewById(R.id.btnMove)
        private val btnDetails: MaterialButton = v.findViewById(R.id.btnDetails)

        fun bind(item: TreeItem.Node) {
            val topic = item.treeNode.topic
            val density = itemView.resources.displayMetrics.density

            // ── Indentation (20dp per level, matching picker style) ───
            val indentPx = ((BASE_PADDING_DP + item.depth * DEPTH_INDENT_DP) * density).toInt()
            itemView.setPaddingRelative(indentPx, 0, itemView.paddingEnd, 0)

            // ── Color bar ─────────────────────────────────────────────
            try {
                colorBar.setBackgroundColor(Color.parseColor(topic.color))
            } catch (_: Exception) {
                colorBar.setBackgroundColor(itemView.context.themeColor(R.attr.colorAccent))
            }

            // ── Icon + name ───────────────────────────────────────────
            tvIcon.text = topic.icon
            tvName.text = topic.name

            // ── Recording count ·N (hidden when 0) ────────────────────
            val count = item.treeNode.recordings.size
            if (count > 0) {
                tvCount.visibility = View.VISIBLE
                tvCount.text = "·$count"
            } else {
                tvCount.visibility = View.GONE
            }

            // ── Selection highlight ───────────────────────────────────
            val isSelected = topic.id == selectedTopicId
            itemView.setBackgroundColor(
                if (isSelected) itemView.context.themeColor(R.attr.colorSurfaceElevated)
                else Color.TRANSPARENT
            )
            tvName.setTextColor(
                if (isSelected) itemView.context.themeColor(R.attr.colorAccent)
                else itemView.context.themeColor(R.attr.colorTextPrimary)
            )

            // ── Chevron: only for topics with child topics (not recordings) ──
            val hasChildTopics = item.treeNode.children.isNotEmpty()
            if (hasChildTopics) {
                ivChevron.visibility = View.VISIBLE
                ivChevron.rotation = if (item.isCollapsed) -90f else 0f
                ivChevron.setOnClickListener { onCollapseToggle(topic.id, item.isCollapsed) }
            } else {
                ivChevron.visibility = View.INVISIBLE
                ivChevron.setOnClickListener(null)
            }

            // ── Row tap = select / deselect ───────────────────────────
            itemView.setOnClickListener {
                val newSelection = if (isSelected) -1L else topic.id
                selectedTopicId = newSelection
                onTopicSelect(if (newSelection == -1L) null else newSelection)
            }

            // ── Move button ───────────────────────────────────────────
            btnMove.setOnClickListener { onMoveClick(topic.id) }

            // ── DETAILS button ────────────────────────────────────────
            btnDetails.setOnClickListener { onDetailsClick(topic.id) }
        }
    }
}