package com.treecast.app.ui.tree

import android.app.AlertDialog
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
import androidx.core.view.GestureDetectorCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.treecast.app.R
import com.treecast.app.data.entities.CategoryEntity
import com.treecast.app.data.entities.RecordingEntity
import com.treecast.app.data.repository.TreeItem
import com.treecast.app.data.repository.TreeNode
import com.treecast.app.ui.common.CategoryPickerView
import java.text.SimpleDateFormat
import java.util.*

class TreeItemAdapter(
    private val onCategoryClick: (TreeNode, Boolean) -> Unit,
    private val onPlayPause: (RecordingEntity) -> Unit,
    private val onRename: (id: Long, newTitle: String) -> Unit,
    private val onMove: (id: Long, categoryId: Long?) -> Unit,
    private val onDelete: (RecordingEntity) -> Unit,
) : ListAdapter<TreeItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_NODE = 0
        private const val TYPE_LEAF = 1
        private const val INDENT_DP = 20f

        val DIFF = object : DiffUtil.ItemCallback<TreeItem>() {
            override fun areItemsTheSame(a: TreeItem, b: TreeItem) = when {
                a is TreeItem.Node && b is TreeItem.Node -> a.treeNode.category.id == b.treeNode.category.id
                a is TreeItem.Leaf && b is TreeItem.Leaf -> a.recording.id == b.recording.id
                else -> false
            }
            override fun areContentsTheSame(a: TreeItem, b: TreeItem) = a == b
        }
    }

    private var expandedRecordingId: Long = -1L
    var categories: List<CategoryEntity> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    var nowPlayingId: Long = -1L
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }
    var isPlaying: Boolean = false
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

    // ── Node ViewHolder ────────────────────────────────────────────────
    inner class NodeVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvIcon:    TextView  = v.findViewById(R.id.tvIcon)
        private val tvName:    TextView  = v.findViewById(R.id.tvName)
        private val tvCount:   TextView  = v.findViewById(R.id.tvCount)
        private val ivChevron: ImageView = v.findViewById(R.id.ivChevron)
        private val colorBar:  View      = v.findViewById(R.id.colorBar)

        fun bind(item: TreeItem.Node) {
            val cat = item.treeNode.category
            val density = itemView.resources.displayMetrics.density
            itemView.setPaddingRelative(
                (item.depth * INDENT_DP * density).toInt(),
                itemView.paddingTop, itemView.paddingEnd, itemView.paddingBottom
            )
            tvIcon.text = cat.icon
            tvName.text = cat.name
            val rc = item.treeNode.recordings.size
            val cc = item.treeNode.children.size
            tvCount.text = when {
                rc > 0 && cc > 0 -> "$rc eps · $cc folders"
                rc > 0 -> "$rc episodes"
                cc > 0 -> "$cc folders"
                else -> "Empty"
            }
            try { colorBar.setBackgroundColor(Color.parseColor(cat.color)) }
            catch (e: Exception) { colorBar.setBackgroundColor(Color.parseColor("#6C63FF")) }

            ivChevron.visibility = if (item.hasChildren) View.VISIBLE else View.INVISIBLE
            if (item.hasChildren) ivChevron.rotation = if (item.isCollapsed) -90f else 0f
            itemView.setOnClickListener {
                if (item.hasChildren) onCategoryClick(item.treeNode, item.isCollapsed)
            }
        }
    }

    // ── Leaf ViewHolder ────────────────────────────────────────────────
    inner class LeafVH(v: View) : RecyclerView.ViewHolder(v) {
        private val btnInlinePlay:    ImageView         = v.findViewById(R.id.btnInlinePlay)
        private val tvTitle:          TextView          = v.findViewById(R.id.tvTitle)
        private val tvDuration:       TextView          = v.findViewById(R.id.tvDuration)
        private val tvDate:           TextView          = v.findViewById(R.id.tvDate)
        private val dotListened:      View              = v.findViewById(R.id.dotListened)
        private val ivChevron:        ImageView         = v.findViewById(R.id.ivChevron)
        private val expandedControls: LinearLayout      = v.findViewById(R.id.expandedControls)
        private val btnRename:        MaterialButton    = v.findViewById(R.id.btnRename)
        private val btnMove:          MaterialButton    = v.findViewById(R.id.btnMove)
        private val btnDelete:        MaterialButton    = v.findViewById(R.id.btnDelete)
        private val picker:           CategoryPickerView = v.findViewById(R.id.inlineCategoryPicker)

        private val gestureDetector = GestureDetectorCompat(v.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    toggleExpand(bindingAdapterPosition); return true
                }
            }
        )

        init {
            itemView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); true }
        }

        fun bind(item: TreeItem.Leaf) {
            val rec = item.recording
            val density = itemView.resources.displayMetrics.density
            itemView.setPaddingRelative(
                (item.depth * INDENT_DP * density).toInt(),
                itemView.paddingTop, itemView.paddingEnd, itemView.paddingBottom
            )
            tvTitle.text = rec.title
            tvDuration.text = formatDuration(rec.durationMs)
            tvDate.text = formatDate(rec.createdAt)
            dotListened.visibility = if (rec.isListened) View.GONE else View.VISIBLE

            val isThisPlaying = rec.id == nowPlayingId && isPlaying
            btnInlinePlay.setImageResource(
                if (isThisPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            btnInlinePlay.setOnClickListener { onPlayPause(rec) }

            val isExpanded = rec.id == expandedRecordingId
            expandedControls.visibility = if (isExpanded) View.VISIBLE else View.GONE
            ivChevron.rotation = if (isExpanded) 0f else -90f

            btnRename.setOnClickListener { showRenameDialog(rec) }
            btnDelete.setOnClickListener { showDeleteDialog(rec) }
            btnMove.setOnClickListener {
                val showing = picker.visibility == View.VISIBLE
                picker.visibility = if (showing) View.GONE else View.VISIBLE
                if (!showing) {
                    picker.setCategories(categories)
                    val catName = rec.categoryId?.let { id ->
                        categories.find { it.id == id }?.name
                    } ?: "Uncategorised"
                    picker.setSelectedCategory(rec.categoryId, catName)
                    picker.onCategorySelected = { catId ->
                        onMove(rec.id, catId)
                        picker.visibility = View.GONE
                        collapseItem(rec.id)
                    }
                }
            }
        }

        private fun toggleExpand(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val rec = (getItem(position) as? TreeItem.Leaf)?.recording ?: return
            val previousId = expandedRecordingId
            expandedRecordingId = if (rec.id == expandedRecordingId) -1L else rec.id
            if (previousId != -1L && previousId != rec.id) {
                val prevPos = currentList.indexOfFirst {
                    it is TreeItem.Leaf && it.recording.id == previousId
                }
                if (prevPos != -1) notifyItemChanged(prevPos)
            }
            notifyItemChanged(position)
        }

        private fun collapseItem(id: Long) {
            if (expandedRecordingId == id) {
                expandedRecordingId = -1L
                val pos = currentList.indexOfFirst { it is TreeItem.Leaf && (it as TreeItem.Leaf).recording.id == id }
                if (pos != -1) notifyItemChanged(pos)
            }
        }

        private fun showRenameDialog(rec: RecordingEntity) {
            val input = EditText(itemView.context).apply { setText(rec.title); selectAll() }
            val pad = (16 * itemView.resources.displayMetrics.density).toInt()
            val container = LinearLayout(itemView.context).apply {
                setPadding(pad, 0, pad, 0); addView(input)
            }
            AlertDialog.Builder(itemView.context)
                .setTitle("Rename")
                .setView(container)
                .setPositiveButton("Save") { _, _ ->
                    val t = input.text.toString().trim()
                    if (t.isNotEmpty()) { onRename(rec.id, t); collapseItem(rec.id) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun showDeleteDialog(rec: RecordingEntity) {
            AlertDialog.Builder(itemView.context)
                .setTitle("Delete recording?")
                .setMessage("\"${rec.title}\"\n\nThis cannot be undone.")
                .setPositiveButton("Delete") { _, _ -> onDelete(rec) }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun formatDuration(ms: Long): String {
            val s = ms / 1000; val m = s / 60
            return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s % 60)
            else "%d:%02d".format(m, s % 60)
        }

        private fun formatDate(epoch: Long) =
            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(epoch))
    }
}
