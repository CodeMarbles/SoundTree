package com.treecast.app.service

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.treecast.app.R
import com.treecast.app.TreeCastApp
import com.treecast.app.data.repository.TreeCastRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that manages audio playback via Media3 ExoPlayer.
 *
 * Extending MediaSessionService gives us:
 *   - An automatic media-style foreground notification with transport controls
 *   - Lock screen / Now Playing integration (via MediaSessionCompat bridge)
 *   - Audio focus handling via ExoPlayer
 *   - Bluetooth headset button support
 *   - Headphone unplug pause
 *
 * Mark-aware transport:
 *   ExoPlayer wraps in [MarkAwarePlayer], a ForwardingPlayer that intercepts
 *   seekToPrevious / seekToNext — the standard commands wired to the lock screen
 *   and notification prev/next buttons on all Android versions — and routes them
 *   to mark-jumping logic instead of the default queue-navigation behaviour.
 *   This also ensures those buttons are always available (un-greyed) regardless
 *   of queue position.
 *
 * Custom session commands ([PlaybackCommands]):
 *   JUMP_PREV_MARK / JUMP_NEXT_MARK and ADD_MARK are additionally available as
 *   explicit custom commands (e.g. from the in-app UI via MediaController).
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var repo: TreeCastRepository

    // Coroutine scope for async mark DB operations triggered from outside the app.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Mark-aware ForwardingPlayer ───────────────────────────────────

    /**
     * Wraps ExoPlayer so that the standard SEEK_TO_PREVIOUS / SEEK_TO_NEXT
     * transport commands — exposed by the lock screen and notification controls
     * on every Android version via the Media3 → MediaSessionCompat bridge —
     * perform mark-jumping instead of playlist navigation.
     *
     * Reporting both commands as always available un-greys the lock screen
     * buttons regardless of queue position.
     */
    private inner class MarkAwarePlayer(player: Player) : androidx.media3.common.ForwardingPlayer(player) {

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(COMMAND_SEEK_TO_PREVIOUS)
                .add(COMMAND_SEEK_TO_NEXT)
                .build()

        override fun isCommandAvailable(command: @Player.Command Int): Boolean {
            if (command == COMMAND_SEEK_TO_PREVIOUS || command == COMMAND_SEEK_TO_NEXT) return true
            return super.isCommandAvailable(command)
        }

        override fun seekToPrevious() {
            serviceScope.launch { jumpMark(forward = false) }
        }

        override fun seekToNext() {
            serviceScope.launch { jumpMark(forward = true) }
        }
    }

    // ── Shared mark logic ─────────────────────────────────────────────

    /**
     * Seeks to the nearest mark before (forward=false) or after (forward=true)
     * the current playback position.
     *
     * A 500 ms dead-zone prevents getting stuck when positioned exactly on a mark.
     *
     * Jump-previous falls back to position 0 when no earlier mark exists,
     * giving an implicit "start of recording" marker (the equivalent of a
     * standard media prev button restart).
     *
     * Jump-next does nothing when no later mark exists — no wrap-around.
     *
     * All DB access runs on [Dispatchers.IO]; seeks are dispatched back to Main.
     */
    private suspend fun jumpMark(forward: Boolean) {
        val session     = mediaSession ?: return
        val player      = session.player
        val recordingId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val currentPos  = withContext(Dispatchers.Main) { player.currentPosition }

        val marks = withContext(Dispatchers.IO) {
            repo.getMarksForRecording(recordingId).first()
        }

        val targetMs: Long? = if (forward) {
            marks.filter { it.positionMs > currentPos + 500L }.minByOrNull { it.positionMs }?.positionMs
        } else {
            marks.filter { it.positionMs < currentPos - 500L }.maxByOrNull { it.positionMs }?.positionMs
                ?: 0L  // implicit 0-mark: fall back to start of recording
        }

        if (targetMs != null) {
            withContext(Dispatchers.Main) { player.seekTo(targetMs) }
        }
    }

    /** Inserts a new mark at the current playback position. */
    private suspend fun addMark() {
        val session     = mediaSession ?: return
        val recordingId = session.player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val posMs       = withContext(Dispatchers.Main) { session.player.currentPosition }
        withContext(Dispatchers.IO) { repo.addMark(recordingId, posMs) }
    }

    // ── Session callback ──────────────────────────────────────────────

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(PlaybackCommands.JUMP_PREV_MARK, Bundle.EMPTY))
                        .add(SessionCommand(PlaybackCommands.JUMP_NEXT_MARK, Bundle.EMPTY))
                        .add(SessionCommand(PlaybackCommands.ADD_MARK, Bundle.EMPTY))
                        .build()
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            serviceScope.launch {
                when (customCommand.customAction) {
                    PlaybackCommands.JUMP_PREV_MARK -> jumpMark(forward = false)
                    PlaybackCommands.JUMP_NEXT_MARK -> jumpMark(forward = true)
                    PlaybackCommands.ADD_MARK       -> addMark()
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    // ── Service lifecycle ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        repo = (application as TreeCastApp).repository

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus= */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Wrap in MarkAwarePlayer so lock screen prev/next trigger mark-jumping.
        val markAwarePlayer = MarkAwarePlayer(exoPlayer)

        mediaSession = MediaSession.Builder(this, markAwarePlayer)
            .setCallback(SessionCallback())
            .build()

        // Advertise jump-prev, jump-next, and add-mark as extra buttons in the
        // expanded media notification. Media3 inserts these alongside the
        // standard transport controls.
        mediaSession?.setCustomLayout(
            listOf(
                CommandButton.Builder()
                    .setDisplayName("Jump to previous mark")
                    .setIconResId(R.drawable.ic_jump_prev_mark)
                    .setSessionCommand(SessionCommand(PlaybackCommands.JUMP_PREV_MARK, Bundle.EMPTY))
                    .build(),
                CommandButton.Builder()
                    .setDisplayName("Jump to next mark")
                    .setIconResId(R.drawable.ic_jump_next_mark)
                    .setSessionCommand(SessionCommand(PlaybackCommands.JUMP_NEXT_MARK, Bundle.EMPTY))
                    .build(),
                CommandButton.Builder()
                    .setDisplayName("Add mark")
                    .setIconResId(R.drawable.ic_mark)
                    .setSessionCommand(SessionCommand(PlaybackCommands.ADD_MARK, Bundle.EMPTY))
                    .build()
            )
        )
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}