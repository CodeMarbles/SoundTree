package app.treecast.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.treecast.storage.StorageVolumeHelper
import app.treecast.util.WaveformStatus

/**
 * A single recorded audio episode (leaf node in the podcast tree).
 * Belongs to a topic (nullable = Unsorted).
 *
 * DB version 4 adds [storageVolumeUuid] to track which storage device holds
 * the audio file.
 *
 * DB version 5 adds [waveformStatus] to track background waveform generation.
 * Existing rows are migrated to [WaveformStatus.PENDING] so they get picked
 * up by [app.treecast.worker.WaveformWorker] on the first launch after
 * the update.
 *
 * DB version 12 adds [metadataUpdatedAt] to track the freshness of all
 * content metadata associated with this recording. Bumped by the repository
 * on any mutation to the recording itself (title, description, tags, topic
 * assignment, favourite status) or to any of its marks (add, delete, nudge).
 * Backfilled from [createdAt] for existing rows.
 *
 * Intentionally NOT bumped by [updatePlayback] — playback position and
 * listened state are device-local state, not content metadata.
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

    /** Null = "Unsorted" */
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
    @ColumnInfo(name = "storage_volume_uuid") val storageVolumeUuid: String = StorageVolumeHelper.UUID_PRIMARY,

    @ColumnInfo(name = "waveform_status") val waveformStatus: Int = WaveformStatus.PENDING,

    @ColumnInfo(name = "db_inserted_at") val dbInsertedAt: Long = System.currentTimeMillis(),

    /**
     * Epoch ms of the most recent change to this recording's content metadata.
     *
     * "Content metadata" means anything that belongs in an export: title,
     * description, tags, topic assignment, favourite status, and marks.
     * Bumped transactionally alongside the change that caused it.
     *
     * Used by the backup export pass to decide whether the destination JSON
     * is still fresh (skip) or needs to be regenerated (re-export).
     *
     * Backfilled from [createdAt] for rows existing before DB version 12.
     * NOT bumped by playback state changes ([playbackPositionMs], [isListened]).
     */
    @ColumnInfo(name = "metadata_updated_at") val metadataUpdatedAt: Long = System.currentTimeMillis(),
)