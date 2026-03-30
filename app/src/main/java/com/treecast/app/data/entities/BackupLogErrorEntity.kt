package com.treecast.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Records a single per-file incident (failure or warning) that occurred
 * during a backup run.
 *
 * Rows are written only for files that encountered a problem — files that
 * copied cleanly or were skipped as up-to-date generate no child rows.
 * This keeps the table from becoming log spam while still giving the user
 * (and future diagnostic tooling) precise detail on what went wrong.
 *
 * The parent [BackupLogEntity] row tracks aggregate counts ([filesFailed]
 * etc.) across all incidents; this table provides the per-item detail.
 * Both are cleared together when the user chooses to clear backup history.
 */
@Entity(
    tableName = "backup_log_errors",
    foreignKeys = [
        ForeignKey(
            entity = BackupLogEntity::class,
            parentColumns = ["id"],
            childColumns = ["log_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("log_id")]
)
data class BackupLogErrorEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** FK to the parent [BackupLogEntity.id]. Cascade-deleted with it. */
    @ColumnInfo(name = "log_id")
    val logId: Long,

    /**
     * Severity of this incident. One of [ErrorSeverity].
     * Stored as a string for readability in raw DB inspection.
     *
     * [ErrorSeverity.WARNING] — the run continued; e.g. a file was skipped
     * due to a transient read error but other files succeeded.
     * [ErrorSeverity.ERROR] — this file definitively failed to back up.
     */
    @ColumnInfo(name = "severity")
    val severity: String,

    /**
     * Absolute path of the source file that caused this incident,
     * e.g. "/storage/emulated/0/Android/data/com.treecast.app/files/recordings/TC_20250330_143201.m4a".
     * Null for incidents not tied to a specific file (e.g. destination
     * directory creation failure).
     */
    @ColumnInfo(name = "source_path")
    val sourcePath: String? = null,

    /**
     * Human-readable description of what went wrong.
     * Sourced from the exception message or a descriptive constant in
     * BackupWorker. Kept concise — full stack traces are not stored here.
     */
    @ColumnInfo(name = "error_message")
    val errorMessage: String,

    /** Epoch ms when this incident was recorded. */
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long = System.currentTimeMillis(),
) {
    /** Sentinel values for the [severity] column. */
    object ErrorSeverity {
        const val WARNING = "WARNING"
        const val ERROR   = "ERROR"
    }
}