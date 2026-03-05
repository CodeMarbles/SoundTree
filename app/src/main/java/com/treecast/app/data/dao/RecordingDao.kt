package com.treecast.app.data.dao

import androidx.room.*
import com.treecast.app.data.entities.RecordingEntity
import kotlinx.coroutines.flow.Flow

// ── Storage stats projection ──────────────────────────────────────────────────

/**
 * Per-volume aggregated storage usage, returned by [RecordingDao.getStorageUsageByVolume].
 * Room maps this from the GROUP BY query automatically.
 */
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

    /** All recordings in a topic, newest first */
    @Query("SELECT * FROM recordings WHERE topic_id = :topicId ORDER BY created_at DESC")
    fun getByTopic(topicId: Long): Flow<List<RecordingEntity>>

    /** Inbox: recordings with no topic assigned */
    @Query("SELECT * FROM recordings WHERE topic_id IS NULL ORDER BY created_at DESC")
    fun getInbox(): Flow<List<RecordingEntity>>

    /** All recordings flat list, newest first */
    @Query("SELECT * FROM recordings ORDER BY created_at DESC")
    fun getAll(): Flow<List<RecordingEntity>>

    /** Favourites */
    @Query("SELECT * FROM recordings WHERE is_favourite = 1 ORDER BY created_at DESC")
    fun getFavourites(): Flow<List<RecordingEntity>>

    /** Search by title or tags */
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

    /**
     * Returns used bytes grouped by storage volume. Collectors can join this
     * with the live volume list from [StorageVolumeHelper] to populate the
     * per-device usage annotation in Settings.
     *
     * Returns a [Flow] so the Settings UI re-renders automatically when the
     * user deletes a recording and the used-storage number changes.
     */
    @Query("""
        SELECT storage_volume_uuid, 
               COALESCE(SUM(file_size_bytes), 0) AS total_bytes
        FROM   recordings
        GROUP  BY storage_volume_uuid
    """)
    fun getStorageUsageByVolume(): Flow<List<VolumeUsage>>

    /**
     * All recordings stored on a specific volume. Used to flag orphaned
     * recordings when a volume is detected as unmounted.
     */
    @Query("SELECT * FROM recordings WHERE storage_volume_uuid = :uuid ORDER BY created_at DESC")
    fun getByVolume(uuid: String): Flow<List<RecordingEntity>>
}