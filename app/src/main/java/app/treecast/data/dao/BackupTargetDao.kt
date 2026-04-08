package app.treecast.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.treecast.data.entities.BackupTargetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupTargetDao {

    // ── Reads ─────────────────────────────────────────────────────────────────

    /** All configured backup targets — observed reactively by the Storage tab. */
    @Query("SELECT * FROM backup_targets")
    fun getAll(): Flow<List<BackupTargetEntity>>

    /** One-shot read of all targets, e.g. for use inside WorkManager jobs. */
    @Query("SELECT * FROM backup_targets")
    suspend fun getAllOnce(): List<BackupTargetEntity>

    /** Returns the target for [volumeUuid], or null if not designated. */
    @Query("SELECT * FROM backup_targets WHERE volume_uuid = :volumeUuid")
    suspend fun getByUuid(volumeUuid: String): BackupTargetEntity?

    /**
     * All targets with on-connect backups enabled.
     * Called by [app.treecast.receiver.StorageMountReceiver] to decide
     * whether a newly-mounted volume should trigger a backup job.
     */
    @Query("SELECT * FROM backup_targets WHERE on_connect_enabled = 1")
    suspend fun getOnConnectTargets(): List<BackupTargetEntity>

    /**
     * All targets with scheduled backups enabled.
     * Used on app startup to reconcile live WorkManager periodic requests
     * against the stored configuration.
     */
    @Query("SELECT * FROM backup_targets WHERE scheduled_enabled = 1")
    suspend fun getScheduledTargets(): List<BackupTargetEntity>

    // ── Writes ────────────────────────────────────────────────────────────────

    /**
     * Inserts a new backup target. Using REPLACE so that if the user removes
     * and re-adds the same volume UUID the row is cleanly overwritten rather
     * than throwing a constraint violation.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(target: BackupTargetEntity)

    @Update
    suspend fun update(target: BackupTargetEntity)

    @Delete
    suspend fun delete(target: BackupTargetEntity)

    @Query("DELETE FROM backup_targets WHERE volume_uuid = :volumeUuid")
    suspend fun deleteByUuid(volumeUuid: String)

    @Query("UPDATE backup_targets SET export_metadata_enabled = :enabled WHERE volume_uuid = :volumeUuid")
    suspend fun setExportMetadataEnabled(volumeUuid: String, enabled: Boolean)

    // ── Targeted field updates ────────────────────────────────────────────────

    /**
     * Caches the OS-provided display label for this volume.
     * Called when a target is first added and after each successful backup run,
     * so the label remains readable in Settings even when the drive is away.
     */
    @Query("UPDATE backup_targets SET volume_label = :label WHERE volume_uuid = :volumeUuid")
    suspend fun setVolumeLabel(volumeUuid: String, label: String)

    @Query("UPDATE backup_targets SET on_connect_enabled = :enabled WHERE volume_uuid = :volumeUuid")
    suspend fun setOnConnectEnabled(volumeUuid: String, enabled: Boolean)

    @Query("UPDATE backup_targets SET scheduled_enabled = :enabled WHERE volume_uuid = :volumeUuid")
    suspend fun setScheduledEnabled(volumeUuid: String, enabled: Boolean)

    @Query("UPDATE backup_targets SET interval_hours = :hours WHERE volume_uuid = :volumeUuid")
    suspend fun setIntervalHours(volumeUuid: String, hours: Int)

    @Query("UPDATE backup_targets SET backup_dir_uri = :uri WHERE volume_uuid = :volumeUuid")
    suspend fun setBackupDirUri(volumeUuid: String, uri: String)

    /** Stamped by BackupWorker on successful completion. */
    @Query("UPDATE backup_targets SET last_backup_at = :epochMs WHERE volume_uuid = :volumeUuid")
    suspend fun setLastBackupAt(volumeUuid: String, epochMs: Long)

    @Query("UPDATE backup_targets SET backup_preferences = :enabled WHERE volume_uuid = :volumeUuid")
    suspend fun setBackupPreferences(volumeUuid: String, enabled: Boolean)
}