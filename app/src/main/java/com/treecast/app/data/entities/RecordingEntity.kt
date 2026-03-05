package com.treecast.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.treecast.app.util.StorageVolumeHelper

/**
 * A single recorded audio episode (leaf node in the podcast tree).
 * Belongs to a topic (nullable = uncategorised / Inbox).
 *
 * DB version 4 adds [storageVolumeUuid] to track which storage device holds
 * the audio file. This allows:
 *   - Per-device used-storage stats without parsing file paths
 *   - Identifying orphaned recordings when a volume is ejected
 *   - Correctly routing new recordings to the user's preferred device
 *
 * Existing rows receive DEFAULT 'primary' via the Room migration, which
 * matches [StorageVolumeHelper.UUID_PRIMARY] — correct for all recordings
 * saved before this feature was added (they all went to getExternalFilesDir).
 */
@Entity(
    tableName = "recordings",
    foreignKeys = [
        ForeignKey(
            entity = TopicEntity::class,
            parentColumns = ["id"],
            childColumns = ["topic_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("topic_id")]
)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Null = "Inbox" (uncategorised) */
    @ColumnInfo(name = "topic_id") val topicId: Long? = null,

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

    /** Display order within its topic */
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,

    /**
     * Comma-separated tags, e.g. "idea,morning,important"
     * Simple storage — app splits/joins on ","
     */
    @ColumnInfo(name = "tags") val tags: String = "",

    /**
     * UUID of the storage volume that holds [filePath].
     * Matches [StorageVolumeHelper.UUID_PRIMARY] ("primary") for the default
     * primary-external volume, or the removable-volume UUID (e.g. "1A2B-3C4D")
     * for SD cards. Never null — existing rows migrated to "primary".
     *
     * Use this rather than parsing [filePath] to identify the volume, as path
     * prefixes can theoretically change between OS versions.
     */
    @ColumnInfo(name = "storage_volume_uuid")
    val storageVolumeUuid: String = StorageVolumeHelper.UUID_PRIMARY
)