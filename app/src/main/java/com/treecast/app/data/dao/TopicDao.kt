package com.treecast.app.data.dao

import androidx.room.*
import com.treecast.app.data.entities.TopicEntity
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

    @Query("UPDATE topics SET is_collapsed = :collapsed WHERE id = :id")
    suspend fun setCollapsed(id: Long, collapsed: Boolean)

    @Query("UPDATE topics SET updated_at = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long = System.currentTimeMillis())
}