package com.treecast.app.data.repository

import android.content.Context
import com.treecast.app.data.dao.VolumeUsage
import com.treecast.app.data.db.AppDatabase
import com.treecast.app.data.entities.MarkEntity
import com.treecast.app.data.entities.RecordingEntity
import com.treecast.app.data.entities.SessionEntity
import com.treecast.app.data.entities.TopicEntity
import com.treecast.app.util.StorageVolumeHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class TreeCastRepository(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val sessionDao = db.sessionDao()
    private val topicDao = db.topicDao()
    private val recordingDao = db.recordingDao()
    private val markDao = db.markDao()

    // ── Session ──────────────────────────────────────────────────────
    suspend fun openSession(): Long {
        val session = SessionEntity()
        return sessionDao.insert(session)
    }

    suspend fun closeSession(id: Long) {
        val now = System.currentTimeMillis()
        val session = sessionDao.getLastSession()?.takeIf { it.id == id } ?: return
        sessionDao.closeSession(id, now, now - session.openedAt)
    }

    suspend fun getLastSession(): SessionEntity? = sessionDao.getLastSession()
    suspend fun getLastClosedSession(): SessionEntity? = sessionDao.getLastClosedSession()
    suspend fun getTotalRecordingTime(): Long = sessionDao.getTotalRecordingTime()

    // ── Topics ──────────────────────────────────────────────────────
    suspend fun createTopic(
        name: String,
        parentId: Long? = null,
        icon: String = "🎙️",
        color: String = "#6C63FF"
    ): Long = topicDao.insert(TopicEntity(name = name, parentId = parentId, icon = icon, color = color))

    suspend fun updateTopic(topic: TopicEntity) = topicDao.update(topic)
    suspend fun deleteTopic(topic: TopicEntity) = topicDao.delete(topic)
    fun getAllTopics(): Flow<List<TopicEntity>> = topicDao.getAllTopics()
    suspend fun topicExists(id: Long): Boolean = topicDao.getById(id) != null

    // ── Recordings ──────────────────────────────────────────────────
    suspend fun saveRecording(
        filePath: String,
        durationMs: Long,
        fileSizeBytes: Long,
        title: String,
        topicId: Long? = null
    ): Long = recordingDao.insert(
        RecordingEntity(
            filePath = filePath,
            durationMs = durationMs,
            fileSizeBytes = fileSizeBytes,
            title = title,
            topicId = topicId
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
        topicId: Long? = null,
        markTimestamps: List<Long>,
        storageVolumeUuid: String = StorageVolumeHelper.UUID_PRIMARY
    ): Long {
        val recordingId = recordingDao.insert(
            RecordingEntity(
                filePath          = filePath,
                durationMs        = durationMs,
                fileSizeBytes     = fileSizeBytes,
                title             = title,
                topicId           = topicId,
                storageVolumeUuid = storageVolumeUuid
            )
        )
        if (markTimestamps.isNotEmpty()) {
            saveMarks(recordingId, markTimestamps)
        }
        return recordingId
    }

    /**
     * Returns recordings whose waveform is PENDING or stuck IN_PROGRESS.
     * Called by TreeCastApp on startup to enqueue jobs.
     */
    suspend fun getPendingWaveformRecordings(): List<RecordingEntity> =
        recordingDao.getPendingWaveformRecordings()

    /**
     * Resets every recording's waveform status to PENDING.
     * Called by the "Regenerate all waveforms" action after cache files
     * have been deleted.
     */
    suspend fun resetAllWaveformStatuses() =
        recordingDao.resetAllWaveformStatuses()

    /**
     * Returns every recording in the database (oldest first).
     * Used for bulk re-enqueue after a full waveform reset.
     */
    suspend fun getAllRecordingsOnce(): List<RecordingEntity> =
        recordingDao.getAllOnce()

    suspend fun updateRecording(recording: RecordingEntity) = recordingDao.update(recording)
    suspend fun deleteRecording(recording: RecordingEntity) = recordingDao.delete(recording)
    suspend fun moveRecording(id: Long, topicId: Long?) = recordingDao.moveToTopic(id, topicId)
    suspend fun renameRecording(id: Long, title: String) = recordingDao.rename(id, title)
    suspend fun setFavourite(id: Long, fav: Boolean) = recordingDao.setFavourite(id, fav)
    suspend fun updatePlayback(id: Long, posMs: Long, listened: Boolean) =
        recordingDao.updatePlaybackState(id, posMs, listened)

    fun getAllRecordings(): Flow<List<RecordingEntity>> = recordingDao.getAll()
    fun getInbox(): Flow<List<RecordingEntity>> = recordingDao.getInbox()
    fun getFavourites(): Flow<List<RecordingEntity>> = recordingDao.getFavourites()
    fun searchRecordings(q: String): Flow<List<RecordingEntity>> = recordingDao.search(q)

    // ── Combined tree flow ───────────────────────────────────────────
    fun getTreeFlow(): Flow<List<TreeNode>> = combine(
        topicDao.getAllTopics(),
        recordingDao.getAll()
    ) { topics, recs ->
        TreeBuilder.build(topics, recs)
    }

    // ── Marks ──────────────────────────────────────────────────────────
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
    suspend fun deleteMark(id: Long) = markDao.deleteById(id)
    suspend fun nudgeMark(id: Long, deltaMs: Long) = markDao.nudgeMark(id, deltaMs)

    companion object {
        @Volatile private var INSTANCE: TreeCastRepository? = null
        fun getInstance(context: Context): TreeCastRepository =
            INSTANCE ?: synchronized(this) {
                TreeCastRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}