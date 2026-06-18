package app.soundtree.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.soundtree.diagnostic.MediaMountEvent
import app.soundtree.diagnostic.MediaMountEventLog

/**
 * Receives storage removal broadcasts and notifies a listener so the UI
 * can refresh its volume list and orphan indicators immediately.
 *
 * Registered/unregistered dynamically in [MainActivity] — NOT in the
 * manifest. Static registration of ACTION_MEDIA_REMOVED is blocked by
 * the OS on API 26+ for this action family.
 *
 * ## Diagnostic logging
 * Every received broadcast is recorded to [MediaMountEventLog] (in-memory,
 * process lifetime only) so the Storage Event Log dev tool can distinguish
 * events delivered to this dynamic receiver vs the static [app.soundtree.receiver.StorageMountReceiver].
 */
class StorageVolumeEventReceiver(
    private val onVolumeChange: () -> Unit
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Record all storage broadcasts for diagnostic purposes, regardless
        // of whether we act on them.
        MediaMountEventLog.record(context, intent, MediaMountEvent.ReceiverSource.DYNAMIC)

        when (intent.action) {
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_BAD_REMOVAL,
            Intent.ACTION_MEDIA_MOUNTED -> onVolumeChange()
        }
    }
}