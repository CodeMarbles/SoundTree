package com.treecast.app.ui.library.manage

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.ConcatAdapter
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
        private val tvIcon:    TextView  = v.findViewById(R.id.tvIcon)
        private val tvName:    TextView  = v.findViewById(R.id.tvName)
        private val tvCount:   TextView  = v.findViewById(R.id.tvCount)
        private val ivChevron: ImageView = v.findViewById(R.id.ivChevron)

        fun bind() {
            tvIcon.text = "📥"
            tvName.text = itemView.context.getString(R.string.label_unsorted)
            tvName.setTextColor(itemView.context.themeColor(R.attr.colorTextSecondary))

            if (unsortedCount > 0) {
                tvCount.visibility = View.VISIBLE
                tvCount.text = itemView.context.resources.getQuantityString(
                    R.plurals.recording_count, unsortedCount, unsortedCount
                )
            } else {
                tvCount.visibility = View.GONE
            }

            ivChevron.visibility = View.INVISIBLE
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

        private const val MENU_NEW_SUBTOPIC = 1
        private const val MENU_MOVE         = 2
        private const val MENU_RENAME       = 3
        private const val MENU_ICON         = 4
        private const val MENU_DELETE       = 5
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_topic_manage_row, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvIcon:    TextView  = v.findViewById(R.id.tvIcon)
        private val tvName:    TextView  = v.findViewById(R.id.tvName)
        private val tvCount:   TextView  = v.findViewById(R.id.tvCount)
        private val ivChevron: ImageView = v.findViewById(R.id.ivChevron)

        fun bind(item: TreeItem.Node) {
            val topic   = item.treeNode.topic
            val density = itemView.resources.displayMetrics.density

            // ── Indentation ───────────────────────────────────────────
            val indentPx = ((BASE_PADDING_DP + item.depth * DEPTH_INDENT_DP) * density).toInt()
            itemView.setPaddingRelative(indentPx, 0, itemView.paddingEnd, 0)

            // ── Icon + name ───────────────────────────────────────────
            tvIcon.text = topic.icon
            tvName.text = topic.name
            tvName.setTextColor(itemView.context.themeColor(R.attr.colorTextPrimary))

            // ── Recording count ───────────────────────────────────────
            val count = item.treeNode.recordings.size
            if (count > 0) {
                tvCount.visibility = View.VISIBLE
                tvCount.text = itemView.context.resources.getQuantityString(
                    R.plurals.recording_count, count, count
                )
            } else {
                tvCount.visibility = View.GONE
            }

            // ── Chevron: only when topic has children ─────────────────
            val hasChildTopics = item.treeNode.children.isNotEmpty()
            if (hasChildTopics) {
                ivChevron.visibility = View.VISIBLE
                ivChevron.rotation = if (item.isCollapsed) -90f else 0f
                ivChevron.setOnClickListener { onCollapseToggle(topic.id, item.isCollapsed) }
            } else {
                ivChevron.visibility = View.INVISIBLE
                ivChevron.setOnClickListener(null)
            }

            // ── Single tap → Details ──────────────────────────────────
            itemView.setOnClickListener { onTopicClick(topic.id) }

            // ── Long press → PopupMenu ────────────────────────────────
            itemView.setOnLongClickListener { anchor ->
                val isEmpty = item.treeNode.recordings.isEmpty() &&
                        item.treeNode.children.isEmpty()

                PopupMenu(anchor.context, anchor).apply {
                    menu.add(0, MENU_NEW_SUBTOPIC, 0, "New Subtopic")
                    menu.add(0, MENU_MOVE,         1, "Move")
                    menu.add(0, MENU_RENAME,       2, "Rename")
                    menu.add(0, MENU_ICON,         3, "Icon")
                    if (isEmpty) menu.add(0, MENU_DELETE, 4, "Delete")

                    setOnMenuItemClickListener { menuItem ->
                        when (menuItem.itemId) {
                            MENU_NEW_SUBTOPIC -> { onNewSubtopic(topic.id); true }
                            MENU_MOVE         -> { onMoveClick(topic.id); true }
                            MENU_RENAME       -> { onRenameClick(topic.id, topic.name); true }
                            MENU_ICON         -> { onIconClick(topic.id); true }
                            MENU_DELETE       -> { onDeleteClick(topic.id); true }
                            else              -> false
                        }
                    }
                    show()
                }
                true
            }
        }
    }
}