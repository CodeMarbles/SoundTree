package com.treecast.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.treecast.app.data.entities.MarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MarkDao {
    @Query("SELECT * FROM marks WHERE recording_id = :recordingId ORDER BY position_ms ASC")
    fun getMarksForRecording(recordingId: Long): Flow<List<MarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mark: MarkEntity): Long

    /** Bulk insert — used to flush in-memory recording marks to the DB on save. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(marks: List<MarkEntity>)

    @Delete
    suspend fun delete(mark: MarkEntity)

    @Query("DELETE FROM marks WHERE id = :id")
    suspend fun deleteById(id: Long)
}