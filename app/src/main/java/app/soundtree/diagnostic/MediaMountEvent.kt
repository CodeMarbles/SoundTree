package app.soundtree.diagnostic

/**
 * A single captured storage broadcast event.
 *
 * @property timestampMs    Epoch ms when the broadcast was received.
 * @property action         The intent action string (e.g. "android.intent.action.MEDIA_MOUNTED").
 * @property mountPath      The path from [android.content.Intent.getData], or null if absent.
 * @property resolvedUuid   The volume UUID resolved via StorageManager, or a sentinel string
 *                          if resolution failed (see [MediaMountEventLog] for sentinel values).
 * @property receiverSource Which receiver caught this event — useful for diagnosing whether
 *                          GrapheneOS delivers to static vs dynamic receivers differently.
 */
data class MediaMountEvent(
    val timestampMs:    Long,
    val action:         String,
    val mountPath:      String?,
    val resolvedUuid:   String,
    val receiverSource: ReceiverSource,
) {
    enum class ReceiverSource(val label: String) {
        /** [app.soundtree.receiver.StorageMountReceiver] — static, manifest-registered. */
        STATIC("static"),
        /** [app.soundtree.storage.StorageVolumeEventReceiver] — dynamic, registered in MainActivity. */
        DYNAMIC("dynamic"),
    }
}