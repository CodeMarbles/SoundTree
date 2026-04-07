package app.treecast.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a storage volume designated as an automatic backup destination.
 *
 * Each row corresponds to one volume UUID the user has added as a backup target.
 * Two independent backup triggers are supported and can be enabled in any
 * combination:
 *
 *  - **On connect** ([onConnectEnabled]): a backup is triggered whenever the
 *    volume mounts. Natural default for SD cards or drives the user connects
 *    occasionally.
 *
 *  - **Scheduled** ([scheduledEnabled] + [intervalHours]): a periodic backup
 *    runs every [intervalHours] hours via a WorkManager [PeriodicWorkRequest].
 *    Intended for volumes that remain permanently attached.
 *
 * Both modes default to enabled so the out-of-the-box experience is
 * "back up on connect AND on a 24-hour schedule." The user can selectively
 * disable either without losing the other's configuration.
 *
 * [backupPreferences] is stubbed for a future feature that will include
 * app preferences (SharedPreferences) in the backup archive. It has no effect
 * on backup behaviour in the current version.
 */
@Entity(tableName = "backup_targets")
data class BackupTargetEntity(

    /**
     * Stable UUID of the backup destination volume.
     * Matches the format used by [app.treecast.util.AppVolume.uuid]:
     *   - [app.treecast.util.StorageVolumeHelper.UUID_PRIMARY] ("primary")
     *     for the primary external volume (not a useful backup target, but not
     *     explicitly excluded at the entity level).
     *   - A removable-volume UUID string (e.g. "1A2B-3C4D") for SD cards /
     *     external drives.
     */
    @PrimaryKey
    @ColumnInfo(name = "volume_uuid")
    val volumeUuid: String,

    /**
     * Human-readable OS label for this volume (e.g. "USB Drive", "SD Card").
     * Cached here so the Settings tab can display a name even when the drive
     * is not currently connected. Updated whenever the user first adds the
     * target and again each time a backup runs successfully.
     *
     * Null for targets added before schema v11, or when the OS has never
     * provided a label for this volume.
     */
    @ColumnInfo(name = "volume_label")
    val volumeLabel: String? = null,

    /**
     * When true, [app.treecast.receiver.StorageMountReceiver] will enqueue
     * a [app.treecast.worker.BackupWorker] job each time this volume mounts.
     *
     * Default: true.
     */
    @ColumnInfo(name = "on_connect_enabled")
    val onConnectEnabled: Boolean = true,

    /**
     * When true, a WorkManager [androidx.work.PeriodicWorkRequest] is kept live
     * for this volume, firing every [intervalHours] hours.
     *
     * Toggling this off cancels the periodic request; toggling it back on
     * re-enqueues it using the stored [intervalHours] value.
     *
     * Default: true.
     */
    @ColumnInfo(name = "scheduled_enabled")
    val scheduledEnabled: Boolean = true,

    /**
     * How often the scheduled backup fires, in hours.
     * Only meaningful when [scheduledEnabled] is true.
     * Preserved when [scheduledEnabled] is toggled off so the value is
     * remembered if the user re-enables scheduling later.
     *
     * Default: 24 (once per day).
     */
    @ColumnInfo(name = "interval_hours")
    val intervalHours: Int = 24,

    /**
     * Epoch milliseconds of the most recent completed backup to this volume,
     * or null if a backup has never been run.
     *
     * Updated by [app.treecast.worker.BackupWorker] on successful
     * completion. Used to display "Last backed up: …" in the Storage tab UI.
     */
    @ColumnInfo(name = "last_backup_at")
    val lastBackupAt: Long? = null,

    /**
     * Persisted SAF (Storage Access Framework) URI string pointing to the
     * user-chosen directory on this volume where backup files will be written.
     *
     * Obtained via [android.content.Intent.ACTION_OPEN_DOCUMENT_TREE] and
     * persisted with [android.content.ContentResolver.takePersistableUriPermission]
     * so access survives process death and device reboots.
     *
     * Null means the user has not yet completed directory selection — the UI
     * should treat this target as incomplete and prompt for a directory before
     * allowing backup to run. Set during the add-target flow, not after.
     */
    @ColumnInfo(name = "backup_dir_uri")
    val backupDirUri: String? = null,

    /**
     * When true, the backup archive will include a snapshot of app preferences
     * (SharedPreferences). **Currently stubbed — has no effect on backup
     * behaviour.** Wired up in a follow-on feature.
     *
     * Default: false (opt-in once the feature is implemented).
     */
    @ColumnInfo(name = "backup_preferences")
    val backupPreferences: Boolean = false,
)