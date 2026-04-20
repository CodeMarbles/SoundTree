package app.soundtree.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Records a single event that occurred during a backup run.
 *
 * Events span a three-level severity spectrum:
 *
 *  - [EventType.INFO]    — milestone events emitted when verbose logging is
 *    enabled (see [app.soundtree.data.entities.BackupTargetEntity] and
 *    the verbose backup setting). Examples: WAL checkpoint complete,
 *    db/ directory resolved, pre-scan stats. Never written when verbose
 *    logging is off, keeping the table lean on storage-constrained devices.
 *
 *  - [EventType.WARNING] — the run continued but something was noteworthy;
 *    e.g. a file was skipped due to a transient read error while other
 *    files succeeded.
 *
 *  - [EventType.ERROR]   — a file or operation definitively failed.
 *
 * The parent [BackupLogEntity] row tracks aggregate counts ([filesFailed]
 * etc.) across all events; this table provides the per-item detail.
 * Both are cleared together when the user chooses to clear backup history.
 *
 * UI note: anywhere a count of "errors" is displayed to the user it should
 * count only WARNING + ERROR rows, not INFO rows.
 */
@Entity(
    tableName = "backup_log_events",
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
data class BackupLogEventEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** FK to the parent [BackupLogEntity.id]. Cascade-deleted with it. */
    @ColumnInfo(name = "log_id")
    val logId: Long,

    /**
     * Severity / type of this event. One of [EventType].
     * Stored as a string for readability in raw DB inspection.
     */
    @ColumnInfo(name = "severity")
    val severity: String,

    /**
     * Absolute path of the source file associated with this event,
     * e.g. "/storage/emulated/0/Android/data/app.soundtree/files/recordings/TC_20250330_143201.m4a".
     * Null for events not tied to a specific file (e.g. directory creation,
     * WAL checkpoint, pre-scan summary).
     */
    @ColumnInfo(name = "source_path")
    val sourcePath: String? = null,

    /**
     * Human-readable description of the event or error.
     * For INFO events: a concise milestone description.
     * For WARNING/ERROR events: what went wrong, sourced from the exception
     * message or a descriptive constant in BackupWorker.
     * Kept concise — full stack traces are not stored here.
     */
    @ColumnInfo(name = "error_message")
    val message: String,

    /** Epoch ms when this event was recorded. */
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long = System.currentTimeMillis(),
) {
    /** Sentinel values for the [severity] column. */
    object EventType {
        /**
         * Milestone event — only written when verbose backup logging is
         * enabled. Records structural steps such as directory creation,
         * WAL checkpoint result, and pre-scan statistics.
         */
        const val INFO    = "INFO"

        /**
         * The run continued; something was noteworthy but not fatal.
         * E.g. a file was skipped due to a transient read error while other
         * files succeeded.
         */
        const val WARNING = "WARNING"

        /**
         * A file or operation definitively failed.
         * Each ERROR event typically corresponds to one [filesFailed]
         * increment on the parent [BackupLogEntity].
         */
        const val ERROR   = "ERROR"
    }
}