package app.treecast.service

/**
 * Custom [androidx.media3.session.SessionCommand] action strings for
 * mark-aware playback operations.
 *
 * These are sent from [app.treecast.ui.MainViewModel] via
 * [androidx.media3.session.MediaController.sendCustomCommand] and handled
 * in [PlaybackService]'s [androidx.media3.session.MediaSession.Callback],
 * enabling jump-to-mark from notification and lock screen controls.
 */
object PlaybackCommands {
    const val JUMP_PREV_MARK = "app.treecast.JUMP_PREV_MARK"
    const val JUMP_NEXT_MARK = "app.treecast.JUMP_NEXT_MARK"
    const val ADD_MARK       = "app.treecast.ADD_MARK"
}