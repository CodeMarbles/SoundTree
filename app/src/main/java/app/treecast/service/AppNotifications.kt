package app.treecast.service

/**
 * Single source of truth for all notification-related integer constants.
 *
 * PendingIntent request codes and NotificationManager notification IDs are
 * both app-wide flat namespaces — the OS does not scope them per-class or
 * per-service. Any collision silently produces the wrong behaviour (wrong
 * intent delivered, wrong notification updated/dismissed). All values must
 * be unique across this entire file.
 *
 * Channel IDs are strings and less collision-prone, but are also defined
 * here to eliminate magic strings and keep notification concerns in one place.
 *
 * ── Allocation map ────────────────────────────────────────────────────────
 *
 *  Notification IDs
 *    1001  NOTIF_RECORDING   RecordingService foreground notification
 *    1002  NOTIF_PLAYBACK    PlaybackService / Media3 foreground notification
 *    1003  NOTIF_BACKUP      BackupWorker progress / result notification
 *
 *  PendingIntent request codes
 *    Recording service (1x)
 *      10  REQUEST_RECORD_PAUSE
 *      11  REQUEST_RECORD_RESUME
 *      12  REQUEST_RECORD_STOP      (reserved; not currently in notification)
 *      13  REQUEST_RECORD_MARK
 *      14  REQUEST_RECORD_SAVE
 *      15  REQUEST_RECORD_OPEN_APP  content-intent → Record tab
 *
 *    Playback service (2x)
 *      20  REQUEST_PLAYBACK_SESSION_ACTIVITY  content-intent → Listen tab
 *
 *    Backup worker (3x)
 *      30  REQUEST_BACKUP_OPEN_SETTINGS  content-intent → Settings/Storage tab
 */
object AppNotifications {

    // ── Notification IDs ──────────────────────────────────────────────────────

    const val NOTIF_RECORDING = 1001
    const val NOTIF_PLAYBACK  = 1002  // Currently un-used as media3 handles this internally.   Kept as a placeholder for when we eventually, maybe, take finer control of playback notification controls
    const val NOTIF_BACKUP    = 1003

    // ── Channel IDs ───────────────────────────────────────────────────────────

    const val CHANNEL_RECORDING = "treecast_recording"
    const val CHANNEL_PLAYBACK  = "treecast_playback"
    const val CHANNEL_BACKUP    = "treecast_backup"

    // ── PendingIntent request codes — Recording ───────────────────────────────

    const val REQUEST_RECORD_PAUSE    = 10
    const val REQUEST_RECORD_RESUME   = 11
    const val REQUEST_RECORD_STOP     = 12
    const val REQUEST_RECORD_MARK     = 13
    const val REQUEST_RECORD_SAVE     = 14
    const val REQUEST_RECORD_OPEN_APP = 15

    // ── PendingIntent request codes — Playback ────────────────────────────────

    const val REQUEST_PLAYBACK_SESSION_ACTIVITY = 20

    // ── PendingIntent request codes — Backup ──────────────────────────────────

    const val REQUEST_BACKUP_OPEN_SETTINGS = 30
}