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
 *    1001        NOTIF_RECORDING   RecordingService foreground notification
 *    1002        NOTIF_PLAYBACK    PlaybackService / Media3 foreground notification
 *    1003–5097   Per-volume backup notifications, computed by
 *                BackupWorker.notifIdForVolume(uuid) as:
 *                  NOTIF_BACKUP_BASE + (uuid.hashCode() and 0x0FFF)
 *                The same value doubles as the PendingIntent request code for
 *                that volume's deep-link intent, keeping both namespaces
 *                consistent and avoiding cross-volume PendingIntent collisions.
 *
 *  PendingIntent request codes
 *    Recording service (1x)
 *      10  REQUEST_RECORD_PAUSE
 *      11  REQUEST_RECORD_RESUME
 *      12  REQUEST_RECORD_STOP      (reserved; not currently wired to a notification action)
 *      13  REQUEST_RECORD_MARK
 *      14  REQUEST_RECORD_SAVE
 *      15  REQUEST_RECORD_OPEN_APP  content-intent → Record tab
 *
 *    Playback service (2x)
 *      20  REQUEST_PLAYBACK_SESSION_ACTIVITY  content-intent → Listen tab
 *
 *    Backup worker (3x)
 *      Per-volume, 1003–5097 — see NOTIF_BACKUP_BASE note above.
 */
object AppNotifications {

    // ── Notification IDs ──────────────────────────────────────────────────────

    const val NOTIF_RECORDING = 1001
    /** Currently unused — Media3 manages the playback notification internally.
     *  Kept as a placeholder for eventual finer control of playback actions. */
    const val NOTIF_PLAYBACK  = 1002

    /**
     * Base value for per-volume backup notification IDs.
     * Never used as a bare ID — always passed through [BackupWorker.notifIdForVolume].
     * Allocated range: 1003–5097 (4 095 slots via a 12-bit hash).
     */
    const val NOTIF_BACKUP_BASE = 1003

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
    //
    // Backup uses per-volume codes derived from notifIdForVolume(uuid) — see
    // NOTIF_BACKUP_BASE above. No single fixed constant is defined here.
}