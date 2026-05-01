package app.soundtree.ui.restore

import app.soundtree.util.DatabaseRestoreManager

// ─────────────────────────────────────────────────────────────────────────────
// RestoreListItem.kt
//
// Item types for the RecyclerView on the restore wizard's progress screen.
// The adapter holds a flat list of these; SectionHeader items act as visual
// dividers that also carry the section's running counters and collapsed state.
// ─────────────────────────────────────────────────────────────────────────────

/** Categories of files that the restore copies, in display order. */
enum class FileCategory(val displayName: String) {
    RECORDINGS("Recordings"),
    WAVEFORMS("Waveforms"),
}

/**
 * Cumulative outcome counters for a single file category.
 * Immutable — a new instance is produced each time any counter changes.
 */
data class FileCounters(
    val copied: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
) {
    val total: Int get() = copied + skipped + failed
    val hasActivity: Boolean get() = total > 0
}

/** A single file-copy event in the log. */
data class FileLogEntry(
    val category: FileCategory,
    val type: DatabaseRestoreManager.FileEventType,
    val filename: String,
)

/**
 * The active filter applied across both RecyclerView sections.
 *
 * ALL              — show COPIED, SKIPPED, and FAILED entries
 * WRITES_AND_FAILS — show COPIED and FAILED; omit SKIPPED
 * FAILURES_ONLY    — show only FAILED entries
 */
enum class RestoreLogFilter { ALL, WRITES_AND_FAILS, FAILURES_ONLY }

/** Returns true if this entry should be visible under [filter]. */
fun FileLogEntry.visibleUnder(filter: RestoreLogFilter): Boolean = when (filter) {
    RestoreLogFilter.ALL              -> true
    RestoreLogFilter.WRITES_AND_FAILS ->
        type != DatabaseRestoreManager.FileEventType.SKIPPED
    RestoreLogFilter.FAILURES_ONLY   ->
        type == DatabaseRestoreManager.FileEventType.FAILED
}

// ── RecyclerView item types ───────────────────────────────────────────────────

sealed class RestoreListItem {

    /**
     * Section divider row for a file category.
     *
     * [isRunning]   — true while the restore is actively processing this category;
     *                 drives a small indeterminate spinner in the header.
     * [isComplete]  — true once the category's copy pass has finished.
     * [isCollapsed] — controlled by the adapter; toggled on header tap.
     */
    data class SectionHeader(
        val category: FileCategory,
        val counts: FileCounters,
        val isRunning: Boolean,
        val isComplete: Boolean,
        val isCollapsed: Boolean,
    ) : RestoreListItem()

    /** A single file-copy event row, shown/hidden based on filter + collapse state. */
    data class FileEntry(
        val entry: FileLogEntry,
    ) : RestoreListItem()
}