package app.treecast.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.treecast.data.entities.BackupLogEntity
import app.treecast.data.entities.BackupLogEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupLogDao {

    // ── BackupLogEntity ───────────────────────────────────────────────────────

    /**
     * Inserts a new log row at the start of a backup run.
     * Returns the generated [BackupLogEntity.id] so BackupWorker can
     * reference it when writing [BackupLogEventEntity] child rows and
     * when updating the row on completion.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: BackupLogEntity): Long

    @Update
    suspend fun update(log: BackupLogEntity)

    /**
     * All log entries, newest first. Observed reactively by the UI to
     * display backup history.
     */
    @Query("SELECT * FROM backup_logs ORDER BY started_at DESC")
    fun getAll(): Flow<List<BackupLogEntity>>

    /**
     * Log entries for a specific backup target, newest first.
     * Used to populate per-target history in the Storage tab.
     */
    @Query("""
        SELECT * FROM backup_logs
        WHERE volume_uuid = :volumeUuid
        ORDER BY started_at DESC
    """)
    fun getByVolume(volumeUuid: String): Flow<List<BackupLogEntity>>

    /**
     * The most recent completed log entry for a given volume.
     * Used to populate the "Last backed up: …" label on target rows in
     * the Storage tab without observing the full history.
     */
    @Query("""
        SELECT * FROM backup_logs
        WHERE volume_uuid = :volumeUuid
          AND status IS NOT NULL
        ORDER BY started_at DESC
        LIMIT 1
    """)
    suspend fun getLastCompletedForVolume(volumeUuid: String): BackupLogEntity?

    /**
     * All log entries that are currently in-progress (status IS NULL), newest first.
     * Observed by MainViewModel to drive the progress card and title-bar strip.
     */
    @Query("SELECT * FROM backup_logs WHERE status IS NULL ORDER BY started_at DESC")
    fun getInProgressBackupLogs(): Flow<List<BackupLogEntity>>

    @Query("SELECT * FROM backup_logs WHERE id = :logId")
    fun observeById(logId: Long): Flow<BackupLogEntity?>

    /**
     * The most recent INFO event message for a given in-progress log entry.
     * Returns an empty list when verbose logging is off or no events have been
     * written yet. The caller maps the list to firstOrNull().
     */
    @Query("""
    SELECT error_message FROM backup_log_events
    WHERE log_id = :logId
      AND severity = 'INFO'
    ORDER BY occurred_at DESC
    LIMIT 1
""")
    fun getLatestInfoMessagesForLog(logId: Long): Flow<List<String>>

    /** One-shot read of all log entries, for export or bulk processing. */
    @Query("SELECT * FROM backup_logs ORDER BY started_at DESC")
    suspend fun getAllOnce(): List<BackupLogEntity>

    /**
     * Stamps the terminal fields on an in-progress log row.
     * Called by BackupWorker when the run concludes.
     */
    @Query("""
        UPDATE backup_logs
        SET ended_at      = :endedAt,
            status        = :status,
            error_message = :errorMessage
        WHERE id = :id
    """)
    suspend fun finalise(
        id: Long,
        endedAt: Long,
        status: String,
        errorMessage: String? = null,
    )

    /**
     * Updates the running stats columns on an in-progress log row.
     * BackupWorker calls this once after all copy passes complete.
     *
     * The aggregate files_* columns are kept in sync alongside the per-category
     * breakdown so pre-v13 rendering paths continue to work without changes.
     */
    @Query("""
        UPDATE backup_logs
        SET files_examined               = :filesExamined,
            files_copied                 = :filesCopied,
            files_skipped                = :filesSkipped,
            files_failed                 = :filesFailed,
            bytes_copied                 = :bytesCopied,
            total_recordings_on_source   = :totalOnSource,
            total_recordings_on_dest     = :totalOnDest,
            total_bytes_on_destination   = :totalBytesOnDest,
            db_backed_up                 = :dbBackedUp,
            recordings_copied            = :recordingsCopied,
            recordings_skipped           = :recordingsSkipped,
            recordings_failed            = :recordingsFailed,
            metadata_generated           = :metadataGenerated,
            metadata_skipped             = :metadataSkipped,
            metadata_failed              = :metadataFailed,
            waveforms_copied             = :waveformsCopied,
            waveforms_skipped            = :waveformsSkipped,
            waveforms_failed             = :waveformsFailed
        WHERE id = :id
    """)
    suspend fun updateStats(
        id: Long,
        // ── Aggregates (backward compat) ──────────────────────────────────────
        filesExamined: Int,
        filesCopied: Int,
        filesSkipped: Int,
        filesFailed: Int,
        bytesCopied: Long,
        totalOnSource: Int,
        totalOnDest: Int,
        totalBytesOnDest: Long,
        dbBackedUp: Boolean,
        // ── Per-category breakdown (v13+) ─────────────────────────────────────
        recordingsCopied: Int,
        recordingsSkipped: Int,
        recordingsFailed: Int,
        metadataGenerated: Int,
        metadataSkipped: Int,
        metadataFailed: Int,
        waveformsCopied: Int,
        waveformsSkipped: Int,
        waveformsFailed: Int,
    )

    // ── Live progress updates (v14+) ──────────────────────────────────────────
    //
    // Called by BackupWorker on every file during an active run.
    // Each query is deliberately narrow — only the columns that change within
    // the current phase are touched, keeping writes as cheap as possible.
    // The final consolidated updateStats() call in stepFlushStats() remains
    // authoritative for the log detail dialog; these are UI progress feeds only.

    /**
     * Signals which phase is currently executing. Called once at the top of
     * each step function so the UI can select the correct progress slice formula.
     *
     * Passing null clears the field (used if we ever want to mark "done" before
     * finalise() runs, though currently not called with null in the worker).
     */
    @Query("UPDATE backup_logs SET current_phase = :phase WHERE id = :id")
    suspend fun updatePhase(id: Long, phase: String?)

    /**
     * Persists per-file recording progress during [stepCopyRecordings].
     *
     * [totalBytesOnSource] is the same value on every call within a run, but
     * bundling it here avoids a separate round-trip at phase start and ensures
     * the UI always has a valid denominator alongside the numerator.
     */
    @Query("""
        UPDATE backup_logs
        SET bytes_copied            = :bytesCopied,
            files_examined          = :filesExamined,
            recordings_copied       = :copied,
            recordings_skipped      = :skipped,
            recordings_failed       = :failed,
            total_bytes_on_source   = :totalBytesOnSource
        WHERE id = :id
    """)
    suspend fun updateRecordingProgress(
        id                 : Long,
        bytesCopied        : Long,
        filesExamined      : Int,
        copied             : Int,
        skipped            : Int,
        failed             : Int,
        totalBytesOnSource : Long,
    )

    /**
     * Persists per-file metadata progress during [stepExportMetadata].
     *
     * [totalFiles] is [allRecordings.size], constant for the run; bundled here
     * for the same reason as [totalBytesOnSource] in [updateRecordingProgress].
     */
    @Query("""
        UPDATE backup_logs
        SET metadata_generated   = :generated,
            metadata_skipped     = :skipped,
            metadata_failed      = :failed,
            total_metadata_files = :totalFiles
        WHERE id = :id
    """)
    suspend fun updateMetadataProgress(
        id         : Long,
        generated  : Int,
        skipped    : Int,
        failed     : Int,
        totalFiles : Int,
    )

    /**
     * Persists per-file waveform progress during [stepSyncWaveforms].
     *
     * [totalFiles] is [wfmFiles.size], constant for the run.
     */
    @Query("""
        UPDATE backup_logs
        SET waveforms_copied     = :copied,
            waveforms_skipped    = :skipped,
            waveforms_failed     = :failed,
            total_waveform_files = :totalFiles
        WHERE id = :id
    """)
    suspend fun updateWaveformProgress(
        id         : Long,
        copied     : Int,
        skipped    : Int,
        failed     : Int,
        totalFiles : Int,
    )

    // ── Log clearing ──────────────────────────────────────────────────────────

    /** Deletes all log entries (and their child event rows via CASCADE). */
    @Query("DELETE FROM backup_logs")
    suspend fun clearAll()

    /**
     * Deletes log entries for a specific volume (and child event rows via CASCADE).
     * Called when the user removes a backup target and opts to clear its history.
     */
    @Query("DELETE FROM backup_logs WHERE volume_uuid = :volumeUuid")
    suspend fun clearForVolume(volumeUuid: String)

    // ── BackupLogEventEntity ──────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: BackupLogEventEntity)

    /** Bulk insert — BackupWorker may accumulate events and flush them together. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<BackupLogEventEntity>)

    /**
     * All event rows for a given log entry, oldest first.
     * Used to populate the detail view for a specific backup run.
     * Includes INFO, WARNING, and ERROR severity rows.
     */
    @Query("""
        SELECT * FROM backup_log_events
        WHERE log_id = :logId
        ORDER BY occurred_at ASC
    """)
    fun getEventsForLog(logId: Long): Flow<List<BackupLogEventEntity>>

    /** One-shot variant for use outside of UI observation. */
    @Query("""
        SELECT * FROM backup_log_events
        WHERE log_id = :logId
        ORDER BY occurred_at ASC
    """)
    suspend fun getEventsForLogOnce(logId: Long): List<BackupLogEventEntity>

    /**
     * WARNING + ERROR rows only for a given log entry, oldest first.
     * Use this when computing user-visible error counts or rendering
     * a summary that should not include INFO milestone rows.
     */
    @Query("""
        SELECT * FROM backup_log_events
        WHERE log_id = :logId
          AND severity IN ('WARNING', 'ERROR')
        ORDER BY occurred_at ASC
    """)
    fun getProblemsForLog(logId: Long): Flow<List<BackupLogEventEntity>>

    /** One-shot variant of [getProblemsForLog]. */
    @Query("""
        SELECT * FROM backup_log_events
        WHERE log_id = :logId
          AND severity IN ('WARNING', 'ERROR')
        ORDER BY occurred_at ASC
    """)
    suspend fun getProblemsForLogOnce(logId: Long): List<BackupLogEventEntity>
}