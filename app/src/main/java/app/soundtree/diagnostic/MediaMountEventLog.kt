package app.soundtree.diagnostic

import android.content.Context
import android.content.Intent
import android.os.storage.StorageManager
import app.soundtree.storage.StorageVolumeHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * In-memory log of storage broadcast events captured by the app's receivers.
 *
 * ## Purpose
 * Diagnostic tool for probing whether GrapheneOS delivers [Intent.ACTION_MEDIA_MOUNTED]
 * and related broadcasts to both static (manifest-registered) and dynamic receivers.
 * Not persisted — events accumulate until the process dies.
 *
 * ## Usage
 * Call [record] from any receiver that handles storage broadcasts. Observe
 * [events] in the diagnostic dialog to see what arrived and from where.
 *
 * ## Capacity
 * Capped at [MAX_EVENTS] entries (newest-first). Oldest entries are dropped
 * when the cap is reached. In practice a user will never generate more than
 * a handful of events in a session, so the cap is a safety net only.
 */
object MediaMountEventLog {

    const val MAX_EVENTS = 200

    /** Sentinel used when StorageManager returns null for a mount path. */
    const val UUID_NO_VOLUME = "(no volume)"

    /** Sentinel used when StorageManager throws or the path is null. */
    const val UUID_RESOLUTION_FAILED = "(resolution failed)"

    /** Sentinel used when intent.data is null — no path to resolve from. */
    const val UUID_NO_PATH = "(no path in intent)"

    private val _events = MutableStateFlow<List<MediaMountEvent>>(emptyList())

    /** Observed by [app.soundtree.ui.settings.StorageMountEventLogDialog]. Newest entry first. */
    val events: StateFlow<List<MediaMountEvent>> = _events.asStateFlow()

    /**
     * Records a storage broadcast event.
     *
     * Safe to call from any thread. UUID resolution is performed inline using
     * [StorageManager] — this is a cheap synchronous lookup and acceptable
     * from a BroadcastReceiver context.
     *
     * @param context        Used for [StorageManager] access.
     * @param intent         The received broadcast intent.
     * @param source         Which receiver is recording this event.
     */
    fun record(
        context: Context,
        intent:  Intent,
        source:  MediaMountEvent.ReceiverSource,
    ) {
        val action    = intent.action ?: return
        val mountPath = intent.data?.path

        val resolvedUuid = when {
            mountPath == null -> UUID_NO_PATH
            else              -> resolveUuid(context, mountPath)
        }

        val event = MediaMountEvent(
            timestampMs    = System.currentTimeMillis(),
            action         = action,
            mountPath      = mountPath,
            resolvedUuid   = resolvedUuid,
            receiverSource = source,
        )

        append(event)
    }

    /**
     * Records a roots-change notification from [app.soundtree.storage.StorageRootsObserver].
     *
     * Unlike broadcast events, [ContentObserver.onChange] carries no mount path or
     * volume UUID — just the URI that changed. We synthesise a synthetic action
     * string for display purposes and record what we can.
     *
     * @param context  Used for [StorageManager] access (no-op here, but kept for consistency).
     * @param uri      The URI passed to [ContentObserver.onChange], if any.
     * @param source   Should always be [MediaMountEvent.ReceiverSource.CONTENT_OBSERVER].
     */
    fun recordRootsChange(
        context: Context,
        uri:     android.net.Uri?,
        source:  MediaMountEvent.ReceiverSource,
    ) {
        val event = MediaMountEvent(
            timestampMs    = System.currentTimeMillis(),
            action         = "ROOTS_CHANGED",
            mountPath      = uri?.toString(),
            resolvedUuid   = "(n/a — ContentObserver)",
            receiverSource = source,
        )

        append(event)
    }

    /**
     * Records a failed observer/receiver registration attempt.
     *
     * Useful for diagnosing platforms (e.g. GrapheneOS) that deny access to
     * the external storage documents provider without a prior SAF grant.
     * Shows up in the diagnostic dialog so the failure is visible without logcat.
     */
    fun recordRegistrationFailure(
        context: Context,
        reason:  String?,
        source:  MediaMountEvent.ReceiverSource,
    ) {
        val event = MediaMountEvent(
            timestampMs    = System.currentTimeMillis(),
            action         = "REGISTRATION_FAILED",
            mountPath      = null,
            resolvedUuid   = reason ?: "(no detail)",
            receiverSource = source,
        )
        append(event)
    }

    /** Clears all recorded events. */
    fun clear() {
        _events.value = emptyList()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun append(event: MediaMountEvent) {
        val updated = listOf(event) + _events.value
        _events.value = if (updated.size > MAX_EVENTS) updated.take(MAX_EVENTS) else updated
    }

    private fun resolveUuid(context: Context, mountPath: String): String {
        val sm = context.getSystemService(StorageManager::class.java)
        return runCatching {
            val sv = sm.getStorageVolume(File(mountPath))
                ?: return UUID_NO_VOLUME
            sv.uuid ?: StorageVolumeHelper.UUID_PRIMARY
        }.getOrElse { UUID_RESOLUTION_FAILED }
    }
}