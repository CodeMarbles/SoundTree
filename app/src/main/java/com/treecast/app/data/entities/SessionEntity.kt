package com.treecast.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks every app session (open → close).
 * Rows are chronologically ordered by openedAt.
 *
 * Use-cases:
 *  - "Time since last session"  → SELECT * FROM sessions ORDER BY openedAt DESC LIMIT 1
 *  - "Sessions in past week"    → SELECT * FROM sessions WHERE openedAt >= :weekAgo
 *  - "Total listen/record time" → SUM(durationMs) WHERE sessionType = 'RECORD'|'LISTEN'
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Epoch millis when the app came to foreground */
    @ColumnInfo(name = "opened_at") val openedAt: Long = System.currentTimeMillis(),

    /** Epoch millis when the app left foreground; null while session is active */
    @ColumnInfo(name = "closed_at") val closedAt: Long? = null,

    /** Derived convenience column: closedAt - openedAt (ms). Updated on close. */
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,

    /**
     * One of: "IDLE" | "RECORD" | "PLAYBACK"
     * Set to the dominant activity of the session.
     */
    @ColumnInfo(name = "session_type") val sessionType: String = "IDLE",

    /** App build version for debugging */
    @ColumnInfo(name = "app_version") val appVersion: Int = 1
)
