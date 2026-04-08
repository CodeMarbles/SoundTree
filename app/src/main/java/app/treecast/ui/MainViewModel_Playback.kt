package app.treecast.ui

// ─────────────────────────────────────────────────────────────────────────────
// MainViewModel_Playback.kt
//
// Extension functions on MainViewModel covering all Media3 / playback concerns
// ─────────────────────────────────────────────────────────────────────────────

import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import app.treecast.R
import app.treecast.data.entities.MarkEntity
import app.treecast.data.entities.RecordingEntity
import app.treecast.util.MarkJumpLogic
import app.treecast.util.PlaybackPositionHelper
import app.treecast.util.bitmapToPngByteArray
import app.treecast.util.buildTopicArtwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "MainViewModel"

// ── Core playback commands ────────────────────────────────────────────────────

fun MainViewModel.play(recording: RecordingEntity) {
    val controller = mediaController
    if (controller == null) {
        Log.w(TAG, "play() called before MediaController connected")
        return
    }

    stopProgressPolling()

    // ── Save outgoing recording's position before switching ───────────────
    // The normal save path (onIsPlayingChanged → saveCurrentPosition) is
    // unreliable during a recording swap: by the time the listener fires,
    // _nowPlaying already points to the new recording, and the controller's
    // isPlaying/playbackState no longer reflect the outgoing item.
    // Capture both values synchronously here, before any state mutation.
    val outgoing = _nowPlaying.value
    if (outgoing != null && outgoing.recording.id != recording.id) {
        val outgoingPos = mediaController?.currentPosition ?: outgoing.positionMs
        val outgoingRec = outgoing.recording
        viewModelScope.launch {
            if (PlaybackPositionHelper.shouldPersistPosition(outgoingRec, prefs)) {
                repo.updatePlayback(outgoingRec.id, outgoingPos, false)
            } else {
                repo.updatePlayback(outgoingRec.id, 0L, false)
            }
        }
    }

    val uri       = Uri.fromFile(File(recording.filePath))
    val topic     = allTopics.value.firstOrNull { it.id == recording.topicId }
    val topicName = topic?.name
        ?: getApplication<android.app.Application>().getString(R.string.topic_label_unsorted)

    val artwork      = topic?.let { buildTopicArtwork(it.color, it.icon) }
    val artworkBytes = artwork?.let { bitmapToPngByteArray(it) }

    val mediaItem = MediaItem.Builder()
        .setUri(uri)
        .setMediaId(recording.id.toString())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(recording.title)
                .setArtist(topicName)
                .apply {
                    artworkBytes?.let {
                        setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    }
                }
                .build()
        )
        .build()

    // below line might be cause of the stop bug
    //controller.stop()
    controller.setMediaItem(mediaItem)
    controller.prepare()
    controller.setPlaybackParameters(PlaybackParameters(_playbackSpeed.value))

    // Determine where playback should start.
    //
    // Priority 1: if this is the same recording already loaded in the session
    // (e.g. the user paused and tapped play again), restore the in-memory
    // position regardless of the DB persist setting — this is the "current
    // session exception" and requires no DB read.
    //
    // Priority 2: use PlaybackPositionHelper.effectivePositionMs(), which
    // reads the DB value and applies both the remember-mode setting and the
    // near-end dead-zone rule.
    val startPos: Long = run {
        val inSession = _nowPlaying.value
        if (inSession != null && inSession.recording.id == recording.id) {
            inSession.positionMs
        } else {
            PlaybackPositionHelper.effectivePositionMs(recording, prefs)
        }
    }
    if (startPos > 0L) {
        controller.seekTo(startPos)
    }
    controller.play()

    _nowPlaying.value = NowPlayingState(
        recording  = recording,
        isPlaying  = true,
        positionMs = startPos,
        durationMs = recording.durationMs
    )

    startProgressPolling()
    startObservingMarks(recording.id)
    _selectedMarkId.value          = null
    _playbackMarkNudgeLocked.value = true

    loadWaveform(recording.id, recording.filePath)
}

fun MainViewModel.togglePlayPause() {
    val controller = mediaController ?: return
    if (controller.isPlaying) controller.pause() else controller.play()
}

fun MainViewModel.requestToggleRecordingPause() {
    _toggleRecordingPauseEvent.tryEmit(Unit)
}

fun MainViewModel.seekTo(posMs: Long) {
    mediaController?.seekTo(posMs)
    _nowPlaying.value = _nowPlaying.value?.copy(positionMs = posMs)
}

/**
 * Like [seekTo] but also fires [markJumpMs] so the Listen tab's MLWV
 * can jump-scroll to the correct bar regardless of splitter state.
 * Use this everywhere the seek target IS a mark position.
 */
fun MainViewModel.seekToMark(posMs: Long) {
    seekTo(posMs)
    _markJumpMs.tryEmit(posMs)
}

/**
 * Single entry point for all playback mark jumps.
 *
 * @param forward  true = jump to next mark; false = jump to prev (or track start).
 * @param select   true = select the landed mark and unlock nudging (mini player);
 *                 false = seek only, no selection change (Listen tab).
 */
fun MainViewModel.jumpMark(forward: Boolean, select: Boolean) {
    val posMs = mediaController?.currentPosition
        ?: _nowPlaying.value?.positionMs ?: return
    when (val target = MarkJumpLogic.findTarget(
        marks             = _marks.value.map { it.positionMs },
        currentPositionMs = posMs,
        forward           = forward,
        rewindThresholdMs = markRewindThresholdMs
    )) {
        is MarkJumpLogic.JumpTarget.ToMark -> {
            seekToMark(target.positionMs)
            if (select) {
                _marks.value.firstOrNull { it.positionMs == target.positionMs }
                    ?.let { mark ->
                        _selectedMarkId.value          = mark.id
                        _playbackMarkNudgeLocked.value = false
                    }
            }
        }
        is MarkJumpLogic.JumpTarget.ToTrackStart -> seekToMark(0L)
        is MarkJumpLogic.JumpTarget.NoTarget     -> { /* next-jump with no marks ahead; no-op */ }
    }
}

fun MainViewModel.skipBack() {
    val currentPos = mediaController?.currentPosition
        ?: _nowPlaying.value?.positionMs
        ?: return
    val target = (currentPos - scrubBackSecs.value * 1000L).coerceAtLeast(0L)
    seekTo(target)
}

fun MainViewModel.skipForward() {
    val dur = _nowPlaying.value?.durationMs ?: return
    val currentPos = mediaController?.currentPosition
        ?: _nowPlaying.value?.positionMs
        ?: return
    val target = (currentPos + scrubForwardSecs.value * 1000L).coerceAtMost(dur)
    seekTo(target)
}

/**
 * Stops playback and fully clears the player state, returning the app to
 * the same state as if it had just been launched with no recording selected.
 *
 * Called by the Mini Player close (×) button.
 */
fun MainViewModel.stopAndClear() {
    mediaController?.stop()
    _nowPlaying.value          = null
    _selectedRecordingId.value = -1L
    _playerPillMinimized.value = false   // restore pill-only users to widget view
    marksJob?.cancel()
    _marks.value          = emptyList()
    _selectedMarkId.value = null
}

// ── Mark lifecycle ────────────────────────────────────────────────────────────

fun MainViewModel.addMark() {
    val posMs = mediaController?.currentPosition
        ?: _nowPlaying.value?.positionMs
        ?: return
    val recId = _nowPlaying.value?.recording?.id ?: return
    viewModelScope.launch {
        val newId = repo.addMark(recId, posMs)
        _selectedMarkId.value          = newId
        _playbackMarkNudgeLocked.value = false
    }
}

fun MainViewModel.deleteSelectedMark() {
    val id    = _selectedMarkId.value ?: return
    val recId = _nowPlaying.value?.recording?.id ?: return
    viewModelScope.launch {
        repo.deleteMark(id, recId)
        _selectedMarkId.value = null
    }
}

fun MainViewModel.selectMark(id: Long?) {
    _selectedMarkId.value = id
}

/**
 * Returns a live flow of marks for any recording by ID.
 * Used by [RecordingDetailsDialogFragment] to populate the condensed
 * waveform overview independently of the now-playing state.
 */
fun MainViewModel.getMarksForRecording(id: Long): Flow<List<MarkEntity>> =
    repo.getMarksForRecording(id)

// ── Playback mark nudge ───────────────────────────────────────────────────────
// Playback nudge writes directly to the DB; commit just clears selection.

fun MainViewModel.nudgePlaybackMarkBack() {
    if (_playbackMarkNudgeLocked.value) return
    val id    = _selectedMarkId.value ?: return
    val recId = _nowPlaying.value?.recording?.id ?: return
    val deltaMs = -(_markNudgeSecs.value * 1000L).toLong()
    viewModelScope.launch { repo.nudgeMark(id, deltaMs, recId) }
}

fun MainViewModel.nudgePlaybackMarkForward() {
    if (_playbackMarkNudgeLocked.value) return
    val id    = _selectedMarkId.value ?: return
    val recId = _nowPlaying.value?.recording?.id ?: return
    val deltaMs = (_markNudgeSecs.value * 1000L).toLong()
    viewModelScope.launch { repo.nudgeMark(id, deltaMs, recId) }
}

/** Clears selection and re-locks nudging until the next jump-and-select. */
fun MainViewModel.commitPlaybackMarkNudge() {
    _playbackMarkNudgeLocked.value = true
    _selectedMarkId.value          = null
}

// ── Internal helpers ──────────────────────────────────────────────────────────

internal fun MainViewModel.startObservingMarks(recordingId: Long) {
    marksJob?.cancel()
    marksJob = viewModelScope.launch {
        repo.getMarksForRecording(recordingId).collect { _marks.value = it }
    }
}

internal fun MainViewModel.startProgressPolling() {
    stopProgressPolling()
    progressJob = viewModelScope.launch {
        while (isActive) {
            delay(500)
            val controller = mediaController ?: break
            val pos        = controller.currentPosition
            _nowPlaying.value = _nowPlaying.value?.copy(positionMs = pos)
        }
    }
}

internal fun MainViewModel.stopProgressPolling() {
    progressJob?.cancel()
    progressJob = null
}

internal suspend fun MainViewModel.saveCurrentPosition() {
    val state = _nowPlaying.value ?: return
    val pos   = mediaController?.currentPosition ?: state.positionMs

    if (PlaybackPositionHelper.shouldPersistPosition(state.recording, prefs)) {
        repo.updatePlayback(state.recording.id, pos, false)
    } else {
        // Zero out any previously-stored position so it cannot resurface if
        // the user later changes the setting back to Always/Long Only.
        repo.updatePlayback(state.recording.id, 0L, false)
    }
}