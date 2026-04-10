package app.treecast.data.dao

import androidx.room.*
import app.treecast.data.entities.TopicEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TopicDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(topic: TopicEntity): Long

    @Update
    suspend fun update(topic: TopicEntity)

    @Delete
    suspend fun delete(topic: TopicEntity)

    @Query("SELECT * FROM topics WHERE id = :id")
    suspend fun getById(id: Long): TopicEntity?

    /** All topics — used to reconstruct the full tree in-memory */
    @Query("SELECT * FROM topics ORDER BY sort_order ASC, name ASC")
    fun getAllTopics(): Flow<List<TopicEntity>>

    @Query("SELECT * FROM topics ORDER BY sort_order ASC, name ASC")
    suspend fun getAllTopicsOnce(): List<TopicEntity>

    /** Direct children of a given parent */
    @Query("SELECT * FROM topics WHERE parent_id = :parentId ORDER BY sort_order ASC, name ASC")
    fun getChildren(parentId: Long): Flow<List<TopicEntity>>

    /** Root topics (no parent) */
    @Query("SELECT * FROM topics WHERE parent_id IS NULL ORDER BY sort_order ASC, name ASC")
    fun getRoots(): Flow<List<TopicEntity>>

    @Query("UPDATE topics SET updated_at = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long = System.currentTimeMillis())

    /** Total topic count. Used by the restore wizard summary step. */
    @Query("SELECT COUNT(*) FROM topics")
    suspend fun countAll(): Int
}