package app.treecast.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.treecast.data.entities.MarkEntity
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

    /** Moves a mark's position by deltaMs (negative = back, positive = forward). Floors at 0. */
    @Query("UPDATE marks SET position_ms = MAX(0, position_ms + :deltaMs) WHERE id = :id")
    suspend fun nudgeMark(id: Long, deltaMs: Long)

    /** One-shot read of marks for a single recording; used by the backup export pass. */
    @Query("SELECT * FROM marks WHERE recording_id = :recordingId ORDER BY position_ms ASC")
    suspend fun getMarksForRecordingOnce(recordingId: Long): List<MarkEntity>
}