package com.treecast.app.data.repository

import android.content.Context
import com.treecast.app.data.dao.VolumeUsage
import com.treecast.app.data.db.AppDatabase
import com.treecast.app.data.entities.BackupLogEntity
import com.treecast.app.data.entities.BackupLogErrorEntity
import com.treecast.app.data.entities.BackupTargetEntity
import com.treecast.app.data.entities.MarkEntity
import com.treecast.app.data.entities.RecordingEntity
import com.treecast.app.data.entities.TopicEntity
import com.treecast.app.util.Icons
import com.treecast.app.util.StorageVolumeHelper
import com.treecast.app.worker.BackupWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class TreeCastRepository(context: Context) {

    private val db            = AppDatabase.getInstance(context)
    private val topicDao      = db.topicDao()
    private val recordingDao  = db.recordingDao()
    private val markDao       = db.markDao()
    private val backupTargetDao = db.backupTargetDao()
    private val backupLogDao    = db.backupLogDao()

    // Keep a context reference for WorkManager calls in backup target mutations.
    private val appContext = context.applicationContext

    suspend fun getTotalRecordingTime(): Long = recordingDao.getTotalDurationMs()

    // ── Topics ────────────────────────────────────────────────────────────────

    suspend fun createTopic(
        name: String,
        parentId: Long? = null,
        icon: String = Icons.DEFAULT_TOPIC,
        color: String = "#6C63FF"
    ): Long = topicDao.insert(TopicEntity(name = name, parentId = parentId, icon = icon, color = color))

    suspend fun updateTopic(topic: TopicEntity) = topicDao.update(topic)
    suspend fun deleteTopic(topic: TopicEntity) = topicDao.delete(topic)
    fun getAllTopics(): Flow<List<TopicEntity>> = topicDao.getAllTopics()
    suspend fun topicExists(id: Long): Boolean = topicDao.getById(id) != null

    // ── Recordings ────────────────────────────────────────────────────────────

    suspend fun saveRecording(
        filePath: String,
        durationMs: Long,
        fileSizeBytes: Long,
        title: String,
        topicId: Long? = null
    ): Long = recordingDao.insert(
        RecordingEntity(
            filePath      = filePath,
            durationMs    = durationMs,
            fileSizeBytes = fileSizeBytes,
            title         = title,
            topicId       = topicId
        )
    )

    /**
     * Per-volume storage usage, live from the DB.
     * Delegated directly to [RecordingDao.getStorageUsageByVolume].
     * The ViewModel turns this into a [StateFlow<Map<String, Long>>].
     */
    fun getStorageUsageByVolume(): Flow<List<VolumeUsage>> =
        recordingDao.getStorageUsageByVolume()

    /**
     * All recordings on a given storage volume.
     * Used to identify orphaned recordings when a volume is unmounted.
     */
    fun getRecordingsByVolume(uuid: String): Flow<List<RecordingEntity>> =
        recordingDao.getByVolume(uuid)

    /**
     * Saves a recording and atomically flushes any marks dropped during
     * that recording session. The recording row is inserted first to
     * obtain its ID, then all mark timestamps are inserted in bulk.
     * If [markTimestamps] is empty, no mark rows are written.
     */
    suspend fun saveRecordingWithMarks(
        filePath: String,
        durationMs: Long,
        fileSizeBytes: Long,
        title: String,
        topicId: Long?,
        storageVolumeUuid: String = StorageVolumeHelper.UUID_PRIMARY,
        markTimestamps: List<Long> = emptyList(),
    ): Long {
        val recordingId = recordingDao.insert(
            RecordingEntity(
                filePath          = filePath,
                durationMs        = durationMs,
                fileSizeBytes     = fileSizeBytes,
                title             = title,
                topicId           = topicId,
                storageVolumeUuid = storageVolumeUuid,
            )
        )
        if (markTimestamps.isNotEmpty()) {
            markDao.insertAll(markTimestamps.map { MarkEntity(recordingId = recordingId, positionMs = it) })
        }
        return recordingId
    }

    suspend fun getPendingWaveformRecordings() = recordingDao.getPendingWaveformRecordings()
    suspend fun resetAllWaveformStatuses()     = recordingDao.resetAllWaveformStatuses()

    suspend fun getAllRecordingsOnce(): List<RecordingEntity> = recordingDao.getAllOnce()

    suspend fun updateRecording(recording: RecordingEntity) = recordingDao.update(recording)
    suspend fun deleteRecording(recording: RecordingEntity) = recordingDao.delete(recording)
    suspend fun moveRecording(id: Long, topicId: Long?)     = recordingDao.moveToTopic(id, topicId)
    suspend fun renameRecording(id: Long, title: String)    = recordingDao.rename(id, title)
    suspend fun setFavourite(id: Long, fav: Boolean)        = recordingDao.setFavourite(id, fav)
    suspend fun updatePlayback(id: Long, posMs: Long, listened: Boolean) =
        recordingDao.updatePlaybackState(id, posMs, listened)

    fun getAllRecordings(): Flow<List<RecordingEntity>>          = recordingDao.getAll()
    fun getUnsorted(): Flow<List<RecordingEntity>>               = recordingDao.getUnsorted()
    fun getFavourites(): Flow<List<RecordingEntity>>             = recordingDao.getFavourites()
    fun searchRecordings(q: String): Flow<List<RecordingEntity>> = recordingDao.search(q)

    /**
     * Returns the set of all file paths currently registered in the database.
     * Used by [com.treecast.app.util.OrphanRecordingScanner] at startup to
     * distinguish known recordings from orphaned files on disk.
     */
    suspend fun getKnownFilePaths(): Set<String> =
        recordingDao.getAllFilePaths().toHashSet()

    // ── Combined tree flow ────────────────────────────────────────────────────

    fun getTreeFlow(): Flow<List<TreeNode>> = combine(
        topicDao.getAllTopics(),
        recordingDao.getAll()
    ) { topics, recs ->
        TreeBuilder.build(topics, recs)
    }

    // ── Marks ─────────────────────────────────────────────────────────────────

    /**
     * Bulk-inserts mark timestamps for a given recording.
     * Called by [saveRecordingWithMarks]; can also be called independently
     * if marks need to be added to an existing recording in bulk.
     */
    suspend fun saveMarks(recordingId: Long, timestamps: List<Long>) {
        val entities = timestamps.map { positionMs ->
            MarkEntity(recordingId = recordingId, positionMs = positionMs)
        }
        markDao.insertAll(entities)
    }

    fun getMarksForRecording(recordingId: Long) = markDao.getMarksForRecording(recordingId)
    suspend fun addMark(recordingId: Long, positionMs: Long) =
        markDao.insert(MarkEntity(recordingId = recordingId, positionMs = positionMs))
    suspend fun deleteMark(id: Long)                = markDao.deleteById(id)
    suspend fun nudgeMark(id: Long, deltaMs: Long)  = markDao.nudgeMark(id, deltaMs)

    // ── Backup targets ────────────────────────────────────────────────────────

    /**
     * All configured backup targets, observed reactively.
     * Consumed by the Storage tab to build the backup target list UI.
     */
    fun getBackupTargets(): Flow<List<BackupTargetEntity>> =
        backupTargetDao.getAll()

    suspend fun getBackupTarget(volumeUuid: String): BackupTargetEntity? =
        backupTargetDao.getByUuid(volumeUuid)

    /**
     * Adds a new backup target with default settings (both triggers enabled,
     * 24-hour interval, no directory chosen yet).
     *
     * The caller is responsible for immediately launching the SAF directory
     * picker and calling [setBackupTargetDirUri] with the result, since a
     * target with a null [BackupTargetEntity.backupDirUri] cannot run a backup.
     */
    suspend fun addBackupTarget(volumeUuid: String) {
        backupTargetDao.insert(BackupTargetEntity(volumeUuid = volumeUuid))
        // Enqueue the periodic job immediately using the default interval.
        BackupWorker.enqueueOrUpdatePeriodic(
            context       = appContext,
            volumeUuid    = volumeUuid,
            intervalHours = 24L,
        )
    }

    /**
     * Removes a backup target and cancels its periodic WorkManager job.
     * One-time (on-connect) jobs that are already enqueued will run to
     * completion — they are not cancelled, as interrupting an in-progress
     * backup is worse than letting it finish.
     */
    suspend fun removeBackupTarget(volumeUuid: String) {
        backupTargetDao.deleteByUuid(volumeUuid)
        BackupWorker.cancelPeriodic(appContext, volumeUuid)
    }

    /**
     * Persists the SAF directory URI chosen by the user for this target.
     * Called immediately after a successful [Intent.ACTION_OPEN_DOCUMENT_TREE]
     * result in the add-target flow.
     */
    suspend fun setBackupTargetDirUri(volumeUuid: String, uri: String) =
        backupTargetDao.setBackupDirUri(volumeUuid, uri)

    /**
     * Toggles the on-connect backup trigger for a target.
     * No WorkManager changes needed — [StorageMountReceiver] reads this flag
     * live from the DB at mount time.
     */
    suspend fun setBackupOnConnectEnabled(volumeUuid: String, enabled: Boolean) =
        backupTargetDao.setOnConnectEnabled(volumeUuid, enabled)

    /**
     * Toggles the scheduled backup trigger for a target.
     * Enqueues or cancels the periodic WorkManager job accordingly.
     */
    suspend fun setBackupScheduledEnabled(volumeUuid: String, enabled: Boolean) {
        backupTargetDao.setScheduledEnabled(volumeUuid, enabled)
        if (enabled) {
            val intervalHours = backupTargetDao.getByUuid(volumeUuid)?.intervalHours?.toLong()
                ?: return
            BackupWorker.enqueueOrUpdatePeriodic(appContext, volumeUuid, intervalHours)
        } else {
            BackupWorker.cancelPeriodic(appContext, volumeUuid)
        }
    }

    /**
     * Updates the scheduled backup interval for a target.
     * If scheduled backups are currently enabled, the periodic WorkManager
     * job is replaced immediately so the new interval takes effect without
     * waiting for the next scheduled fire.
     */
    suspend fun setBackupIntervalHours(volumeUuid: String, hours: Int) {
        backupTargetDao.setIntervalHours(volumeUuid, hours)
        val target = backupTargetDao.getByUuid(volumeUuid) ?: return
        if (target.scheduledEnabled) {
            BackupWorker.enqueueOrUpdatePeriodic(appContext, volumeUuid, hours.toLong())
        }
    }

    suspend fun setBackupPreferencesEnabled(volumeUuid: String, enabled: Boolean) =
        backupTargetDao.setBackupPreferences(volumeUuid, enabled)

    // ── Backup logs ───────────────────────────────────────────────────────────

    /**
     * All backup log entries, newest first.
     * Consumed by the Tools tab backup history UI.
     */
    fun getBackupLogs(): Flow<List<BackupLogEntity>> =
        backupLogDao.getAll()

    /**
     * Backup log entries for a specific volume, newest first.
     * Used to populate per-target history in the Storage tab.
     */
    fun getBackupLogsForVolume(volumeUuid: String): Flow<List<BackupLogEntity>> =
        backupLogDao.getByVolume(volumeUuid)

    /**
     * The most recent completed log entry for a volume.
     * Used for the "Last backed up: …" label on target rows without
     * observing the full history flow.
     */
    suspend fun getLastBackupForVolume(volumeUuid: String): BackupLogEntity? =
        backupLogDao.getLastCompletedForVolume(volumeUuid)

    /**
     * Error/warning rows for a specific backup run.
     * Consumed by the incident detail view.
     */
    fun getBackupLogErrors(logId: Long): Flow<List<BackupLogErrorEntity>> =
        backupLogDao.getErrorsForLog(logId)

    /** Clears all backup log entries (and their child error rows via CASCADE). */
    suspend fun clearAllBackupLogs() = backupLogDao.clearAll()

    /**
     * Clears backup log entries for a specific volume.
     * Offered when the user removes a backup target.
     */
    suspend fun clearBackupLogsForVolume(volumeUuid: String) =
        backupLogDao.clearForVolume(volumeUuid)

    // ── Singleton ─────────────────────────────────────────────────────────────

    companion object {
        @Volatile private var INSTANCE: TreeCastRepository? = null
        fun getInstance(context: Context): TreeCastRepository =
            INSTANCE ?: synchronized(this) {
                TreeCastRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}