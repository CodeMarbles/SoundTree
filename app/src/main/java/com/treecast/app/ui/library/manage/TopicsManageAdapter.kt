package com.treecast.app.ui.library.manage

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.treecast.app.R
import com.treecast.app.data.repository.TreeItem
import com.treecast.app.util.themeColor

// ── Unsorted static row ────────────────────────────────────────────────────
//
// Kept in its own single-item adapter so that ListAdapter's DiffUtil
// notifications in TopicsManageAdapter never have to account for an offset.

class UnsortedRowAdapter(
    private val onUnsortedClick: () -> Unit,
) : RecyclerView.Adapter<UnsortedRowAdapter.VH>() {

    var unsortedCount: Int = 0
        set(value) {
            if (field != value) {
                field = value
                notifyItemChanged(0)
            }
        }

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_topic_manage_row, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind()

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvIcon:     TextView  = v.findViewById(R.id.tvIcon)
        private val tvName:     TextView  = v.findViewById(R.id.tvName)
        private val tvCount:    TextView  = v.findViewById(R.id.tvCount)
        private val ivOverflow: ImageView = v.findViewById(R.id.ivOverflow)
        private val ivChevron:  ImageView = v.findViewById(R.id.ivChevron)

        fun bind() {
            tvIcon.text = "📥"
            tvName.text = itemView.context.getString(R.string.topic_label_unsorted)
            tvName.setTextColor(itemView.context.themeColor(R.attr.colorTextSecondary))

            if (unsortedCount > 0) {
                tvCount.visibility = View.VISIBLE
                tvCount.text = itemView.context.resources.getQuantityString(
                    R.plurals.common_label_recording_count, unsortedCount, unsortedCount
                )
            } else {
                tvCount.visibility = View.GONE
            }

            // Unsorted row is purely navigational — no management actions.
            ivOverflow.visibility = View.GONE
            ivChevron.visibility  = View.INVISIBLE
            itemView.setOnClickListener { onUnsortedClick() }
            itemView.setOnLongClickListener(null)
        }
    }
}

// ── Topic rows ─────────────────────────────────────────────────────────────

class TopicsManageAdapter(
    private val onCollapseToggle: (topicId: Long, currentlyCollapsed: Boolean) -> Unit,
    private val onTopicClick:     (topicId: Long) -> Unit,
    private val onNewSubtopic:    (parentId: Long) -> Unit,
    private val onMoveClick:      (topicId: Long) -> Unit,
    private val onRenameClick:    (topicId: Long, currentName: String) -> Unit,
    private val onIconClick:      (topicId: Long) -> Unit,
    private val onDeleteClick:    (topicId: Long) -> Unit,
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_topic_manage_row, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvIcon:     TextView  = v.findViewById(R.id.tvIcon)
        private val tvName:     TextView  = v.findViewById(R.id.tvName)
        private val tvCount:    TextView  = v.findViewById(R.id.tvCount)
        private val ivOverflow: ImageView = v.findViewById(R.id.ivOverflow)
        private val ivChevron:  ImageView = v.findViewById(R.id.ivChevron)

        fun bind(item: TreeItem.Node) {
            val topic   = item.treeNode.topic
            val isEmpty = item.treeNode.recordings.isEmpty() &&
                    item.treeNode.children.isEmpty()

            // ── Indent ────────────────────────────────────────────────
            val density   = itemView.resources.displayMetrics.density
            val indentPx  = ((item.depth * DEPTH_INDENT_DP + BASE_PADDING_DP) * density).toInt()
            itemView.setPaddingRelative(indentPx, 0, itemView.paddingEnd, 0)

            // ── Content ───────────────────────────────────────────────
            tvIcon.text = topic.icon
            tvName.text = topic.name
            tvName.setTextColor(itemView.context.themeColor(R.attr.colorTextPrimary))

            val totalCount = item.treeNode.recordings.size
            if (totalCount > 0) {
                tvCount.visibility = View.VISIBLE
                tvCount.text = itemView.context.resources.getQuantityString(
                    R.plurals.common_label_recording_count, totalCount, totalCount
                )
            } else {
                tvCount.visibility = View.GONE
            }

            // ── Overflow ⋮ ────────────────────────────────────────────
            ivOverflow.visibility = View.VISIBLE
            ivOverflow.contentDescription = "${topic.name} options"
            ivOverflow.setOnClickListener { showOptionsMenu(topic.id, topic.name, isEmpty) }

            // ── Chevron ───────────────────────────────────────────────
            if (item.treeNode.children.isNotEmpty()) {
                ivChevron.visibility = View.VISIBLE
                ivChevron.rotation   = if (item.isCollapsed) -90f else 0f
                ivChevron.contentDescription =
                    if (item.isCollapsed) "Expand ${topic.name}"
                    else                  "Collapse ${topic.name}"
                ivChevron.setOnClickListener { onCollapseToggle(topic.id, item.isCollapsed) }
            } else {
                ivChevron.visibility = View.INVISIBLE
                ivChevron.setOnClickListener(null)
            }

            // ── Single tap → Details ──────────────────────────────────
            itemView.setOnClickListener { onTopicClick(topic.id) }

            // ── Long press → same menu as overflow ────────────────────
            itemView.setOnLongClickListener {
                showOptionsMenu(topic.id, topic.name, isEmpty)
                true
            }

            // ── Accessibility actions ─────────────────────────────────
            setupAccessibilityActions(topic.id, topic.name, isEmpty)
        }

        // ── Popup menu ────────────────────────────────────────────────

        private fun showOptionsMenu(topicId: Long, topicName: String, isEmpty: Boolean) {
            PopupMenu(ivOverflow.context, ivOverflow).apply {
                menuInflater.inflate(R.menu.menu_topic_options, menu)
                menu.findItem(R.id.action_delete)?.isVisible = isEmpty
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_new_subtopic -> { onNewSubtopic(topicId); true }
                        R.id.action_move         -> { onMoveClick(topicId); true }
                        R.id.action_rename       -> { onRenameClick(topicId, topicName); true }
                        R.id.action_icon         -> { onIconClick(topicId); true }
                        R.id.action_delete       -> { onDeleteClick(topicId); true }
                        else                     -> false
                    }
                }
                show()
            }
        }

        // ── Accessibility ─────────────────────────────────────────────

        private fun setupAccessibilityActions(
            topicId: Long,
            topicName: String,
            isEmpty: Boolean
        ) {
            ViewCompat.addAccessibilityAction(itemView, "New subtopic") { _, _ ->
                onNewSubtopic(topicId); true
            }
            ViewCompat.addAccessibilityAction(itemView, "Move topic") { _, _ ->
                onMoveClick(topicId); true
            }
            ViewCompat.addAccessibilityAction(itemView, "Rename topic") { _, _ ->
                onRenameClick(topicId, topicName); true
            }
            ViewCompat.addAccessibilityAction(itemView, "Change icon") { _, _ ->
                onIconClick(topicId); true
            }
            // Delete is only available for empty topics — skip registering the
            // action entirely when the topic has content, so it never appears
            // in TalkBack's actions menu for non-empty rows.
            if (isEmpty) {
                ViewCompat.addAccessibilityAction(itemView, "Delete topic") { _, _ ->
                    onDeleteClick(topicId); true
                }
            }
        }
    }
}