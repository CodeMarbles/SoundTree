package com.treecast.app.data.dao

import androidx.room.*
import com.treecast.app.data.entities.RecordingEntity
import com.treecast.app.util.WaveformStatus
import kotlinx.coroutines.flow.Flow

// ── Storage stats projection ──────────────────────────────────────────────────

data class VolumeUsage(
    @ColumnInfo(name = "storage_volume_uuid") val storageVolumeUuid: String,
    @ColumnInfo(name = "total_bytes")         val totalBytes: Long
)

// ── DAO ───────────────────────────────────────────────────────────────────────

@Dao
interface RecordingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: RecordingEntity): Long

    @Update
    suspend fun update(recording: RecordingEntity)

    @Delete
    suspend fun delete(recording: RecordingEntity)

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getById(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE topic_id = :topicId ORDER BY created_at DESC")
    fun getByTopic(topicId: Long): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE topic_id IS NULL ORDER BY created_at DESC")
    fun getInbox(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings ORDER BY created_at DESC")
    fun getAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE is_favourite = 1 ORDER BY created_at DESC")
    fun getFavourites(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE title LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' ORDER BY created_at DESC")
    fun search(query: String): Flow<List<RecordingEntity>>

    @Query("UPDATE recordings SET playback_position_ms = :positionMs, is_listened = :listened WHERE id = :id")
    suspend fun updatePlaybackState(id: Long, positionMs: Long, listened: Boolean)

    @Query("UPDATE recordings SET is_favourite = :fav WHERE id = :id")
    suspend fun setFavourite(id: Long, fav: Boolean)

    @Query("UPDATE recordings SET topic_id = :topicId WHERE id = :id")
    suspend fun moveToTopic(id: Long, topicId: Long?)

    @Query("UPDATE recordings SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("SELECT COUNT(*) FROM recordings WHERE topic_id = :topicId")
    suspend fun countInTopic(topicId: Long): Int

    @Query("SELECT COUNT(*) FROM recordings")
    suspend fun countAll(): Int

    // ── Storage ───────────────────────────────────────────────────────────────

    @Query("""
        SELECT storage_volume_uuid, 
               COALESCE(SUM(file_size_bytes), 0) AS total_bytes
        FROM   recordings
        GROUP  BY storage_volume_uuid
    """)
    fun getStorageUsageByVolume(): Flow<List<VolumeUsage>>

    @Query("SELECT * FROM recordings WHERE storage_volume_uuid = :uuid ORDER BY created_at DESC")
    fun getByVolume(uuid: String): Flow<List<RecordingEntity>>

    // ── Waveform ──────────────────────────────────────────────────────────────

    @Query("UPDATE recordings SET waveform_status = :status WHERE id = :id")
    suspend fun updateWaveformStatus(id: Long, status: Int)

    /**
     * Resets every recording's waveform status back to PENDING.
     * Called by the "Regenerate all waveforms" action in Settings, after the
     * cache files have been deleted, so every recording gets re-decoded.
     */
    @Query("UPDATE recordings SET waveform_status = ${WaveformStatus.PENDING}")
    suspend fun resetAllWaveformStatuses()

    /**
     * Returns recordings whose waveform has not yet been successfully generated.
     * Includes IN_PROGRESS rows to recover from an app kill mid-worker.
     */
    @Query("SELECT * FROM recordings WHERE waveform_status IN (:pending, :inProgress)")
    suspend fun getPendingWaveformRecordings(
        pending: Int = WaveformStatus.PENDING,
        inProgress: Int = WaveformStatus.IN_PROGRESS
    ): List<RecordingEntity>

    /** Returns every recording, used for bulk re-enqueue after a full reset. */
    @Query("SELECT * FROM recordings ORDER BY created_at ASC")
    suspend fun getAllOnce(): List<RecordingEntity>

    @Query("SELECT file_path FROM recordings")
    suspend fun getAllFilePaths(): List<String>
}