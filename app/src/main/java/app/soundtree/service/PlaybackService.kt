package app.soundtree.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.soundtree.R
import app.soundtree.data.entities.MarkEntity
import app.soundtree.data.repository.SoundTreeRepository
import app.soundtree.service.PlaybackService.MarkAwarePlayer
import app.soundtree.ui.MainActivity
import app.soundtree.util.MarkJumpLogic
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 *   seekToPrevious / seekToNext and routes them to mark-jumping logic.
 *   COMMAND_SEEK_TO_PREVIOUS is always available (falls back to position 0).
 *   COMMAND_SEEK_TO_NEXT is only reported available when a forward mark exists,
 *   which greys out the lock screen / notification next button appropriately.
 *
 * [cachedMarks] is kept in sync with the DB via a coroutine Flow observer that
 * restarts whenever the current media item changes. Media3 queries
 * [getAvailableCommands] on every natural state change (play, pause, seek,
 * track transition), so the notification reflects the correct state at all
 * meaningful moments.
 *
 * Custom session commands ([PlaybackCommands]):
 *   JUMP_PREV_MARK / JUMP_NEXT_MARK and ADD_MARK remain registered in the
 *   session callback for potential future in-app use via sendCustomCommand.
 *   Only ADD_MARK is advertised in the notification custom layout — jump-prev
 *   and jump-next are redundant there since the standard prev/next buttons
 *   already perform mark-jumping via MarkAwarePlayer.
 */
class PlaybackService : MediaSessionService() {
    companion object;

    private var mediaSession: MediaSession? = null
    private lateinit var repo: SoundTreeRepository
    private lateinit var prefs: android.content.SharedPreferences

    // Coroutine scope for async mark DB operations triggered from outside the app.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Cached marks for the currently loaded recording. Updated reactively via
    // a Flow observer started in playerListener. MarkAwarePlayer reads this
    // synchronously in getAvailableCommands() / isCommandAvailable().
    private var cachedMarks: List<MarkEntity> = emptyList()
    private var marksJob: Job? = null

    // ── Mark-aware ForwardingPlayer ───────────────────────────────────

    /**
     * Wraps ExoPlayer so that the standard SEEK_TO_PREVIOUS / SEEK_TO_NEXT
     * commands — wired to lock screen and notification prev/next buttons on
     * all Android versions via the Media3 → MediaSessionCompat bridge —
     * perform mark-jumping instead of playlist navigation.
     *
     * SEEK_TO_PREVIOUS: always available (fallback = position 0).
     * SEEK_TO_NEXT: available only when a mark exists ahead of current position.
     */
    @UnstableApi
    private inner class MarkAwarePlayer(player: Player) : ForwardingPlayer(player) {

        override fun getAvailableCommands(): Player.Commands {
            val hasNextMark = MarkJumpLogic.findTarget(
                cachedMarks.map { it.positionMs }, currentPosition, forward = true, rewindThresholdMs = 0L
            ) is MarkJumpLogic.JumpTarget.ToMark
            return super.getAvailableCommands().buildUpon()
                .add(COMMAND_SEEK_TO_PREVIOUS)
                .apply { if (hasNextMark) add(COMMAND_SEEK_TO_NEXT) }
                .build()
        }

        override fun isCommandAvailable(command: @Player.Command Int): Boolean {
            if (command == COMMAND_SEEK_TO_PREVIOUS) return true
            if (command == COMMAND_SEEK_TO_NEXT) {
                return cachedMarks.any { it.positionMs > currentPosition + MarkJumpLogic.FORWARD_DEAD_ZONE_MS }
            }
            return super.isCommandAvailable(command)
        }

        override fun seekToPrevious() {
            serviceScope.launch { jumpMark(forward = false) }
        }

        override fun seekToNext() {
            serviceScope.launch { jumpMark(forward = true) }
        }
    }

    // ── Player listener — restarts marks observer on track change ─────

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(
            mediaItem: MediaItem?,
            reason: Int
        ) {
            val recordingId = mediaItem?.mediaId?.toLongOrNull()
            marksJob?.cancel()
            if (recordingId == null) {
                cachedMarks = emptyList()
                return
            }
            marksJob = serviceScope.launch {
                repo.getMarksForRecording(recordingId).collect { marks ->
                    cachedMarks = marks
                }
            }
        }
    }

    // ── Shared mark logic ─────────────────────────────────────────────

    /**
     * Seeks to the nearest mark before (forward=false) or after (forward=true)
     * the current playback position.
     *
     * A modifiable dead-zone (default 500ms) prevents getting stuck when positioned exactly on a mark.
     * Jump-previous falls back to position 0 when no earlier mark exists.
     * Jump-next does nothing when no later mark exists.
     */
    private suspend fun jumpMark(forward: Boolean) {
        val player = mediaSession?.player ?: return
        val posMs  = withContext(Dispatchers.Main) { player.currentPosition }
        val threshMs = (prefs.getFloat(
            MarkJumpLogic.PREF_REWIND_THRESHOLD_SECS,
            MarkJumpLogic.DEFAULT_REWIND_THRESHOLD_SECS
        ) * 1000f).toLong()
        val target = MarkJumpLogic.findTarget(
            marks             = cachedMarks.map { it.positionMs },
            currentPositionMs = posMs,
            forward           = forward,
            rewindThresholdMs = threshMs
        )
        val seekTo = when (target) {
            is MarkJumpLogic.JumpTarget.ToMark       -> target.positionMs
            is MarkJumpLogic.JumpTarget.ToTrackStart -> 0L
            is MarkJumpLogic.JumpTarget.NoTarget     -> return
        }
        withContext(Dispatchers.Main) { player.seekTo(seekTo) }
    }

    /** Inserts a new mark at the current playback position. */
    private suspend fun addMark() {
        val player      = mediaSession?.player ?: return
        val recordingId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val posMs       = withContext(Dispatchers.Main) { player.currentPosition }
        withContext(Dispatchers.IO) { repo.addMark(recordingId, posMs) }
    }

    // ── Session callback ──────────────────────────────────────────────

    private inner class SessionCallback : MediaSession.Callback {

        @OptIn(UnstableApi::class)
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

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        prefs = getSharedPreferences("soundtree_settings", android.content.Context.MODE_PRIVATE)
        repo = (application as app.soundtree.SoundTreeApp).repository

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

        // Watch for track changes so we can restart the marks Flow observer.
        markAwarePlayer.addListener(playerListener)

        mediaSession = MediaSession.Builder(this, markAwarePlayer)
            .setCallback(SessionCallback())
            .build()

        // Only Add Mark is advertised as a custom notification button.
        // Jump-prev and jump-next are handled by the standard prev/next transport
        // buttons via MarkAwarePlayer, so adding them here would be redundant and
        // would crowd out Add Mark from the visible notification action slots.
        mediaSession?.setCustomLayout(
            listOf(
                CommandButton.Builder()
                    .setDisplayName("Add mark")
                    .setIconResId(R.drawable.ic_mark)
                    .setSessionCommand(SessionCommand(PlaybackCommands.ADD_MARK, Bundle.EMPTY))
                    .build()
            )
        )

        val listenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_NAVIGATE_TO_PAGE, MainActivity.PAGE_LISTEN)
        }
        val listenPi = PendingIntent.getActivity(
            this,
            AppNotifications.REQUEST_PLAYBACK_SESSION_ACTIVITY,
            listenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Tapping the notification body (or the lock screen card) navigates back to
        // the Listen tab rather than being a no-op. setSessionActivity is the correct
        // Media3 hook — it feeds the content intent into the auto-generated
        // media-style notification, the lock screen, and any future Wear OS surface.
        mediaSession?.setSessionActivity(listenPi)
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        marksJob?.cancel()
        serviceScope.cancel()
        mediaSession?.run {
            player.removeListener(playerListener)
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}