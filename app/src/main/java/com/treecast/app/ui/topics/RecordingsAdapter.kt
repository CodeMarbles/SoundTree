package com.treecast.app.ui.topics

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.treecast.app.R
import com.treecast.app.data.entities.RecordingEntity
import com.treecast.app.data.entities.TopicEntity
import com.treecast.app.util.Icons
import com.treecast.app.util.themeColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Unified adapter for all Recording list contexts:
 *   - All Recordings tab   (showTopicIcon = true)
 *   - Inbox / Unsorted tab (showTopicIcon = false)
 *   - Topic Details tab    (showTopicIcon = false)
 *
 * ── Interactions ──────────────────────────────────────────────────────────────
 *
 * Single tap  → selection highlight + onSelect callback. Navigation TBD;
 *               this is intentionally a no-op at the Fragment level for now
 *               so the selection highlight acknowledges the tap immediately
 *               (no ~300ms GestureDetector delay).
 *
 * Long press  → PopupMenu with Rename / Move to topic… / Delete
 * ⋮ button    → same PopupMenu (discoverability + accessibility fallback)
 * Play button → onPlayPause
 *
 * ── Move architecture ─────────────────────────────────────────────────────────
 *
 * [onMoveRequested] fires when the user chooses "Move to topic…". The adapter
 * does NOT hold a FragmentManager reference — it delegates entirely to the
 * host Fragment, which is responsible for showing TopicPickerBottomSheet and
 * handling its result via setFragmentResultListener. This keeps the adapter
 * testable and free of Fragment lifecycle coupling.
 *
 * ── Accessibility ─────────────────────────────────────────────────────────────
 *
 * Each row registers named AccessibilityActions (Rename / Move to topic /
 * Delete) so TalkBack users can trigger them via swipe-gesture without needing
 * to discover long-press. The ⋮ button's contentDescription handles the
 * simpler double-tap path.
 *
 * ── Migration note ────────────────────────────────────────────────────────────
 *
 * AllRecordingsAdapter is superseded by this class with showTopicIcon = true.
 * AllRecordingsAdapter.kt and item_recording_all.xml can be deleted.
 */
class RecordingsAdapter(
    private val showTopicIcon:    Boolean = false,
    private val onPlayPause:      (RecordingEntity) -> Unit,
    private val onRename:         (id: Long, newTitle: String) -> Unit,
    private val onMoveRequested:  (recordingId: Long, currentTopicId: Long?) -> Unit,
    private val onDelete:         (RecordingEntity) -> Unit,
    private val onSelect:         (Long) -> Unit = {},
) : ListAdapter<RecordingEntity, RecordingsAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<RecordingEntity>() {
            override fun areItemsTheSame(a: RecordingEntity, b: RecordingEntity) = a.id == b.id
            override fun areContentsTheSame(a: RecordingEntity, b: RecordingEntity) = a == b
        }
        private const val NEW_THRESHOLD_MS = 30 * 60 * 1_000L
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    // ── ViewHolder ────────────────────────────────────────────────────────────

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val btnInlinePlay: ImageView = v.findViewById(R.id.btnInlinePlay)
        private val tvTopicIcon:   TextView  = v.findViewById(R.id.tvTopicIcon)
        private val tvNewBadge:    TextView  = v.findViewById(R.id.tvNewBadge)
        private val tvTitle:       TextView  = v.findViewById(R.id.tvTitle)
        private val tvMeta:        TextView  = v.findViewById(R.id.tvMeta)
        private val ivOverflow:    ImageView = v.findViewById(R.id.ivOverflow)

        fun bind(rec: RecordingEntity) {
            tvTitle.text = rec.title
            tvMeta.text  = "${formatDuration(rec.durationMs)} · ${formatDate(rec.createdAt)}"

            // ── Topic icon (All Recordings context only) ──────────────
            if (showTopicIcon) {
                tvTopicIcon.visibility = View.VISIBLE
                val topic = topics.firstOrNull { it.id == rec.topicId }
                tvTopicIcon.text = topic?.icon ?: Icons.INBOX
            } else {
                tvTopicIcon.visibility = View.GONE
            }

            // ── Selection highlight ───────────────────────────────────
            val isSelected = rec.id == selectedRecordingId
            itemView.setBackgroundColor(
                if (isSelected) itemView.context.themeColor(R.attr.colorSurfaceElevated)
                else android.graphics.Color.TRANSPARENT
            )

            // ── Orphan (storage-offline) state ────────────────────────
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
                tvNewBadge.visibility = View.GONE
            } else {
                val isThisPlaying = rec.id == nowPlayingId && isPlaying
                btnInlinePlay.setImageResource(
                    if (isThisPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                btnInlinePlay.imageTintList = ColorStateList.valueOf(
                    itemView.context.themeColor(R.attr.colorAccent))
                itemView.alpha = 1f
                btnInlinePlay.setOnClickListener { onPlayPause(rec) }

                val isNew = System.currentTimeMillis() - rec.createdAt < NEW_THRESHOLD_MS
                tvNewBadge.visibility = if (isNew) View.VISIBLE else View.GONE
            }

            // ── Single tap → selection highlight ─────────────────────
            // Intentionally no navigation here yet. The instant click response
            // (vs the old ~300ms GestureDetector delay) lets the selection
            // highlight acknowledge the tap immediately. Navigation to a
            // detail view will be wired here in a future pass.
            itemView.setOnClickListener {
                onSelect(rec.id)
            }

            // ── Long press → popup menu ───────────────────────────────
            itemView.setOnLongClickListener {
                showOptionsMenu(rec)
                true
            }

            // ── Overflow ⋮ → same popup menu ─────────────────────────
            ivOverflow.setOnClickListener {
                showOptionsMenu(rec)
            }

            // ── Accessibility actions ─────────────────────────────────
            // Registers named swipe-gesture actions for TalkBack users.
            // Calling addAccessibilityAction with the same label on a
            // recycled ViewHolder replaces the previous lambda, so there
            // is no risk of stale closures or duplicate actions.
            setupAccessibilityActions(rec, isOrphan)
        }

        // ── Popup menu ────────────────────────────────────────────────

        private fun showOptionsMenu(rec: RecordingEntity) {
            val isOrphan = rec.storageVolumeUuid in orphanVolumeUuids
            PopupMenu(ivOverflow.context, ivOverflow).apply {
                menuInflater.inflate(R.menu.menu_recording_options, menu)
                // Move is unavailable while storage is offline; the recording
                // can't be reassigned to a topic it may not be able to access.
                menu.findItem(R.id.action_move)?.isVisible = !isOrphan
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_rename -> { showRenameDialog(rec); true }
                        R.id.action_move   -> { onMoveRequested(rec.id, rec.topicId); true }
                        R.id.action_delete -> { showDeleteDialog(rec); true }
                        else               -> false
                    }
                }
                show()
            }
        }

        // ── Accessibility ─────────────────────────────────────────────

        private fun setupAccessibilityActions(rec: RecordingEntity, isOrphan: Boolean) {
            // "Rename" — always available
            ViewCompat.addAccessibilityAction(itemView, "Rename recording") { _, _ ->
                showRenameDialog(rec)
                true
            }
            // "Move to topic" — hidden for orphan recordings (storage offline)
            ViewCompat.addAccessibilityAction(itemView, "Move to topic") { _, _ ->
                if (!isOrphan) onMoveRequested(rec.id, rec.topicId)
                true
            }
            // "Delete" — always available
            ViewCompat.addAccessibilityAction(itemView, "Delete recording") { _, _ ->
                showDeleteDialog(rec)
                true
            }
        }

        // ── Dialogs ───────────────────────────────────────────────────

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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    private fun formatDate(epochMs: Long): String =
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochMs))
}