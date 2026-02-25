package com.treecast.app.data.dao

import androidx.room.*
import com.treecast.app.data.entities.RecordingEntity
import kotlinx.coroutines.flow.Flow

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
}