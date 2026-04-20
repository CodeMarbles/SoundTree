package app.soundtree.ui.topics

import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
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
import app.soundtree.R
import app.soundtree.data.entities.RecordingEntity
import app.soundtree.data.entities.TopicEntity
import app.soundtree.ui.library.SplitBackgroundDrawable
import app.soundtree.util.Icons
import app.soundtree.util.PlaybackPositionHelper
import app.soundtree.util.themeColor
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
    private val showTopicDetails: Boolean = false,
    private val onPlayPause:      (RecordingEntity) -> Unit,
    private val onRename:         (id: Long, newTitle: String) -> Unit,
    private val onMoveRequested:  (recordingId: Long, currentTopicId: Long?) -> Unit,
    private val onDelete:         (RecordingEntity) -> Unit,
    private val onTopicDetailsRequested: (topicId: Long?) -> Unit = {},
    private val onSelect:         (Long) -> Unit = {},
) : ListAdapter<RecordingEntity, RecordingsAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<RecordingEntity>() {
            override fun areItemsTheSame(a: RecordingEntity, b: RecordingEntity) = a.id == b.id
            override fun areContentsTheSame(a: RecordingEntity, b: RecordingEntity) = a == b
        }
        private const val NEW_THRESHOLD_MS = 30 * 60 * 1_000L

        /** Payload object used to request a progress-only partial bind. */
        val PAYLOAD_PROGRESS = Any()
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

    /** Whether the split-background visualisation is enabled (from settings). */
    var playheadVisEnabled: Boolean = true
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }

    /** Intensity in [0.1, 1.0]: controls alpha of the played-region colour. */
    var playheadVisIntensity: Float = 0.5f
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }

    /** Current now-playing position in ms. Set only via [updateNowPlayingProgress]. */
    var nowPlayingPositionMs: Long = 0L
        private set

    /**
     * SharedPreferences injected by the host fragment so the adapter can call
     * PlaybackPositionHelper without needing a Context on every bind.
     * Set once in setupAdapter(): adapter.prefs = requireContext().getSharedPreferences(...)
     */
    var prefs: SharedPreferences? = null

    /**
     * Updates the live playback position and issues a targeted partial rebind
     * (PAYLOAD_PROGRESS) for only the now-playing row, so the split background
     * moves in real time without triggering a full rebind of every item.
     */
    fun updateNowPlayingProgress(positionMs: Long) {
        if (nowPlayingPositionMs == positionMs) return
        nowPlayingPositionMs = positionMs
        val idx = currentList.indexOfFirst { it.id == nowPlayingId }
        if (idx >= 0) notifyItemChanged(idx, PAYLOAD_PROGRESS)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    /**
     * This intercepts PAYLOAD_PROGRESS partial binds and only updates the split
     * background, falling through to a full bind for all other payloads.
     */
    override fun onBindViewHolder(holder: VH, position: Int, payloads: List<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it === PAYLOAD_PROGRESS }) {
            holder.updateProgress(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }


    // ── ViewHolder ────────────────────────────────────────────────────────────

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val btnInlinePlay: ImageView = v.findViewById(R.id.btnInlinePlay)
        private val tvTopicIcon:   TextView  = v.findViewById(R.id.tvTopicIcon)
        private val tvNewBadge:    TextView  = v.findViewById(R.id.tvNewBadge)
        private val tvTitle:       TextView  = v.findViewById(R.id.tvTitle)
        private val tvMeta:        TextView  = v.findViewById(R.id.tvMeta)
        private val ivOverflow:    ImageView = v.findViewById(R.id.ivOverflow)
        /** Retained across partial binds so updateProgress can move the split cheaply. */
        private var splitBg:       SplitBackgroundDrawable? = null

        fun bind(rec: RecordingEntity) {
            tvTitle.text = rec.title
            tvMeta.text  = "${formatDuration(rec.durationMs)} · ${formatDate(rec.createdAt)}"

            // ── Topic icon (All Recordings context only) ──────────────
            if (showTopicIcon) {
                tvTopicIcon.visibility = View.VISIBLE
                val topic = topics.firstOrNull { it.id == rec.topicId }
                tvTopicIcon.text = topic?.icon ?: Icons.UNSORTED
            } else {
                tvTopicIcon.visibility = View.GONE
            }

            // ── Background (selection border + progress split) ────────
            // applySplitBackground owns the background for all rows.
            // When vis is enabled it draws the split + an accent-coloured border
            // for selected rows. When vis is disabled it falls back to a flat
            // selection colour or null.
            val isSelected = rec.id == selectedRecordingId
            applySplitBackground(rec, isSelected)

            // ── Orphan (storage-offline) state ────────────────────────
            val isOrphan = rec.storageVolumeUuid in orphanVolumeUuids

            if (isOrphan) {
                btnInlinePlay.setImageResource(R.drawable.ic_storage_offline)
                btnInlinePlay.contentDescription =
                    itemView.context.getString(R.string.recording_cd_storage_offline)
                btnInlinePlay.imageTintList = ColorStateList.valueOf(
                    itemView.context.themeColor(R.attr.colorTextSecondary))
                itemView.alpha = 0.5f
                btnInlinePlay.setOnClickListener {
                    Snackbar.make(
                        itemView,
                        R.string.recording_msg_storage_not_connected,
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
                tvNewBadge.visibility = View.GONE
            } else {
                val isThisPlaying = rec.id == nowPlayingId && isPlaying
                btnInlinePlay.setImageResource(
                    if (isThisPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                btnInlinePlay.contentDescription = itemView.context.getString(
                    if (isThisPlaying) R.string.common_cd_pause else R.string.common_cd_play)
                btnInlinePlay.imageTintList = ColorStateList.valueOf(
                    itemView.context.themeColor(R.attr.colorAccent))
                itemView.alpha = 1f
                btnInlinePlay.setOnClickListener { onPlayPause(rec) }

                val isNew = System.currentTimeMillis() - rec.dbInsertedAt < NEW_THRESHOLD_MS
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
                menu.findItem(R.id.action_move)?.isVisible         = !isOrphan
                menu.findItem(R.id.action_topic_details)?.isVisible = showTopicDetails
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_rename        -> { showRenameDialog(rec); true }
                        R.id.action_move          -> { onMoveRequested(rec.id, rec.topicId); true }
                        R.id.action_delete        -> { showDeleteDialog(rec); true }
                        R.id.action_topic_details -> { onTopicDetailsRequested(rec.topicId); true }  // ← new
                        else                      -> false
                    }
                }
                show()
            }
        }

        // ── Accessibility ─────────────────────────────────────────────

        private fun setupAccessibilityActions(rec: RecordingEntity, isOrphan: Boolean) {
            // "Rename" — always available
            ViewCompat.addAccessibilityAction(
                itemView,
                itemView.context.getString(R.string.recording_cd_rename)
            ) { _, _ ->
                showRenameDialog(rec)
                true
            }
            // "Move to topic" — hidden for orphan recordings (storage offline)
            ViewCompat.addAccessibilityAction(
                itemView,
                itemView.context.getString(R.string.recording_cd_move_to_topic)
            ) { _, _ ->
                if (!isOrphan) onMoveRequested(rec.id, rec.topicId)
                true
            }
            // "Delete" — always available
            ViewCompat.addAccessibilityAction(
                itemView,
                itemView.context.getString(R.string.recording_cd_delete)
            ) { _, _ ->
                showDeleteDialog(rec)
                true
            }
            // Only register "Topic Details" where it's visible — keeps TalkBack
            // actions menu clean on Inbox and Topic Details contexts.
            if (showTopicDetails) {
                ViewCompat.addAccessibilityAction(
                    itemView,
                    itemView.context.getString(R.string.recording_cd_topic_details)
                ) { _, _ ->
                    onTopicDetailsRequested(rec.topicId); true
                }
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
                .setTitle(R.string.recording_dialog_rename_title)
                .setView(input)
                .setPositiveButton(R.string.common_btn_ok) { _, _ ->
                    val newTitle = input.text.toString().trim()
                    if (newTitle.isNotEmpty()) onRename(rec.id, newTitle)
                }
                .setNegativeButton(R.string.common_btn_cancel, null)
                .show()
        }

        private fun showDeleteDialog(rec: RecordingEntity) {
            AlertDialog.Builder(itemView.context)
                .setTitle(R.string.recording_dialog_delete_title)
                .setMessage(itemView.context.getString(R.string.recording_dialog_delete_message, rec.title))
                .setPositiveButton(R.string.common_btn_delete) { _, _ -> onDelete(rec) }
                .setNegativeButton(R.string.common_btn_cancel, null)
                .show()
        }

        // ── Playback Position Background Helpers ──────────────────────

        /**
         * Fast-path update for PAYLOAD_PROGRESS. Moves the split fraction without
         * touching any other views.  Falls back to a full bind if no drawable exists yet.
         */
        fun updateProgress(rec: RecordingEntity) {
            val bg = splitBg ?: run { bind(rec); return }
            bg.setFraction(computeFraction(rec))
        }

        /**
         * Sets [itemView]'s background to reflect both playback progress and
         * selection state.
         *
         * When vis is ENABLED:
         *   - All rows use [SplitBackgroundDrawable] so progress is always visible.
         *   - Selected rows additionally draw an accent-coloured border on top of
         *     the split, so the selection indicator survives the split background.
         *
         * When vis is DISABLED:
         *   - Selected rows get a flat [colorSurfaceElevated] background (previous
         *     behaviour, preserved for the no-vis case).
         *   - Unselected rows have no background (null).
         */
        private fun applySplitBackground(rec: RecordingEntity, isSelected: Boolean) {
            if (!playheadVisEnabled) {
                // Vis off: discard any split drawable and fall back to flat colour.
                splitBg = null
                if (isSelected) {
                    itemView.setBackgroundColor(
                        itemView.context.themeColor(R.attr.colorSurfaceElevated))
                } else {
                    itemView.background = null
                }
                return
            }

            // Vis on: SplitBackgroundDrawable handles everything, including the
            // selection border via its stroke fields.
            val context       = itemView.context
            val fraction      = computeFraction(rec)
            val (played, unplayed) = buildColors(context)
            val strokeColor   = if (isSelected) context.themeColor(R.attr.colorAccent)
            else Color.TRANSPARENT
            val strokeWidthPx = if (isSelected) 2f * context.resources.displayMetrics.density
            else 0f

            val existing = splitBg
            if (existing != null) {
                existing.playedColor   = played
                existing.unplayedColor = unplayed
                existing.strokeColor   = strokeColor
                existing.strokeWidthPx = strokeWidthPx
                existing.setFraction(fraction)
                // Drawable is already set as background; no re-set needed.
            } else {
                val bg = SplitBackgroundDrawable(
                    playedColor   = played,
                    unplayedColor = unplayed,
                    fraction      = fraction,
                    strokeColor   = strokeColor,
                    strokeWidthPx = strokeWidthPx,
                )
                splitBg = bg
                itemView.background = bg
            }
        }

        /**
         * Computes the display fraction for [rec].
         *
         * For the now-playing recording: uses the live [nowPlayingPositionMs] so the
         * split moves in real time without needing a DB value.
         *
         * For all others: delegates to PlaybackPositionHelper.displayFraction(), which
         * reads the DB-stored position and applies the near-end reset rules so a
         * recording that is effectively "done" shows as 0% (unplayed), exactly matching
         * what would happen if you tapped play on it.
         */
        private fun computeFraction(rec: RecordingEntity): Float {
            if (rec.durationMs <= 0L) return 0f
            return if (rec.id == nowPlayingId) {
                // nowPlayingPositionMs is always primed to the correct start position
                // before nowPlayingId triggers a rebind (see collector ordering above),
                // so no floor is needed and backward seeks reflect immediately.
                (nowPlayingPositionMs.toFloat() / rec.durationMs).coerceIn(0f, 1f)
            } else {
                prefs?.let { PlaybackPositionHelper.displayFraction(rec, it) } ?: 0f
            }
        }

        /**
         * Builds (playedColor, unplayedColor) for the split.
         *
         * The played region is colorAccent at alpha = intensity * 255, so the
         * intensity slider gives direct visual control without a second colour pref.
         * The unplayed region is always the natural item surface colour (transparent),
         * so the card/row background shows through unchanged.
         *
         * NOTE: tune these colours once you see them on device. You may find a
         * slightly tinted surface colour for the "unplayed" side looks better than
         * full transparency, especially in dark mode.
         */
        private fun buildColors(context: android.content.Context): Pair<Int, Int> {
            val accentRaw = context.themeColor(R.attr.colorAccent)

            // Apply intensity as alpha, leaving RGB unchanged.
            val alpha      = (playheadVisIntensity * 255f).toInt().coerceIn(0, 255)
            val playedColor = (accentRaw and 0x00FFFFFF) or (alpha shl 24)

            // Unplayed = fully transparent so the card surface colour shows through.
            val unplayedColor = android.graphics.Color.TRANSPARENT

            return Pair(playedColor, unplayedColor)
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