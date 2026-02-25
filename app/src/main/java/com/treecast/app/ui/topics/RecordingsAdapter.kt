package com.treecast.app.ui.topics

import android.app.AlertDialog
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
import com.treecast.app.data.entities.RecordingEntity
import com.treecast.app.data.entities.TopicEntity
import com.treecast.app.ui.common.TopicPickerView
import java.text.SimpleDateFormat
import java.util.*

class RecordingsAdapter(
    private val onPlayPause: (RecordingEntity) -> Unit,
    private val onRename:    (id: Long, newTitle: String) -> Unit,
    private val onMove:      (id: Long, topicId: Long?) -> Unit,
    private val onDelete:    (RecordingEntity) -> Unit,
) : ListAdapter<RecordingEntity, RecordingsAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<RecordingEntity>() {
            override fun areItemsTheSame(a: RecordingEntity, b: RecordingEntity) = a.id == b.id
            override fun areContentsTheSame(a: RecordingEntity, b: RecordingEntity) = a == b
        }
    }

    var topics: List<TopicEntity> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    var nowPlayingId: Long = -1L
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }
    var isPlaying: Boolean = false
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }

    private var expandedId: Long = -1L

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        // IDs from item_recording.xml
        private val btnInlinePlay:    ImageView       = v.findViewById(R.id.btnInlinePlay)
        private val dotListened:      View            = v.findViewById(R.id.dotListened)
        private val tvTitle:          TextView        = v.findViewById(R.id.tvTitle)
        private val tvMeta:           TextView        = v.findViewById(R.id.tvMeta)
        private val ivChevron:        ImageView       = v.findViewById(R.id.ivChevron)
        private val expandedControls: LinearLayout    = v.findViewById(R.id.expandedControls)
        private val btnRename:        MaterialButton  = v.findViewById(R.id.btnRename)
        private val btnMove:          MaterialButton  = v.findViewById(R.id.btnMove)
        private val btnDelete:        MaterialButton  = v.findViewById(R.id.btnDelete)
        private val picker:           TopicPickerView = v.findViewById(R.id.inlineTopicPicker)

        private val gestureDetector = GestureDetectorCompat(v.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    toggleExpand(bindingAdapterPosition); return true
                }
            })

        init {
            itemView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); true }
        }

        fun bind(rec: RecordingEntity) {
            tvTitle.text = rec.title
            tvMeta.text  = "${formatDuration(rec.durationMs)} · ${formatDate(rec.createdAt)}"
            dotListened.visibility = if (rec.isListened) View.GONE else View.VISIBLE

            val isThisPlaying = rec.id == nowPlayingId && isPlaying
            btnInlinePlay.setImageResource(
                if (isThisPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            btnInlinePlay.setOnClickListener { onPlayPause(rec) }

            val isExpanded = rec.id == expandedId
            expandedControls.visibility = if (isExpanded) View.VISIBLE else View.GONE
            ivChevron.rotation = if (isExpanded) 0f else -90f

            btnRename.setOnClickListener { showRenameDialog(rec) }
            btnDelete.setOnClickListener { showDeleteDialog(rec) }
            btnMove.setOnClickListener {
                val showing = picker.visibility == View.VISIBLE
                picker.visibility = if (showing) View.GONE else View.VISIBLE
                if (!showing) {
                    picker.setTopics(topics)
                    val topicName = rec.topicId?.let { id -> topics.find { it.id == id }?.name }
                        ?: "Uncategorised"
                    picker.setSelectedTopic(rec.topicId, topicName)
                    picker.onTopicSelected = { topicId ->
                        onMove(rec.id, topicId)
                        picker.visibility = View.GONE
                        collapseItem(rec.id)
                    }
                }
            }
        }

        private fun toggleExpand(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val rec  = getItem(position)
            val prev = expandedId
            expandedId = if (rec.id == expandedId) -1L else rec.id
            if (prev != -1L && prev != rec.id) {
                val prevPos = currentList.indexOfFirst { it.id == prev }
                if (prevPos != -1) notifyItemChanged(prevPos)
            }
            notifyItemChanged(position)
        }

        private fun collapseItem(id: Long) {
            if (expandedId != id) return
            expandedId = -1L
            val pos = currentList.indexOfFirst { it.id == id }
            if (pos != -1) notifyItemChanged(pos)
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
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000; val m = s / 60
        return if (m >= 60) "%dh %02dm".format(m / 60, m % 60)
        else "%dm %02ds".format(m, s % 60)
    }

    private fun formatDate(epoch: Long): String =
        SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epoch))
}