package app.soundtree.ui

// ─────────────────────────────────────────────────────────────────────────────
// MainViewModel_Recordings.kt
//
// Extension functions on MainViewModel covering two related concerns:
//
// 1. Stored recording CRUD:
//
// 2. Live recording state — pushed from RecordFragment while a recording
//    session is in progress:
//
// Cross-domain note: moveRecording() and renameRecording() also patch
// _nowPlaying in-memory so the Listen tab header updates immediately without
// waiting for a DB re-query. This is intentional — MainViewModel is the right
// place for cross-domain orchestration, and extension functions have full
// access to internal state.
// ─────────────────────────────────────────────────────────────────────────────

import androidx.lifecycle.viewModelScope
import app.soundtree.data.entities.RecordingEntity
import app.soundtree.service.RecordingService
import app.soundtree.ui.MainViewModel.NudgeEvent
import app.soundtree.storage.StorageVolumeHelper
import app.soundtree.worker.WaveformWorker
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

// ── Stored recording CRUD ─────────────────────────────────────────────────────

/**
 * Saves a recording with no marks. Kept for call sites that don't need mark
 * support (currently none, but keeps the API flexible).
 */
fun MainViewModel.saveRecording(
    filePath: String,
    durationMs: Long,
    fileSizeBytes: Long,
    title: String,
    topicId: Long? = null,
): Deferred<Long> = viewModelScope.async {
    repo.saveRecording(filePath, durationMs, fileSizeBytes, title, topicId)
}

/**
 * Saves a recording and flushes any marks dropped during that session
 * to the database in a single operation. This is the primary save path
 * called from [RecordFragment.stopAndSave].
 *
 * If [markTimestamps] is empty the behaviour is identical to [saveRecording].
 */
fun MainViewModel.saveRecordingWithMarks(
    filePath: String,
    durationMs: Long,
    fileSizeBytes: Long,
    title: String,
    topicId: Long? = null,
    markTimestamps: List<Long>,
    storageVolumeUuid: String = StorageVolumeHelper.UUID_PRIMARY,
    createdAt: Long = System.currentTimeMillis(),
): Deferred<Long> = viewModelScope.async {
    val recordingId = repo.saveRecordingWithMarks(
        filePath          = filePath,
        durationMs        = durationMs,
        fileSizeBytes     = fileSizeBytes,
        title             = title,
        topicId           = topicId,
        markTimestamps    = markTimestamps,
        storageVolumeUuid = storageVolumeUuid,
        createdAt         = createdAt
    )
    WaveformWorker.enqueue(
        context           = getApplication(),
        recordingId       = recordingId,
        filePath          = filePath,
        storageVolumeUuid = storageVolumeUuid,
        createdAt         = createdAt,
    )
    recordingId
}

fun MainViewModel.deleteRecording(r: RecordingEntity) = viewModelScope.launch {
    repo.deleteRecording(r)
    // Delete the cache file from whichever volume the recording lived on.
    // If the volume is currently unmounted the cache file stays on disk but
    // becomes orphaned — acceptable, as the recording itself is also gone.
    waveformCacheFor(r.storageVolumeUuid)?.delete(r.id, r.createdAt)
}

fun MainViewModel.moveRecording(id: Long, topicId: Long?) = viewModelScope.launch {
    repo.moveRecording(id, topicId)
    // Keep nowPlaying in sync so observers (Listen tab header, mini player)
    // see the new topic immediately without waiting for a DB re-query.
    if (_nowPlaying.value?.recording?.id == id) {
        _nowPlaying.value = _nowPlaying.value?.copy(
            recording = _nowPlaying.value!!.recording.copy(topicId = topicId)
        )
    }
}

fun MainViewModel.renameRecording(id: Long, title: String) = viewModelScope.launch {
    repo.renameRecording(id, title)
    // Keep nowPlaying in sync so the Listen tab header updates immediately
    // without waiting for a DB re-query — mirrors how moveRecording() works.
    if (_nowPlaying.value?.recording?.id == id) {
        _nowPlaying.value = _nowPlaying.value?.copy(
            recording = _nowPlaying.value!!.recording.copy(title = title)
        )
    }
}

fun MainViewModel.setFavourite(id: Long, fav: Boolean) =
    viewModelScope.launch { repo.setFavourite(id, fav) }

// ── Live recording state (pushed from RecordFragment) ─────────────────────────

fun MainViewModel.setRecordingState(state: RecordingService.State) {
    _recordingState.value = state
    if (state == RecordingService.State.IDLE) {
        _markNudgeLocked.value              = false
        _selectedRecordingMarkIndex.value   = -1
    }
}

fun MainViewModel.setRecordingTopicId(topicId: Long?) {
    _recordingTopicId.value = topicId
}

fun MainViewModel.setRecordingElapsedMs(ms: Long) {
    _recordingElapsedMs.value = ms
}

fun MainViewModel.setLiveAmplitude(amplitude: Float) {
    _liveAmplitude.value = amplitude
}

fun MainViewModel.setRecordingMarks(marks: List<Long>) {
    val prev = _recordingMarks.value
    _recordingMarks.value = marks
    // When a new mark is dropped (list grew), select the new last mark
    // and reset the nudge lock so it can be nudged immediately.
    if (marks.size > prev.size) {
        _selectedRecordingMarkIndex.value = marks.lastIndex
        _markNudgeLocked.value            = false
    }
}

fun MainViewModel.selectRecordingMark(index: Int) {
    val marks = _recordingMarks.value
    if (index < 0 || index >= marks.size) return
    _selectedRecordingMarkIndex.value = index
    // Selecting a mark unlocks nudging (user is clearly intending to refine it).
    _markNudgeLocked.value = false
}

fun MainViewModel.deleteSelectedRecordingMark() {
    val idx = _selectedRecordingMarkIndex.value.takeIf { it >= 0 } ?: return
    _deleteMarkEvent.tryEmit(idx)
    // Optimistically update ViewModel state immediately
    val marks = _recordingMarks.value.toMutableList()
    if (idx !in marks.indices) return
    marks.removeAt(idx)
    _recordingMarks.value = marks
    _selectedRecordingMarkIndex.value = -1
    resetMarkNudgeLock()
}

fun MainViewModel.resetMarkNudgeLock() {
    _markNudgeLocked.value = false
}

// ── Recording mark nudge events ───────────────────────────────────────────────
// Emitted here; observed by RecordFragment which forwards them to the service.

fun MainViewModel.requestNudgeBack() {
    if (_markNudgeLocked.value) return
    val marks = _recordingMarks.value
    if (marks.isEmpty()) return
    val idx = _selectedRecordingMarkIndex.value.takeIf { it >= 0 } ?: marks.lastIndex
    _nudgeBackEvent.tryEmit(NudgeEvent(_markNudgeSecs.value, idx))
}

fun MainViewModel.requestNudgeForward() {
    if (_markNudgeLocked.value) return
    val marks = _recordingMarks.value
    if (marks.isEmpty()) return
    val idx = _selectedRecordingMarkIndex.value.takeIf { it >= 0 } ?: marks.lastIndex
    _nudgeForwardEvent.tryEmit(NudgeEvent(_markNudgeSecs.value, idx))
}