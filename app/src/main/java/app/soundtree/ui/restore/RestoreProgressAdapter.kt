package app.soundtree.ui.restore

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.soundtree.R
import app.soundtree.util.DatabaseRestoreManager

// ─────────────────────────────────────────────────────────────────────────────
// RestoreProgressAdapter.kt
//
// ListAdapter for the file-event log on the restore wizard's progress screen.
//
// Item list is rebuilt via submitList() whenever RestorePhase.Running changes.
// The caller (RestoreWizardDialogFragment) calls buildItems() to produce the
// flat list from the current phase state + adapter-local collapsed flags.
//
// Collapsed state is stored per-category inside the adapter so it survives
// StateFlow re-emissions without resetting on every file event.
// ─────────────────────────────────────────────────────────────────────────────

class RestoreProgressAdapter : ListAdapter<RestoreListItem, RecyclerView.ViewHolder>(DIFF) {

    // ── Filter and collapse state (adapter-local) ─────────────────────────────

    var activeFilter: RestoreLogFilter = RestoreLogFilter.ALL
        private set

    // Per-category collapse flag. Sections start expanded; they auto-collapse
    // when their copy pass completes with zero failures (see buildItems).
    private val collapsedSections = mutableSetOf<FileCategory>()

    fun setFilter(filter: RestoreLogFilter) {
        activeFilter = filter
        // Caller must re-submit the item list after changing the filter.
    }

    fun isCollapsed(category: FileCategory): Boolean =
        category in collapsedSections

    fun toggleCollapse(category: FileCategory) {
        if (category in collapsedSections) collapsedSections.remove(category)
        else collapsedSections.add(category)
    }

    /**
     * Auto-collapses [category] if it has just completed with zero failures.
     * Called by the fragment when a section transitions to isComplete == true.
     * No-op if the section already has failures (keep it expanded for audit).
     */
    fun autoCollapseIfClean(category: FileCategory, counts: FileCounters) {
        if (counts.failed == 0) collapsedSections.add(category)
    }

    // ── Item list construction ────────────────────────────────────────────────

    /**
     * Builds the flat item list from phase state and adapter-local collapse flags.
     * Call this whenever phase state changes, then pass the result to submitList().
     *
     * Each section contributes:
     *   - One SectionHeader (always present once the category has started)
     *   - Zero or more FileEntry rows (omitted if section is collapsed, or if the
     *     entry type is hidden by the active filter)
     */
    fun buildItems(
        recordingEvents: List<FileLogEntry>,
        recordingCounts: FileCounters,
        recordingsRunning: Boolean,
        recordingsComplete: Boolean,
        waveformEvents: List<FileLogEntry>,
        waveformCounts: FileCounters,
        waveformsRunning: Boolean,
        waveformsComplete: Boolean,
    ): List<RestoreListItem> {
        val items = mutableListOf<RestoreListItem>()

        // Recordings section — only emit once we have at least one event or the
        // phase is actively running, so the header doesn't flicker in prematurely.
        if (recordingCounts.hasActivity || recordingsRunning) {
            items += RestoreListItem.SectionHeader(
                category    = FileCategory.RECORDINGS,
                counts      = recordingCounts,
                isRunning   = recordingsRunning,
                isComplete  = recordingsComplete,
                isCollapsed = isCollapsed(FileCategory.RECORDINGS),
            )
            if (!isCollapsed(FileCategory.RECORDINGS)) {
                recordingEvents
                    .filter { it.visibleUnder(activeFilter) }
                    .mapTo(items) { RestoreListItem.FileEntry(it) }
            }
        }

        // Waveforms section — same guard.
        if (waveformCounts.hasActivity || waveformsRunning) {
            items += RestoreListItem.SectionHeader(
                category    = FileCategory.WAVEFORMS,
                counts      = waveformCounts,
                isRunning   = waveformsRunning,
                isComplete  = waveformsComplete,
                isCollapsed = isCollapsed(FileCategory.WAVEFORMS),
            )
            if (!isCollapsed(FileCategory.WAVEFORMS)) {
                waveformEvents
                    .filter { it.visibleUnder(activeFilter) }
                    .mapTo(items) { RestoreListItem.FileEntry(it) }
            }
        }

        return items
    }

    // ── ViewHolder types ──────────────────────────────────────────────────────

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ENTRY  = 1

        private val DIFF = object : DiffUtil.ItemCallback<RestoreListItem>() {
            override fun areItemsTheSame(a: RestoreListItem, b: RestoreListItem) =
                when {
                    a is RestoreListItem.SectionHeader && b is RestoreListItem.SectionHeader ->
                        a.category == b.category
                    a is RestoreListItem.FileEntry && b is RestoreListItem.FileEntry ->
                        a.entry.filename == b.entry.filename && a.entry.category == b.entry.category
                    else -> false
                }

            override fun areContentsTheSame(a: RestoreListItem, b: RestoreListItem) = a == b
        }
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is RestoreListItem.SectionHeader -> TYPE_HEADER
        is RestoreListItem.FileEntry     -> TYPE_ENTRY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(
                inflater.inflate(R.layout.item_restore_section_header, parent, false)
            )
            else -> EntryViewHolder(
                inflater.inflate(R.layout.item_restore_file_entry, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is RestoreListItem.SectionHeader -> (holder as HeaderViewHolder).bind(item)
            is RestoreListItem.FileEntry     -> (holder as EntryViewHolder).bind(item)
        }
    }

    // ── HeaderViewHolder ──────────────────────────────────────────────────────

    inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvCategory: TextView   = view.findViewById(R.id.tvSectionCategory)
        private val tvCounts: TextView     = view.findViewById(R.id.tvSectionCounts)
        private val spinner: ProgressBar   = view.findViewById(R.id.sectionSpinner)
        private val ivChevron: ImageView   = view.findViewById(R.id.ivSectionChevron)

        fun bind(item: RestoreListItem.SectionHeader) {
            tvCategory.text = item.category.displayName

            // Counts summary: "12 copied · 5 skipped · 0 failed"
            // Only shown once there is activity; before that a subtle "…" keeps
            // the header from looking empty during the indeterminate ramp-up.
            tvCounts.text = if (item.counts.hasActivity) {
                "${item.counts.copied} copied · " +
                        "${item.counts.skipped} skipped · " +
                        "${item.counts.failed} failed"
            } else {
                "…"
            }

            // Spinner visible while running, hidden once complete.
            spinner.isVisible  = item.isRunning
            ivChevron.isVisible = item.isComplete || !item.isRunning

            // Chevron direction reflects collapsed state.
            ivChevron.rotation = if (item.isCollapsed) -90f else 0f

            // Tapping the header toggles collapse and re-submits the item list.
            // The lambda is set here; the fragment owns the re-submit logic.
            itemView.setOnClickListener {
                toggleCollapse(item.category)
                // Notify the fragment to rebuild and re-submit.
                onHeaderTapped?.invoke(item.category)
            }
        }
    }

    /** Called by the fragment to trigger a list rebuild when a header is tapped. */
    var onHeaderTapped: ((FileCategory) -> Unit)? = null

    // ── EntryViewHolder ───────────────────────────────────────────────────────

    inner class EntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvGlyph: TextView    = view.findViewById(R.id.tvEntryGlyph)
        private val tvFilename: TextView = view.findViewById(R.id.tvEntryFilename)

        fun bind(item: RestoreListItem.FileEntry) {
            val ctx = itemView.context
            when (item.entry.type) {
                DatabaseRestoreManager.FileEventType.COPIED -> {
                    tvGlyph.text      = "✓"
                    tvGlyph.setTextColor(ContextCompat.getColor(ctx, R.color.restore_log_copied))
                }
                DatabaseRestoreManager.FileEventType.SKIPPED -> {
                    tvGlyph.text      = "–"
                    tvGlyph.setTextColor(ContextCompat.getColor(ctx, R.color.restore_log_skipped))
                }
                DatabaseRestoreManager.FileEventType.FAILED -> {
                    tvGlyph.text      = "✗"
                    tvGlyph.setTextColor(ContextCompat.getColor(ctx, R.color.restore_log_failed))
                }
            }
            tvFilename.text = item.entry.filename
        }
    }
}