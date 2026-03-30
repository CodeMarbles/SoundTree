package com.treecast.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.storage.StorageManager
import com.treecast.app.data.db.AppDatabase
import com.treecast.app.util.StorageVolumeHelper
import com.treecast.app.worker.BackupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Receives [Intent.ACTION_MEDIA_MOUNTED] and triggers an on-connect backup
 * for any volume that the user has designated as a backup target with
 * [com.treecast.app.data.entities.BackupTargetEntity.onConnectEnabled] = true.
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
 * ## Coroutine usage
 * [BroadcastReceiver.onReceive] must return quickly. We use [goAsync] to
 * extend the deadline while performing the DB lookup on [Dispatchers.IO],
 * then call [PendingResult.finish] when done.
 */
class StorageMountReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_MOUNTED) return

        val mountPath = intent.data?.path ?: return

        // Resolve the volume UUID from the mount path.
        val volumeUuid = resolveVolumeUuid(context, mountPath) ?: return

        // Extend receiver lifetime for the async DB lookup.
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).backupTargetDao()
                val target = dao.getByUuid(volumeUuid)

                if (target != null && target.onConnectEnabled) {
                    BackupWorker.enqueueOneTime(
                        context    = context,
                        volumeUuid = volumeUuid,
                        trigger    = com.treecast.app.data.entities.BackupLogEntity.BackupTrigger.ON_CONNECT,
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