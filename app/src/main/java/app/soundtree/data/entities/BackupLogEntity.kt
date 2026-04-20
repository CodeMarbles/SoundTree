package app.soundtree.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Records a single backup run to one [BackupTargetEntity] volume.
 *
 * Each row is created when a backup starts and updated in-place as the run
 * progresses. [BackupLogEventEntity] rows referencing this row's [id] are
 * written for any per-file failure or warning encountered during the run.
 *
 * ## Denormalization
 * [volumeUuid], [volumeLabel], and [backupDirUri] duplicate data from
 * [BackupTargetEntity] intentionally. The log is a historical record — if
 * the user later removes a target or changes the directory, old entries
 * remain fully readable without a join. The FK [backupTargetUuid] is
 * nullable and set to NULL on target deletion for the same reason.
 *
 * ## Stats model
 * Stats are split into two groups:
 *  - **Totals** — the state of the destination at the end of this run
 *    (how many recordings exist there, how many bytes).
 *  - **Deltas** — what this specific run did (copied, skipped, failed).
 */
@Entity(
    tableName = "backup_logs",
    foreignKeys = [
        ForeignKey(
            entity = BackupTargetEntity::class,
            parentColumns = ["volume_uuid"],
            childColumns = ["backup_target_uuid"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("backup_target_uuid")]
)
data class BackupLogEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    // ── Provenance ────────────────────────────────────────────────────────────

    /**
     * FK to [BackupTargetEntity.volumeUuid]. Nullable — set to NULL if the
     * target is removed so the log entry is preserved rather than cascade-deleted.
     */
    @ColumnInfo(name = "backup_target_uuid")
    val backupTargetUuid: String?,

    /** Denormalized copy of the volume UUID at time of backup. */
    @ColumnInfo(name = "volume_uuid")
    val volumeUuid: String,

    /** Denormalized human-readable volume label at time of backup. */
    @ColumnInfo(name = "volume_label")
    val volumeLabel: String,

    /** Denormalized SAF URI of the destination directory at time of backup. */
    @ColumnInfo(name = "backup_dir_uri")
    val backupDirUri: String,

    /**
     * What initiated this backup run. One of [BackupTrigger].
     * Stored as a string for readability in raw DB inspection.
     */
    @ColumnInfo(name = "trigger")
    val trigger: String,

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Epoch ms when the backup run started. */
    @ColumnInfo(name = "started_at")
    val startedAt: Long = System.currentTimeMillis(),

    /**
     * Epoch ms when the run completed (succeeded, failed, or partial).
     * Null while the run is still in progress.
     * Duration = [endedAt] - [startedAt].
     */
    @ColumnInfo(name = "ended_at")
    val endedAt: Long? = null,

    /**
     * Terminal outcome of the run. One of [BackupStatus].
     * Stored as a string for readability in raw DB inspection.
     * Null while the run is still in progress.
     */
    @ColumnInfo(name = "status")
    val status: String? = null,

    /**
     * Top-level error message if [status] is [BackupStatus.FAILED].
     * Null for success or partial runs — per-item errors live in
     * [BackupLogEventEntity].
     */
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    // ── Database backup ───────────────────────────────────────────────────────

    /** True if the database file was successfully copied this run. */
    @ColumnInfo(name = "db_backed_up")
    val dbBackedUp: Boolean = false,

    /**
     * True if app preferences were included in this backup.
     * Stubbed — always false until the preferences backup feature is
     * implemented. Mirrors [BackupTargetEntity.backupPreferences].
     */
    @ColumnInfo(name = "preferences_backed_up")
    val preferencesBackedUp: Boolean = false,

    // ── Delta stats (what this run did) ───────────────────────────────────────

    /** Number of source recording files examined during sync. */
    @ColumnInfo(name = "files_examined")
    val filesExamined: Int = 0,

    /** Files copied to the destination this run (new or changed). */
    @ColumnInfo(name = "files_copied")
    val filesCopied: Int = 0,

    /**
     * Files already present on the destination and up to date — no action
     * taken. filesExamined = filesCopied + filesSkipped + filesFailed.
     */
    @ColumnInfo(name = "files_skipped")
    val filesSkipped: Int = 0,

    /**
     * Files that were attempted but failed. Each failed file has a
     * corresponding [BackupLogEventEntity] row with details.
     */
    @ColumnInfo(name = "files_failed")
    val filesFailed: Int = 0,

    /** Total bytes written to the destination this run. */
    @ColumnInfo(name = "bytes_copied")
    val bytesCopied: Long = 0L,

    // ── Per-category delta stats (v13+) ───────────────────────────────────────
    //
    // Break the aggregate files_* columns into per-category counters so the
    // detail screen can show "recordings / metadata / waveforms" rows rather
    // than a single opaque total.
    //
    // Invariant (checkable per category):
    //   *_copied + *_skipped + *_failed == files_examined  (recordings only)
    //
    // Pre-v13 rows will have all six fields as 0. The UI must treat all-zero
    // as "no breakdown available" and fall back to the legacy aggregate display.

    /** Audio .m4a files successfully copied to the backup destination this run. */
    @ColumnInfo(name = "recordings_copied")
    val recordingsCopied: Int = 0,

    /**
     * Audio .m4a files skipped because an identical copy (same name + size)
     * already existed on the destination.
     */
    @ColumnInfo(name = "recordings_skipped")
    val recordingsSkipped: Int = 0,

    /** Audio .m4a files attempted but failed to copy. */
    @ColumnInfo(name = "recordings_failed")
    val recordingsFailed: Int = 0,

    /**
     * Companion .json metadata sidecar files written (generated) this run.
     * "Generated" rather than "copied" because the file is always rebuilt
     * from live DB data rather than transferred from a source location.
     */
    @ColumnInfo(name = "metadata_generated")
    val metadataGenerated: Int = 0,

    /**
     * Metadata sidecars skipped because the destination copy was already
     * up to date (mtime / size match, or metadata_updated_at unchanged).
     */
    @ColumnInfo(name = "metadata_skipped")
    val metadataSkipped: Int = 0,

    /** Metadata sidecar writes that failed. */
    @ColumnInfo(name = "metadata_failed")
    val metadataFailed: Int = 0,

    /** Waveform .wfm cache files successfully copied to the backup destination. */
    @ColumnInfo(name = "waveforms_copied")
    val waveformsCopied: Int = 0,

    /**
     * Waveform cache files skipped because an identical copy already existed
     * on the destination. This is the normal steady-state outcome on most runs.
     */
    @ColumnInfo(name = "waveforms_skipped")
    val waveformsSkipped: Int = 0,

    /** Waveform cache files attempted but failed to copy. */
    @ColumnInfo(name = "waveforms_failed")
    val waveformsFailed: Int = 0,

    // ── Total stats (state of destination after this run) ─────────────────────

    /** Total recording files present on the source at time of backup. */
    @ColumnInfo(name = "total_recordings_on_source")
    val totalRecordingsOnSource: Int = 0,

    /** Total recording files present on the destination after this run. */
    @ColumnInfo(name = "total_recordings_on_dest")
    val totalRecordingsOnDest: Int = 0,

    /** Total bytes occupied by recordings on the destination after this run. */
    @ColumnInfo(name = "total_bytes_on_destination")
    val totalBytesOnDestination: Long = 0L,

    // ── Live progress fields (v14+) ───────────────────────────────────────────
    //
    // Written by BackupWorker during an active run so the UI can render
    // meaningful phase-aware progress without relying on the prior run's
    // destination totals as a proxy.
    //
    // All four default to 0/null. Pre-v14 completed rows leave them at
    // defaults, which the UI treats as "no phase data — show indeterminate".

    /**
     * The phase currently executing. One of "DB", "RECORDINGS", "METADATA",
     * "WAVEFORMS". Null before the first step starts or once the run finalises.
     * Used by the progress card to pick the correct slice formula.
     */
    @ColumnInfo(name = "current_phase")
    val currentPhase: String? = null,

    /**
     * Total bytes of all source `.m4a` recording files, computed once at the
     * start of [stepCopyRecordings] before the copy loop begins.
     * Denominator for the RECORDINGS slice (10–75 %) of the progress bar.
     */
    @ColumnInfo(name = "total_bytes_on_source")
    val totalBytesOnSource: Long = 0L,

    /**
     * Total number of recordings to process during [stepExportMetadata].
     * Written at the start of that step; denominator for the METADATA slice
     * (75–88 %). Zero when metadata export is disabled for this target.
     */
    @ColumnInfo(name = "total_metadata_files")
    val totalMetadataFiles: Int = 0,

    /**
     * Total number of `.wfm` files found during [stepSyncWaveforms].
     * Written at the start of that step; denominator for the WAVEFORMS slice
     * (88–100 %).
     */
    @ColumnInfo(name = "total_waveform_files")
    val totalWaveformFiles: Int = 0,
) {
    /** Sentinel values for the [trigger] column. */
    object BackupTrigger {
        const val ON_CONNECT = "ON_CONNECT"
        const val SCHEDULED  = "SCHEDULED"
        const val MANUAL     = "MANUAL"
    }

    /** Sentinel values for the [status] column. */
    object BackupStatus {
        /** All operations completed without error. */
        const val SUCCESS = "SUCCESS"
        /** Completed but one or more files failed — see BackupLogErrorEntity. */
        const val PARTIAL = "PARTIAL"
        /** Run aborted by a fatal error — see [errorMessage]. */
        const val FAILED  = "FAILED"
    }
}