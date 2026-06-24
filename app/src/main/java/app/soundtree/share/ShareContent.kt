package app.soundtree.share

/**
 * Describes what files will be included in a share intent.
 *
 * Passed from [app.soundtree.ui.share.ShareRecordingDialogFragment] to
 * [ShareManager.prepareIntent] to select the appropriate intent builder.
 *
 * ## Extension point — clips
 * When clip sharing is added, extend this sealed class:
 *
 *   data class ClipOnly(val clipId: Long) : ShareContent()
 *   data class ClipWithMetadata(val clipId: Long) : ShareContent()
 *
 * Add corresponding `when` branches in [ShareManager.prepareIntent].
 */
sealed class ShareContent {
    /** The recording's audio file only. */
    object AudioOnly : ShareContent()

    /** The recording's audio file plus a one-time JSON metadata export. */
    object AudioWithMetadata : ShareContent()

    /** A one-time JSON metadata export only (no audio). */
    object MetadataOnly : ShareContent()
}