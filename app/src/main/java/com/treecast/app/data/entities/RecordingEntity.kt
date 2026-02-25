package com.treecast.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single recorded audio episode (leaf node in the podcast tree).
 * Belongs to a category (nullable = uncategorised / Inbox).
 */
@Entity(
    tableName = "recordings",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("category_id")]
)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Null = "Inbox" (uncategorised) */
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,

    @ColumnInfo(name = "title") val title: String,

    @ColumnInfo(name = "description") val description: String = "",

    /** Absolute path to the .m4a file on device storage */
    @ColumnInfo(name = "file_path") val filePath: String,

    /** Duration in milliseconds */
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0L,

    /** File size in bytes */
    @ColumnInfo(name = "file_size_bytes") val fileSizeBytes: Long = 0L,

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),

    /** Last position played back to (ms) — for resume */
    @ColumnInfo(name = "playback_position_ms") val playbackPositionMs: Long = 0L,

    /** Whether the user has fully listened to this recording */
    @ColumnInfo(name = "is_listened") val isListened: Boolean = false,

    /** Whether this recording is marked as a favourite */
    @ColumnInfo(name = "is_favourite") val isFavourite: Boolean = false,

    /** Display order within its category */
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,

    /**
     * Comma-separated tags, e.g. "idea,morning,important"
     * Simple storage — app splits/joins on ","
     */
    @ColumnInfo(name = "tags") val tags: String = ""
)
