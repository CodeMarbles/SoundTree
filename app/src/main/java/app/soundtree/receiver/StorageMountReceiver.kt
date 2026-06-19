package app.soundtree.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.storage.StorageManager
import app.soundtree.data.db.AppDatabase
import app.soundtree.diagnostic.MediaMountEvent
import app.soundtree.diagnostic.MediaMountEventLog
import app.soundtree.storage.StorageVolumeHelper
import app.soundtree.worker.BackupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Receives [Intent.ACTION_MEDIA_MOUNTED] and triggers on-connect backups
 * for every target configured for the newly-mounted volume.
 *
 * ## Why static registration is correct here
 * Most storage-related broadcasts ([Intent.ACTION_MEDIA_REMOVED] etc.) cannot
 * be received by manifest-registered receivers on API 26+ due to background
 * execution limits. [Intent.ACTION_MEDIA_MOUNTED] is explicitly exempt from
 * this restriction — it is on Android's implicit broadcast allowlist — so a
 * manifest entry is both permitted and the right choice. Dynamic registration
 * would mean the backup trigger silently does nothing if the app is not
 * running when the user plugs in the drive.
 *
 * ## UUID resolution
 * [Intent.ACTION_MEDIA_MOUNTED] carries a `file://` URI in [Intent.getData]
 * pointing to the mount path (e.g. `/storage/1A2B-3C4D`). We resolve the
 * volume UUID from this path via [StorageManager.getStorageVolume], mapping
 * a null UUID (primary volume) to [StorageVolumeHelper.UUID_PRIMARY] to stay
 * consistent with the rest of the app.
 *
 * ## Multiple targets per volume
 * After the v16 surrogate-key refactor, a single volume may have multiple
 * backup targets (e.g. one per SAF directory). [BackupWorker.getOnConnectTargets]
 * returns all on-connect-enabled targets; we filter by the resolved UUID and
 * enqueue one independent job per matching target.
 *
 * ## Coroutine usage
 * [BroadcastReceiver.onReceive] must return quickly. We use [goAsync] to
 * extend the deadline while performing the DB lookup on [Dispatchers.IO],
 * then call [PendingResult.finish] when done.
 *
 * ## Diagnostic logging
 * Every received broadcast is recorded to [MediaMountEventLog] (in-memory,
 * process lifetime only) so the Storage Event Log dev tool can show whether
 * this static receiver is being reached.
 */
class StorageMountReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        MediaMountEventLog.record(context, intent, MediaMountEvent.ReceiverSource.STATIC)

        if (intent.action != Intent.ACTION_MEDIA_MOUNTED) return

        val mountPath = intent.data?.path ?: return
        val volumeUuid = resolveVolumeUuid(context, mountPath) ?: return

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).backupTargetDao()

                // All on-connect targets for ANY volume — filter to the one that mounted.
                // After the v16 refactor there may be more than one target per volume.
                val matchingTargets = dao.getOnConnectTargets()
                    .filter { it.volumeUuid == volumeUuid }

                for (target in matchingTargets) {
                    BackupWorker.enqueueOneTime(
                        context  = context,
                        targetId = target.id,
                        trigger  = app.soundtree.data.entities.BackupLogEntity.BackupTrigger.ON_CONNECT,
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Resolves the stable volume UUID from a mount path string.
     *
     * Returns [StorageVolumeHelper.UUID_PRIMARY] for the primary emulated
     * volume (whose [android.os.storage.StorageVolume.getUuid] is null),
     * the UUID string for removable volumes (e.g. "1A2B-3C4D"), or null
     * if the path cannot be matched to any known volume.
     */
    private fun resolveVolumeUuid(context: Context, mountPath: String): String? {
        val sm = context.getSystemService(StorageManager::class.java)
        return runCatching {
            val sv = sm.getStorageVolume(File(mountPath)) ?: return null
            sv.uuid ?: StorageVolumeHelper.UUID_PRIMARY
        }.getOrNull()
    }
}