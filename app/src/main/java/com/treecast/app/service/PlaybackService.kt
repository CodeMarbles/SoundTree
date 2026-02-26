package com.treecast.app.service

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Foreground service that manages audio playback via Media3 ExoPlayer.
 *
 * Extending MediaSessionService gives us:
 *   - An automatic media-style foreground notification with transport controls
 *   - Lock screen / Now Playing integration
 *   - Audio focus handling (via ExoPlayer's built-in support)
 *   - Bluetooth headset button support
 *   - Headphone unplug pause (setHandleAudioBecomingNoisy)
 *
 * The service is started automatically when a MediaController connects to its
 * session token — no manual startService() call is required from the ViewModel.
 *
 * Lifecycle:
 *   - Stays alive while any MediaController is connected OR while playing.
 *   - If the user swipes the app away from Recents while paused, stopSelf()
 *     is called and the service (and notification) goes away cleanly.
 *   - If the user swipes away while playing, the service keeps running so
 *     playback continues — this is the whole point of the feature.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus= */ true          // pause/duck on phone call, other media, etc.
            )
            .setHandleAudioBecomingNoisy(true)        // pause when headphones unplugged
            .build()

        mediaSession = MediaSession.Builder(this, player).build()
    }

    /**
     * Called by the Media3 framework whenever a MediaController wants to
     * connect. Returning the session grants access; returning null rejects it.
     */
    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    /**
     * The user swiped the app away from Recents.
     * If we're paused there's no point keeping the service alive — kill it.
     * If we're playing, let it keep running (the notification lets the user
     * return or stop playback from outside the app).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}