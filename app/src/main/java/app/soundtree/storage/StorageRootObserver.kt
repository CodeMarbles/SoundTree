package app.soundtree.storage

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import app.soundtree.diagnostic.MediaMountEvent
import app.soundtree.diagnostic.MediaMountEventLog

/**
 * Observes the external storage documents provider's roots URI for changes,
 * as an alternative mechanism for detecting volume mount/unmount events.
 *
 * ## Motivation
 * On GrapheneOS, [android.content.Intent.ACTION_MEDIA_MOUNTED] is suppressed
 * at the OS level and never delivered to the app's receivers (static or dynamic).
 * A [ContentObserver] on the documents provider roots URI uses a different
 * OS codepath and may survive GrapheneOS's broadcast restrictions.
 *
 * ## What fires this
 * The external storage documents provider (`com.android.externalstorage.documents`)
 * calls [android.content.ContentResolver.notifyChange] on its roots URI whenever
 * its root set changes — i.e. when a volume mounts or unmounts. This is the same
 * signal that system file pickers use to refresh their sidebar.
 *
 * ## Limitations
 * - Only active while the observer is registered (foreground use only).
 * - Does not carry a mount path or volume UUID in the notification itself;
 *   the caller is responsible for querying [android.os.storage.StorageManager]
 *   separately if volume details are needed.
 * - [onChange] may fire multiple times for a single mount event (the provider
 *   can notify several times as it resolves the volume). The [onRootsChanged]
 *   callback should be idempotent.
 *
 * ## Usage
 *   val observer = StorageRootsObserver(context) { refreshStorageVolumes() }
 *   // in onStart:
 *   observer.register()
 *   // in onStop:
 *   observer.unregister()
 */
class StorageRootsObserver(
    private val context: Context,
    private val onRootsChanged: () -> Unit,
) : ContentObserver(Handler(Looper.getMainLooper())) {

    companion object {
        val ROOTS_URI: Uri = DocumentsContract.buildRootsUri(
            "com.android.externalstorage.documents"
        )

        /** How long to wait after the last onChange before firing the callback. */
        private const val DEBOUNCE_MS = 500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val debounced = Runnable {
        MediaMountEventLog.recordRootsChange(
            context = context,
            uri     = null,
            source  = MediaMountEvent.ReceiverSource.CONTENT_OBSERVER,
        )
        onRootsChanged()
    }

    fun register() {
        try {
            context.contentResolver.registerContentObserver(
                ROOTS_URI,
                /* notifyForDescendants = */ false,
                this,
            )
        } catch (e: SecurityException) {
            // GrapheneOS (and potentially other hardened ROMs) deny access to the
            // external storage documents provider without a prior SAF grant.
            // Silently no-op — the observer simply won't fire on this platform.
            MediaMountEventLog.recordRegistrationFailure(
                context = context,
                reason  = e.message,
                source  = MediaMountEvent.ReceiverSource.CONTENT_OBSERVER,
            )
        }
    }

    fun unregister() {
        handler.removeCallbacksAndMessages(null)
        context.contentResolver.unregisterContentObserver(this)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        // The documents provider fires onChange in rapid bursts during a single
        // mount event. Debounce so we only act once the dust has settled.
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(debounced, DEBOUNCE_MS)
    }
}