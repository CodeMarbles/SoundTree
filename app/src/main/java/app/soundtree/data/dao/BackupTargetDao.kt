package app.soundtree.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.soundtree.data.entities.BackupTargetEntity
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

    /** Returns the target with this surrogate [id], or null if not found. */
    @Query("SELECT * FROM backup_targets WHERE id = :id")
    suspend fun getById(id: Long): BackupTargetEntity?

    /**
     * Returns the target whose [BackupTargetEntity.backupDirUri] matches [dirUri],
     * or null if no target uses that directory.
     *
     * Used by [SoundTreeRepository.getOrCreateManualBackupTarget] to detect
     * duplicate SAF directory selections before attempting an insert, preventing
     * the UNIQUE constraint violation that would otherwise occur on the subsequent
     * [setBackupDirUri] UPDATE.
     */
    @Query("SELECT * FROM backup_targets WHERE backup_dir_uri = :dirUri LIMIT 1")
    suspend fun getByDirUri(dirUri: String): BackupTargetEntity?

    /**
     * Returns all targets whose [BackupTargetEntity.volumeUuid] matches.
     * Returns a list because after the v16 refactor multiple targets may share
     * the same volume UUID (each pointing to a different SAF directory).
     *
     * Returns an empty list when no targets are configured for this volume.
     */
    @Query("SELECT * FROM backup_targets WHERE volume_uuid = :volumeUuid")
    suspend fun getByVolumeUuid(volumeUuid: String): List<BackupTargetEntity>

    /**
     * All targets with on-connect backups enabled.
     * Called by [app.soundtree.receiver.StorageMountReceiver] to collect every
     * target that should fire when any volume mounts. The receiver filters the
     * result by the mounted volume's UUID.
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
     * Inserts a new backup target. Returns the generated [BackupTargetEntity.id].
     *
     * IGNORE is used instead of REPLACE so that accidental duplicate inserts
     * (same backup_dir_uri, which carries a UNIQUE constraint) are silently
     * dropped rather than re-minting a new id. Callers should check the return
     * value — -1L means the insert was ignored due to a conflict.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(target: BackupTargetEntity): Long

    @Update
    suspend fun update(target: BackupTargetEntity)

    @Delete
    suspend fun delete(target: BackupTargetEntity)

    @Query("DELETE FROM backup_targets WHERE id = :id")
    suspend fun deleteById(id: Long)

    // ── Targeted field updates (all keyed by surrogate id) ────────────────────

    /**
     * Caches the OS-provided display label for this target.
     * Called when a target is first added and after each successful backup run.
     */
    @Query("UPDATE backup_targets SET volume_label = :label WHERE id = :id")
    suspend fun setVolumeLabel(id: Long, label: String)

    @Query("UPDATE backup_targets SET on_connect_enabled = :enabled WHERE id = :id")
    suspend fun setOnConnectEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE backup_targets SET scheduled_enabled = :enabled WHERE id = :id")
    suspend fun setScheduledEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE backup_targets SET interval_hours = :hours WHERE id = :id")
    suspend fun setIntervalHours(id: Long, hours: Int)

    @Query("UPDATE backup_targets SET backup_dir_uri = :uri WHERE id = :id")
    suspend fun setBackupDirUri(id: Long, uri: String)

    /** Stamped by BackupWorker on successful completion. */
    @Query("UPDATE backup_targets SET last_backup_at = :epochMs WHERE id = :id")
    suspend fun setLastBackupAt(id: Long, epochMs: Long)

    @Query("UPDATE backup_targets SET backup_preferences = :enabled WHERE id = :id")
    suspend fun setBackupPreferences(id: Long, enabled: Boolean)

    @Query("UPDATE backup_targets SET export_metadata_enabled = :enabled WHERE id = :id")
    suspend fun setExportMetadataEnabled(id: Long, enabled: Boolean)
}