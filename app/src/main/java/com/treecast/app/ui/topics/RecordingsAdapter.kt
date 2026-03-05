package com.treecast.app.ui.topics

import android.app.AlertDialog
import android.content.res.ColorStateList
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
import com.treecast.app.ui.common.TopicPickerView
import com.treecast.app.util.Icons
import com.treecast.app.util.themeColor
import java.text.SimpleDateFormat
import java.util.*

class RecordingsAdapter(
    private val onPlayPause: (RecordingEntity) -> Unit,
    private val onRename:    (id: Long, newTitle: String) -> Unit,
    private val onMove:      (id: Long, topicId: Long?) -> Unit,
    private val onDelete:    (RecordingEntity) -> Unit,
    private val onSelect:    (Long) -> Unit = {},
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
    var orphanVolumeUuids: Set<String> = emptySet()
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }

    var selectedRecordingId: Long = -1L
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }

    private var expandedId: Long = -1L

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
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
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onSelect(getItem(pos).id)
                    }
                    toggleExpand(bindingAdapterPosition)
                    return true
                }
            })

        init {
            itemView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); true }
        }

        fun bind(rec: RecordingEntity) {
            tvTitle.text = rec.title
            tvMeta.text  = "${formatDuration(rec.durationMs)} · ${formatDate(rec.createdAt)}"
            dotListened.visibility = if (rec.isListened) View.GONE else View.VISIBLE

            // ── Selection highlight ───────────────────────────────────
            // Tint the row background when this recording is the loaded/playing one.
            val isSelected = rec.id == selectedRecordingId
            itemView.setBackgroundColor(
                if (isSelected) itemView.context.themeColor(R.attr.colorSurfaceElevated)
                else android.graphics.Color.TRANSPARENT
            )

            val isOrphan = rec.storageVolumeUuid in orphanVolumeUuids

            if (isOrphan) {
                // Show warning icon, mute the row, block playback.
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
                val isOrphan = rec.storageVolumeUuid in orphanVolumeUuids

                if (isOrphan) {
                    // Show warning icon, mute the row, block playback.
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
            }
        }

        private fun toggleExpand(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val rec = getItem(position) ?: return
            val previousExpanded = expandedId
            expandedId = if (expandedId == rec.id) -1L else rec.id

            if (previousExpanded != -1L) {
                val prevPos = currentList.indexOfFirst { it.id == previousExpanded }
                if (prevPos != -1) notifyItemChanged(prevPos)
            }
            notifyItemChanged(position)
        }

        private fun collapseItem(recordingId: Long) {
            if (expandedId == recordingId) {
                expandedId = -1L
                val pos = currentList.indexOfFirst { it.id == recordingId }
                if (pos != -1) notifyItemChanged(pos)
            }
        }

        private fun showRenameDialog(rec: RecordingEntity) {
            val ctx = itemView.context
            val input = EditText(ctx).apply {
                setText(rec.title)
                selectAll()
                setPadding(48, 24, 48, 8)
            }
            AlertDialog.Builder(ctx)
                .setTitle("Rename recording")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val newTitle = input.text.toString().trim()
                    if (newTitle.isNotEmpty()) onRename(rec.id, newTitle)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun showDeleteDialog(rec: RecordingEntity) {
            AlertDialog.Builder(itemView.context)
                .setTitle("Delete recording")
                .setMessage("\"${rec.title}\" will be permanently deleted.")
                .setPositiveButton("Delete") { _, _ -> onDelete(rec) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    private fun formatDate(epochMs: Long): String =
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochMs))
}