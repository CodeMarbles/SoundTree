package com.treecast.app.data.dao

import androidx.room.*
import com.treecast.app.data.entities.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    /** Called on app close: set closedAt and durationMs */
    @Query("""
        UPDATE sessions 
        SET closed_at = :closedAt, duration_ms = :durationMs
        WHERE id = :id
    """)
    suspend fun closeSession(id: Long, closedAt: Long, durationMs: Long)

    /** The most recent session regardless of status */
    @Query("SELECT * FROM sessions ORDER BY opened_at DESC LIMIT 1")
    suspend fun getLastSession(): SessionEntity?

    /** All completed sessions ordered newest-first */
    @Query("SELECT * FROM sessions WHERE closed_at IS NOT NULL ORDER BY opened_at DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    /** Sessions opened after a given epoch timestamp */
    @Query("SELECT * FROM sessions WHERE opened_at >= :since ORDER BY opened_at ASC")
    suspend fun getSessionsSince(since: Long): List<SessionEntity>

    /** The most recently completed (closed) session — excludes the active one */
    @Query("SELECT * FROM sessions WHERE closed_at IS NOT NULL ORDER BY opened_at DESC LIMIT 1")
    suspend fun getLastClosedSession(): SessionEntity?

    /** Total recording time across all sessions in ms */
    @Query("SELECT COALESCE(SUM(duration_ms), 0) FROM sessions WHERE closed_at IS NOT NULL AND session_type = 'RECORD'")
    suspend fun getTotalRecordingTime(): Long

    /** Count of recording sessions */
    @Query("SELECT COUNT(*) FROM sessions WHERE session_type = 'RECORD'")
    suspend fun getRecordingSessionCount(): Int

    /** The active (not yet closed) session if one exists */
    @Query("SELECT * FROM sessions WHERE closed_at IS NULL ORDER BY opened_at DESC LIMIT 1")
    suspend fun getActiveSession(): SessionEntity?

    @Query("DELETE FROM sessions")
    suspend fun clearAll()
}
