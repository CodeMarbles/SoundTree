package com.treecast.app.ui.topics

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.treecast.app.R
import com.treecast.app.data.entities.RecordingEntity
import com.treecast.app.data.entities.TopicEntity
import com.treecast.app.data.repository.TreeItem
import com.treecast.app.data.repository.TreeNode
import com.treecast.app.ui.common.TopicPickerView
import com.treecast.app.util.Icons
import com.treecast.app.util.themeColor
import java.text.SimpleDateFormat
import java.util.*

class TopicItemAdapter(
    private val onTopicClick:   (TreeNode, Boolean) -> Unit,
    private val onTopicIconChange: (TopicEntity) -> Unit,
    private val onTopicRename:  (TopicEntity, String) -> Unit,
    private val onTopicDelete:  (TopicEntity) -> Unit,
    private val onDetailsClick: (topicId: Long) -> Unit,
    private val onPlayPause:    (RecordingEntity) -> Unit,
    private val onRename:       (id: Long, newTitle: String) -> Unit,
    private val onMove:         (id: Long, topicId: Long?) -> Unit,
    private val onDelete:       (RecordingEntity) -> Unit,
    private val onSelect: (Long) -> Unit = {},
) : ListAdapter<TreeItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_NODE = 0
        private const val TYPE_LEAF = 1
        private const val INDENT_DP = 20f

        val DIFF = object : DiffUtil.ItemCallback<TreeItem>() {
            override fun areItemsTheSame(a: TreeItem, b: TreeItem) = when {
                a is TreeItem.Node && b is TreeItem.Node ->
                    a.treeNode.topic.id == b.treeNode.topic.id
                a is TreeItem.Leaf && b is TreeItem.Leaf ->
                    a.recording.id == b.recording.id
                else -> false
            }
            override fun areContentsTheSame(a: TreeItem, b: TreeItem) = a == b
        }
    }

    private var expandedTopicId:     Long = -1L
    private var expandedRecordingId: Long = -1L

    var topics: List<TopicEntity> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    var nowPlayingId: Long = -1L
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }
    var isPlaying: Boolean = false
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }
    var orphanVolumeUuids: Set<String> = emptySet()
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }

    var selectedRecordingId: Long = -1L
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is TreeItem.Node -> TYPE_NODE
        is TreeItem.Leaf -> TYPE_LEAF
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_NODE -> NodeVH(inf.inflate(R.layout.item_tree_node, parent, false))
            else      -> LeafVH(inf.inflate(R.layout.item_tree_leaf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is TreeItem.Node -> (holder as NodeVH).bind(item)
            is TreeItem.Leaf -> (holder as LeafVH).bind(item)
        }
    }

    private fun toggleTopicExpand(topicId: Long) {
        val prev = expandedTopicId
        expandedTopicId = if (prev == topicId) -1L else topicId
        // Rebind both the old and new expanded item so visibility updates instantly
        for (i in 0 until currentList.size) {
            val item = getItem(i)
            if (item is TreeItem.Node &&
                (item.treeNode.topic.id == topicId || item.treeNode.topic.id == prev)) {
                notifyItemChanged(i)
            }
        }
    }

    private fun collapseTopicItem(topicId: Long) {
        if (expandedTopicId == topicId) {
            expandedTopicId = -1L
            for (i in 0 until currentList.size) {
                val item = getItem(i)
                if (item is TreeItem.Node && item.treeNode.topic.id == topicId) {
                    notifyItemChanged(i); break
                }
            }
        }
    }

    private fun collapseLeafItem(recordingId: Long) {
        if (expandedRecordingId == recordingId) {
            expandedRecordingId = -1L
            for (i in 0 until currentList.size) {
                val item = getItem(i)
                if (item is TreeItem.Leaf && item.recording.id == recordingId) {
                    notifyItemChanged(i); break
                }
            }
        }
    }

    // ── Node ViewHolder ────────────────────────────────────────────────
    // IDs from item_tree_node.xml: colorBar, tvIcon, tvName, tvCount, ivChevron,
    // expandedControls, btnIcon, btnRename, btnDelete, btnDetails
    inner class NodeVH(v: View) : RecyclerView.ViewHolder(v) {
        private val colorBar:         View          = v.findViewById(R.id.colorBar)
        private val tvIcon:           TextView      = v.findViewById(R.id.tvIcon)
        private val tvName:           TextView      = v.findViewById(R.id.tvName)
        private val tvCount:          TextView      = v.findViewById(R.id.tvCount)
        private val ivChevron:        ImageView     = v.findViewById(R.id.ivChevron)
        private val expandedControls: LinearLayout  = v.findViewById(R.id.expandedControls)
        private val btnIcon:          MaterialButton = v.findViewById(R.id.btnIcon)
        private val btnRename:        MaterialButton = v.findViewById(R.id.btnRename)
        private val btnDelete:        MaterialButton = v.findViewById(R.id.btnDelete)
        private val btnDetails:       MaterialButton = v.findViewById(R.id.btnDetails)

        fun bind(item: TreeItem.Node) {
            val topic   = item.treeNode.topic
            val density = itemView.resources.displayMetrics.density
            itemView.setPaddingRelative(
                (item.depth * INDENT_DP * density).toInt(),
                itemView.paddingTop, itemView.paddingEnd, itemView.paddingBottom)

            tvIcon.text = topic.icon
            tvName.text = topic.name

            val rc = item.treeNode.recordings.size
            val cc = item.treeNode.children.size
            tvCount.text = when {
                rc > 0 && cc > 0 -> "$rc eps · $cc folders"
                rc > 0           -> "$rc episodes"
                cc > 0           -> "$cc folders"
                else             -> "Empty"
            }

            try { colorBar.setBackgroundColor(Color.parseColor(topic.color)) }
            catch (_: Exception) { colorBar.setBackgroundColor(Color.parseColor("#6C63FF")) }

            // Chevron: only visible when there are tree children; handles collapse/expand of subtree
            ivChevron.visibility = if (item.hasChildren) View.VISIBLE else View.INVISIBLE
            if (item.hasChildren) ivChevron.rotation = if (item.isCollapsed) -90f else 0f
            ivChevron.setOnClickListener {
                onTopicClick(item.treeNode, item.isCollapsed)
            }

            // Row tap: toggle edit controls panel
            val isExpanded = topic.id == expandedTopicId
            expandedControls.visibility = if (isExpanded) View.VISIBLE else View.GONE
            itemView.setOnClickListener { toggleTopicExpand(topic.id) }

            // Edit buttons
            btnIcon.setOnClickListener {
                onTopicIconChange(topic)
            }

            btnRename.setOnClickListener {
                val ctx   = itemView.context
                val input = EditText(ctx).apply {
                    setText(topic.name)
                    selectAll()
                    val pad = (12 * ctx.resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                }
                AlertDialog.Builder(ctx)
                    .setTitle("Rename Topic")
                    .setView(input)
                    .setPositiveButton("Rename") { _, _ ->
                        val newName = input.text.toString().trim()
                        if (newName.isNotEmpty()) {
                            onTopicRename(topic, newName)
                            collapseTopicItem(topic.id)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            btnDelete.setOnClickListener {
                val ctx = itemView.context
                val isEmpty = item.treeNode.recordings.isEmpty() && item.treeNode.children.isEmpty()
                if (isEmpty) {
                    AlertDialog.Builder(ctx)
                        .setTitle("Delete Topic")
                        .setMessage("Delete \"${topic.name}\"? This cannot be undone.")
                        .setPositiveButton("Delete") { _, _ ->
                            onTopicDelete(topic)
                            collapseTopicItem(topic.id)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    AlertDialog.Builder(ctx)
                        .setTitle("Cannot Delete")
                        .setMessage("\"${topic.name}\" still has recordings or sub-topics. " +
                                "Move or delete them first.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            btnDetails.setOnClickListener { onDetailsClick(topic.id) }
        }
    }

    // ── Leaf ViewHolder ────────────────────────────────────────────────
    // IDs from item_tree_leaf.xml: btnInlinePlay, dotListened, tvTitle, tvDuration,
    // tvDate, ivChevron, expandedControls, btnRename, btnMove, btnDelete,
    // inlineTopicPicker (class updated to TopicPickerView in XML by step-3 edit)
    inner class LeafVH(v: View) : RecyclerView.ViewHolder(v) {
        private val btnInlinePlay:    ImageView       = v.findViewById(R.id.btnInlinePlay)
        private val tvTitle:          TextView        = v.findViewById(R.id.tvTitle)
        private val tvDuration:       TextView        = v.findViewById(R.id.tvDuration)
        private val tvDate:           TextView        = v.findViewById(R.id.tvDate)
        private val ivChevron:        ImageView       = v.findViewById(R.id.ivChevron)
        private val expandedControls: LinearLayout    = v.findViewById(R.id.expandedControls)
        private val btnRename:        MaterialButton  = v.findViewById(R.id.btnRename)
        private val btnMove:          MaterialButton  = v.findViewById(R.id.btnMove)
        private val btnDelete:        MaterialButton  = v.findViewById(R.id.btnDelete)
        private val picker:           TopicPickerView = v.findViewById(R.id.inlineTopicPicker)

        private val gestureDetector = GestureDetectorCompat(v.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        (getItem(pos) as? TreeItem.Leaf)?.let { onSelect(it.recording.id) }
                    }
                    toggleExpand(bindingAdapterPosition)
                    return true
                }
            })

        init {
            itemView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); true }
        }

        fun bind(item: TreeItem.Leaf) {
            val rec     = item.recording
            val density = itemView.resources.displayMetrics.density
            itemView.setPaddingRelative(
                (item.depth * INDENT_DP * density).toInt(),
                itemView.paddingTop, itemView.paddingEnd, itemView.paddingBottom)

            tvTitle.text    = rec.title
            tvDuration.text = formatDuration(rec.durationMs)
            tvDate.text     = formatDate(rec.createdAt)

            // ── Selection highlight ───────────────────────────────────
            // Tint the row background when this recording is the loaded/playing one.
            // The ripple lives on foreground (item_tree_leaf.xml) so this is safe.
            val isSelected    = rec.id == selectedRecordingId || rec.id == nowPlayingId
            itemView.setBackgroundColor(
                if (isSelected) itemView.context.themeColor(R.attr.colorSurfaceElevated)
                else android.graphics.Color.TRANSPARENT
            )

            val isOrphan = rec.storageVolumeUuid in orphanVolumeUuids

            if (isOrphan) {
                btnInlinePlay.setImageResource(R.drawable.ic_storage_offline)
                btnInlinePlay.imageTintList = ColorStateList.valueOf(
                    itemView.context.themeColor(R.attr.colorTextSecondary))
                itemView.alpha = 0.5f
                btnInlinePlay.setOnClickListener {
                    Snackbar.make(
                        itemView,
                        "Storage device not connected",
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            } else {
                val isThisPlaying = rec.id == nowPlayingId && isPlaying
                btnInlinePlay.setImageResource(
                    if (isThisPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                btnInlinePlay.imageTintList = ColorStateList.valueOf(
                    itemView.context.themeColor(R.attr.colorAccent))
                itemView.alpha = 1f
                btnInlinePlay.setOnClickListener { onPlayPause(rec) }
            }


            val isExpanded = rec.id == expandedRecordingId
            expandedControls.visibility = if (isExpanded) View.VISIBLE else View.GONE
            ivChevron.rotation = if (isExpanded) 0f else -90f

            btnRename.setOnClickListener { showRenameDialog(rec) }
            btnDelete.setOnClickListener { showDeleteDialog(rec) }
            btnMove.setOnClickListener {
                val showing = picker.visibility == View.VISIBLE
                picker.visibility = if (showing) View.GONE else View.VISIBLE
                if (!showing) {
                    picker.setTopics(topics)
                    val topic = rec.topicId?.let { id -> topics.find { it.id == id } }
                    picker.setSelectedTopic(
                        rec.topicId,
                        topic?.name ?: itemView.context.getString(R.string.label_unsorted),
                        topic?.icon ?: Icons.INBOX
                    )
                    picker.onTopicSelected = { topicId ->
                        onMove(rec.id, topicId)
                        picker.visibility = View.GONE
                        collapseLeafItem(rec.id)
                    }
                }
            }
        }

        private fun toggleExpand(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val rec = (getItem(position) as? TreeItem.Leaf)?.recording ?: return
            val prev = expandedRecordingId
            expandedRecordingId = if (prev == rec.id) -1L else rec.id
            notifyItemChanged(position)
            if (prev != -1L && prev != rec.id) {
                for (i in 0 until currentList.size) {
                    val item = getItem(i)
                    if (item is TreeItem.Leaf && item.recording.id == prev) {
                        notifyItemChanged(i); break
                    }
                }
            }
        }

        private fun showRenameDialog(rec: RecordingEntity) {
            val ctx   = itemView.context
            val input = EditText(ctx).apply {
                setText(rec.title)
                selectAll()
                val pad = (12 * ctx.resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            AlertDialog.Builder(ctx)
                .setTitle("Rename Recording")
                .setView(input)
                .setPositiveButton("Rename") { _, _ ->
                    val newTitle = input.text.toString().trim()
                    if (newTitle.isNotEmpty()) onRename(rec.id, newTitle)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun showDeleteDialog(rec: RecordingEntity) {
            AlertDialog.Builder(itemView.context)
                .setTitle("Delete Recording")
                .setMessage("Delete \"${rec.title}\"? This cannot be undone.")
                .setPositiveButton("Delete") { _, _ -> onDelete(rec) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────
    private fun formatDuration(ms: Long): String {
        val s = ms / 1000; val m = s / 60
        return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s % 60)
        else "%d:%02d".format(m, s % 60)
    }

    private fun formatDate(ms: Long): String =
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ms))
}