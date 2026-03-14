package com.treecast.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.treecast.app.R
import com.treecast.app.TreeCastApp
import com.treecast.app.data.entities.MarkEntity
import com.treecast.app.data.entities.RecordingEntity
import com.treecast.app.data.entities.TopicEntity
import com.treecast.app.data.repository.TreeBuilder
import com.treecast.app.data.repository.TreeCastRepository
import com.treecast.app.data.repository.TreeItem
import com.treecast.app.service.PlaybackCommands
import com.treecast.app.service.PlaybackService
import com.treecast.app.service.RecordingService
import com.treecast.app.util.AppVolume
import com.treecast.app.util.StorageVolumeHelper
import com.treecast.app.util.WaveformCache
import com.treecast.app.util.WaveformExtractor
import com.treecast.app.util.bitmapToPngByteArray
import com.treecast.app.util.buildTopicArtwork
import com.treecast.app.worker.WaveformWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

data class NowPlayingState(
    val recording:  RecordingEntity,
    val isPlaying:  Boolean = false,
    val positionMs: Long    = 0L,
    val durationMs: Long    = 0L
)

/** Summary of a single waveform processing job for display in the Settings tab. */
data class ProcessingJobInfo(
    val id: java.util.UUID,
    val recordingId: Long,
    val state: WorkInfo.State,
    val completedAt: Long? = null
)

/** Snapshot of the waveform processing queue shown in the Settings tab. */
data class ProcessingStatus(
    val active: ProcessingJobInfo?,
    val pending: List<ProcessingJobInfo>,
    /**
     * All completed/failed jobs since the last app launch. Memory-only —
     * cleared on process restart. No size cap while in testing mode.
     */
    val recent: List<ProcessingJobInfo>
) {
    companion object {
        val IDLE = ProcessingStatus(null, emptyList(), emptyList())
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: TreeCastRepository = (app as TreeCastApp).repository

    private val prefs: SharedPreferences =
        app.getSharedPreferences("treecast_settings", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "MainViewModel"
        private const val PREF_THEME_MODE = "theme_mode"
        private const val PREF_AUTO_NAVIGATE      = "auto_navigate_to_listen"
        private const val PREF_SCRUB_BACK_SECS    = "scrub_back_secs"
        private const val PREF_SCRUB_FORWARD_SECS = "scrub_forward_secs"
        private const val PREF_JUMP_TO_LIBRARY    = "jump_to_library_on_save"
        private const val PREF_DEFAULT_STORAGE_UUID = "default_storage_uuid"
        private const val PREF_LAYOUT_ORDER    = "layout_element_order"
        private const val PREF_SHOW_TITLE_BAR  = "show_title_bar"
        private const val PREF_MARK_REWIND_THRESHOLD = "mark_rewind_threshold_secs"
        private const val PREF_RECORDER_WIDGET_VISIBILITY  = "recorder_widget_visibility"
        private const val PREF_HIDE_RECORDER_ON_RECORD_TAB = "hide_recorder_on_record_tab"
        private const val PREF_HIDE_PLAYER_ON_LISTEN_TAB   = "hide_player_on_listen_tab"
        private const val PREF_SHOW_RECORD_MARK_TIMESTAMP = "show_record_mark_timestamp"
        private const val PREF_MARK_NUDGE_SECS            = "mark_nudge_secs"
        private const val PREF_COLLAPSED_TOPIC_IDS = "collapsed_topic_ids"
    }

    // ── Session ───────────────────────────────────────────────────────
    private var currentSessionId: Long = -1L
    fun onAppOpen()  = viewModelScope.launch { currentSessionId = repo.openSession() }

    fun onAppClose() = viewModelScope.launch {
        saveCurrentPosition()
        if (currentSessionId != -1L) repo.closeSession(currentSessionId)
    }

    suspend fun getLastSession() = repo.getLastSession()
    suspend fun getLastClosedSession() = repo.getLastClosedSession()
    suspend fun getTotalRecordingTime() = repo.getTotalRecordingTime()

    // ── Top title ─────────────────────────────────────────────────────
    private val _topTitle = MutableStateFlow("Record")
    val topTitle: StateFlow<String> = _topTitle
    fun setTopTitle(title: String) { _topTitle.value = title }

    // ── Now Playing ───────────────────────────────────────────────────
    private val _nowPlaying = MutableStateFlow<NowPlayingState?>(null)
    val nowPlaying: StateFlow<NowPlayingState?> = _nowPlaying

    // ── Media3 MediaController ────────────────────────────────────────
    private val controllerFuture: ListenableFuture<MediaController>
    private var mediaController: MediaController? = null
    private var progressJob: Job? = null

    // ── Recording State ───────────────────────────────────────────────────────

    // Current state of RecordingService — pushed by RecordFragment.
    private val _recordingState = MutableStateFlow(RecordingService.State.IDLE)
    val recordingState: StateFlow<RecordingService.State> = _recordingState

    fun setRecordingState(state: RecordingService.State) {
        _recordingState.value = state
        if (state == RecordingService.State.IDLE) {
            _markNudgeLocked.value = false
            _selectedRecordingMarkIndex.value = -1
        }
    }

    // Add alongside the other recording state fields (_recordingState, etc.):

    private val _recordingTopicId = MutableStateFlow<Long?>(null)
    val recordingTopicId: StateFlow<Long?> = _recordingTopicId

    fun setRecordingTopicId(topicId: Long?) {
        _recordingTopicId.value = topicId
    }

    // Current elapsed recording time in ms — pushed by RecordFragment.
    private val _recordingElapsedMs = MutableStateFlow(0L)
    val recordingElapsedMs: StateFlow<Long> = _recordingElapsedMs
    fun setRecordingElapsedMs(ms: Long) { _recordingElapsedMs.value = ms }

    // Current pending mark timestamps — pushed by RecordFragment.
    private val _recordingMarks = MutableStateFlow<List<Long>>(emptyList())
    val recordingMarks: StateFlow<List<Long>> = _recordingMarks

    fun setRecordingMarks(marks: List<Long>) {
        val prev = _recordingMarks.value
        _recordingMarks.value = marks
        // When a new mark is dropped (list grew), select the new last mark
        // and reset the nudge lock so it can be nudged immediately.
        if (marks.size > prev.size) {
            _selectedRecordingMarkIndex.value = marks.lastIndex
            _markNudgeLocked.value = false
        }
    }

    /** Index into recordingMarks of the mark currently targeted by nudge controls.
     *  -1 means no explicit selection (UI falls back to displaying last mark as teal
     *  but nudge buttons remain in their locked/unlocked state independently). */
    private val _selectedRecordingMarkIndex = MutableStateFlow(-1)
    val selectedRecordingMarkIndex: StateFlow<Int> = _selectedRecordingMarkIndex

    fun selectRecordingMark(index: Int) {
        val marks = _recordingMarks.value
        if (index < 0 || index >= marks.size) return
        _selectedRecordingMarkIndex.value = index
        // Selecting a mark unlocks nudging (user is clearly intending to refine it)
        _markNudgeLocked.value = false
    }

    init {
        val token = SessionToken(
            app,
            ComponentName(app, PlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(app, token).buildAsync()
        controllerFuture.addListener(
            {
                try {
                    mediaController = controllerFuture.get().also {
                        it.addListener(playerListener)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "MediaController connection failed: ${e.message}")
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    private val playerListener = object : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Guard: if we just started a new recording (isPlaying=true in state) but
            // receive a stale isPlaying=false from the previous recording ending,
            // don't let it overwrite the new recording's in-flight state.
            if (!isPlaying && _nowPlaying.value?.isPlaying == true) {
                // Check if the controller actually agrees we're not playing
                if (mediaController?.isPlaying == false &&
                    mediaController?.playbackState != Player.STATE_BUFFERING) {
                    _nowPlaying.value = _nowPlaying.value?.copy(isPlaying = false) ?: return
                    stopProgressPolling()
                    viewModelScope.launch { saveCurrentPosition() }
                }
                return
            }
            _nowPlaying.value = _nowPlaying.value?.copy(isPlaying = isPlaying) ?: return
            if (isPlaying) {
                startProgressPolling(_nowPlaying.value?.recording?.id ?: return)
            } else {
                stopProgressPolling()
                viewModelScope.launch { saveCurrentPosition() }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                val recId = _nowPlaying.value?.recording?.id ?: return
                viewModelScope.launch { repo.updatePlayback(recId, 0L, true) }
                _nowPlaying.value = _nowPlaying.value?.copy(isPlaying = false, positionMs = 0L)
                stopProgressPolling()
            }
        }
    }

    // ── Playback commands ─────────────────────────────────────────────

    fun play(recording: RecordingEntity) {
        val controller = mediaController
        if (controller == null) {
            Log.w(TAG, "play() called before MediaController connected")
            return
        }

        stopProgressPolling()

        val uri = Uri.fromFile(File(recording.filePath))
        val topic = allTopics.value.firstOrNull { it.id == recording.topicId }
        val topicName = topic?.name ?: getApplication<Application>().getString(R.string.unsorted)

        val artwork = topic?.let { buildTopicArtwork(it.color, it.icon) }
        val artworkBytes = artwork?.let { bitmapToPngByteArray(it) }

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(recording.id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(recording.title)
                    .setArtist(topicName)
                    .apply { artworkBytes?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
                    .build()
            )
            .build()

        controller.stop()
        controller.setMediaItem(mediaItem)
        controller.prepare()

        if (recording.playbackPositionMs > 0L) {
            controller.seekTo(recording.playbackPositionMs)
        }
        controller.play()

        _nowPlaying.value = NowPlayingState(
            recording  = recording,
            isPlaying  = true,
            positionMs = recording.playbackPositionMs,
            durationMs = recording.durationMs
        )

        startProgressPolling(recording.id)
        startObservingMarks(recording.id)
        _selectedMarkId.value = null           // clear stale selection from previous recording
        _playbackMarkNudgeLocked.value = true  // re-lock nudge for the fresh recording
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    private val _toggleRecordingPauseEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val toggleRecordingPauseEvent: SharedFlow<Unit> = _toggleRecordingPauseEvent

    fun requestToggleRecordingPause() {
        _toggleRecordingPauseEvent.tryEmit(Unit)
    }


    fun seekTo(posMs: Long) {
        mediaController?.seekTo(posMs)
        _nowPlaying.value = _nowPlaying.value?.copy(positionMs = posMs)
    }

    fun skipBack() {
        val currentPos = mediaController?.currentPosition
            ?: _nowPlaying.value?.positionMs
            ?: return
        val target = (currentPos - scrubBackSecs.value * 1000L).coerceAtLeast(0L)
        seekTo(target)
    }

    fun skipForward() {
        val dur = _nowPlaying.value?.durationMs ?: return
        val currentPos = mediaController?.currentPosition
            ?: _nowPlaying.value?.positionMs
            ?: return
        val target = (currentPos + scrubForwardSecs.value * 1000L).coerceAtMost(dur)
        seekTo(target)
    }

    fun jumpToPrevMark() {
        mediaController?.sendCustomCommand(
            SessionCommand(PlaybackCommands.JUMP_PREV_MARK, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }

    fun jumpToNextMark() {
        mediaController?.sendCustomCommand(
            SessionCommand(PlaybackCommands.JUMP_NEXT_MARK, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }

    // ── Position polling ──────────────────────────────────────────────

    private fun startProgressPolling(recordingId: Long) {
        stopProgressPolling()
        progressJob = viewModelScope.launch {
            while (isActive) {
                delay(500)
                val controller = mediaController ?: break
                val pos = controller.currentPosition
                _nowPlaying.value = _nowPlaying.value?.copy(positionMs = pos)
                repo.updatePlayback(recordingId, pos, false)
            }
        }
    }

    private fun stopProgressPolling() {
        progressJob?.cancel()
        progressJob = null
    }

    private suspend fun saveCurrentPosition() {
        val state = _nowPlaying.value ?: return
        val pos = mediaController?.currentPosition ?: state.positionMs
        repo.updatePlayback(state.recording.id, pos, false)
    }

    // ── Cleanup ───────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        stopProgressPolling()
        marksJob?.cancel()
        MediaController.releaseFuture(controllerFuture)
        waveformJob?.cancel()
    }

    // ── Tree ──────────────────────────────────────────────────────────
    // Collapse state is UI-only — persisted in SharedPreferences, never in the DB.
    // Stored as a comma-separated string of Long IDs, e.g. "1,4,17".
    private val _collapsedIds = MutableStateFlow<Set<Long>>(
        prefs.getString(PREF_COLLAPSED_TOPIC_IDS, "")
            ?.split(",")
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet()
            ?: emptySet()
    )

    val treeItems: StateFlow<List<TreeItem>> = repo.getTreeFlow()
        .combine(_collapsedIds) { roots, collapsed -> TreeBuilder.flatten(roots, collapsed) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun toggleCollapse(topicId: Long, currentlyCollapsed: Boolean) {
        val updated = if (currentlyCollapsed)
            _collapsedIds.value - topicId else _collapsedIds.value + topicId
        _collapsedIds.value = updated
        prefs.edit()
            .putString(PREF_COLLAPSED_TOPIC_IDS, updated.joinToString(","))
            .apply()
    }

    // ── Recordings ────────────────────────────────────────────────────
    val allRecordings: StateFlow<List<RecordingEntity>> = repo.getAllRecordings()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val inbox: StateFlow<List<RecordingEntity>> = repo.getInbox()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchResults: StateFlow<List<RecordingEntity>> = _searchQuery
        .flatMapLatest { q -> if (q.isBlank()) flowOf(emptyList()) else repo.searchRecordings(q) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    fun setSearchQuery(q: String) { _searchQuery.value = q }

    /**
     * Saves a recording with no marks. Kept for any call sites that don't
     * need mark support (currently none, but keeps the API flexible).
     */
    fun saveRecording(
        filePath: String, durationMs: Long, fileSizeBytes: Long,
        title: String, topicId: Long? = null
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
    fun saveRecordingWithMarks(
        filePath: String,
        durationMs: Long,
        fileSizeBytes: Long,
        title: String,
        topicId: Long? = null,
        markTimestamps: List<Long>,
        storageVolumeUuid: String = StorageVolumeHelper.UUID_PRIMARY
    ): Deferred<Long> = viewModelScope.async {
        val recordingId = repo.saveRecordingWithMarks(
            filePath          = filePath,
            durationMs        = durationMs,
            fileSizeBytes     = fileSizeBytes,
            title             = title,
            topicId           = topicId,
            markTimestamps    = markTimestamps,
            storageVolumeUuid = storageVolumeUuid
        )
        WaveformWorker.enqueue(
            context     = getApplication(),
            recordingId = recordingId,
            filePath    = filePath
        )
        recordingId
    }

    fun deleteRecording(r: RecordingEntity) = viewModelScope.launch {
        repo.deleteRecording(r)
        waveformCache.delete(r.id)
    }
    fun moveRecording(id: Long, topicId: Long?) = viewModelScope.launch {
        repo.moveRecording(id, topicId)
        // Keep nowPlaying in sync so observers (Listen tab header, mini player)
        // see the new topic immediately without waiting for a DB re-query
        if (_nowPlaying.value?.recording?.id == id) {
            _nowPlaying.value = _nowPlaying.value?.copy(
                recording = _nowPlaying.value!!.recording.copy(topicId = topicId)
            )
        }
    }
    fun setFavourite(id: Long, fav: Boolean)    = viewModelScope.launch { repo.setFavourite(id, fav) }
    fun renameRecording(id: Long, title: String) = viewModelScope.launch {
        repo.renameRecording(id, title)
        // Keep nowPlaying in sync so the Listen tab header updates immediately
        // without waiting for a DB re-query — mirrors how moveRecording() works.
        if (_nowPlaying.value?.recording?.id == id) {
            _nowPlaying.value = _nowPlaying.value?.copy(
                recording = _nowPlaying.value!!.recording.copy(title = title)
            )
        }
    }
    fun updatePlayback(id: Long, posMs: Long, listened: Boolean) =
        viewModelScope.launch { repo.updatePlayback(id, posMs, listened) }

    // ── Topics ────────────────────────────────────────────────────────
    val allTopics: StateFlow<List<TopicEntity>> = repo.getAllTopics()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun createTopic(name: String, parentId: Long?, icon: String = "🎙️", color: String = "#6C63FF") =
        viewModelScope.launch { repo.createTopic(name, parentId, icon, color) }
    fun updateTopic(t: TopicEntity) = viewModelScope.launch { repo.updateTopic(t) }
    fun deleteTopic(t: TopicEntity) = viewModelScope.launch { repo.deleteTopic(t) }
    /**
     * Returns the ID of [topicId] itself plus every descendant topic ID.
     * Used to build the exclusion set for the reparent topic picker, so the
     * topic being moved and all its children are hidden from the picker.
     */
    fun getTopicWithDescendantIds(topicId: Long): Set<Long> {
        val topics = allTopics.value
        val result = mutableSetOf<Long>()

        fun collect(id: Long) {
            result.add(id)
            topics.filter { it.parentId == id }.forEach { collect(it.id) }
        }

        collect(topicId)
        return result
    }

    /**
     * Reparents [topicId] to [newParentId] (null = make it a root topic).
     * The topic's name, icon, color and other fields are preserved.
     */
    fun reparentTopic(topicId: Long, newParentId: Long?) {
        val topic = allTopics.value.find { it.id == topicId } ?: return
        viewModelScope.launch {
            repo.updateTopic(topic.copy(parentId = newParentId))
        }
    }

    // ── Library Details navigation ────────────────────────────────────
    private val _libraryDetailsTopicId = MutableStateFlow<Long?>(null)
    val libraryDetailsTopicId: StateFlow<Long?> = _libraryDetailsTopicId

    /**
     * Called by [LibraryFragment.openTopicDetails] and [LibraryFragment.navigateToTopicDetails]
     * to set which topic the Details tab should display.
     * Passing null clears the selection (Details tab becomes greyed out again).
     */
    fun setLibraryDetailsTopic(id: Long?) {
        _libraryDetailsTopicId.value = id
    }

    // ── Lock ──────────────────────────────────────────────────────────
    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked
    fun setLocked(locked: Boolean) { _isLocked.value = locked }

    // ── Playback preferences ──────────────────────────────────────────
    private val _autoNavigateToListen =
        MutableStateFlow(prefs.getBoolean(PREF_AUTO_NAVIGATE, false))
    val autoNavigateToListen: StateFlow<Boolean> = _autoNavigateToListen
    fun setAutoNavigateToListen(enabled: Boolean) {
        _autoNavigateToListen.value = enabled
        prefs.edit().putBoolean(PREF_AUTO_NAVIGATE, enabled).apply()
    }

    private val _scrubBackSecs =
        MutableStateFlow(prefs.getInt(PREF_SCRUB_BACK_SECS, 15))
    val scrubBackSecs: StateFlow<Int> = _scrubBackSecs
    fun setScrubBackSecs(secs: Int) {
        val v = secs.coerceAtLeast(5)
        _scrubBackSecs.value = v
        prefs.edit().putInt(PREF_SCRUB_BACK_SECS, v).apply()
    }

    private val _scrubForwardSecs =
        MutableStateFlow(prefs.getInt(PREF_SCRUB_FORWARD_SECS, 15))
    val scrubForwardSecs: StateFlow<Int> = _scrubForwardSecs
    fun setScrubForwardSecs(secs: Int) {
        val v = secs.coerceAtLeast(5)
        _scrubForwardSecs.value = v
        prefs.edit().putInt(PREF_SCRUB_FORWARD_SECS, v).apply()
    }

    private val _markRewindThresholdSecs =
        MutableStateFlow(prefs.getFloat(PREF_MARK_REWIND_THRESHOLD, 1.5f))
    val markRewindThresholdSecs: StateFlow<Float> = _markRewindThresholdSecs
    fun setMarkRewindThresholdSecs(secs: Float) {
        val v = secs.coerceIn(0.5f, 5.0f)
        _markRewindThresholdSecs.value = v
        prefs.edit().putFloat(PREF_MARK_REWIND_THRESHOLD, v).apply()
    }

    // ── Jump to Library on save ───────────────────────────────────────
    private val _jumpToLibraryOnSave =
        MutableStateFlow(prefs.getBoolean(PREF_JUMP_TO_LIBRARY, true))
    val jumpToLibraryOnSave: StateFlow<Boolean> = _jumpToLibraryOnSave
    fun setJumpToLibraryOnSave(enabled: Boolean) {
        _jumpToLibraryOnSave.value = enabled
        prefs.edit().putBoolean(PREF_JUMP_TO_LIBRARY, enabled).apply()
    }

    // ── Theme mode ────────────────────────────────────────────────────
    private val _themeMode =
        MutableStateFlow(prefs.getString(PREF_THEME_MODE, "system") ?: "system")
    val themeMode: StateFlow<String> = _themeMode
    fun setThemeMode(mode: String) {
        _themeMode.value = mode
        prefs.edit().putString(PREF_THEME_MODE, mode).apply()
    }

    // ── Layout order ──────────────────────────────────────────────────
    //
    // Stores the ordered list of chrome elements (Title Bar, Content,
    // Mini Player, Nav) so MainActivity can reconstruct the view stack
    // from SharedPreferences on startup and after the user taps Apply
    // in the Settings layout widget.
    private val _layoutOrder = MutableStateFlow(
        LayoutElement.parseOrder(
            prefs.getString(PREF_LAYOUT_ORDER, null)
                ?: LayoutElement.toOrderString(LayoutElement.DEFAULT_ORDER)
        )
    )
    val layoutOrder: StateFlow<List<LayoutElement>> = _layoutOrder

    fun setLayoutOrder(order: List<LayoutElement>) {
        _layoutOrder.value = order
        prefs.edit().putString(PREF_LAYOUT_ORDER, LayoutElement.toOrderString(order)).apply()
    }

    private val _showTitleBar = MutableStateFlow(
        prefs.getBoolean(PREF_SHOW_TITLE_BAR, true)
    )
    val showTitleBar: StateFlow<Boolean> = _showTitleBar

    fun setShowTitleBar(show: Boolean) {
        _showTitleBar.value = show
        prefs.edit().putBoolean(PREF_SHOW_TITLE_BAR, show).apply()
    }

    // ── Recorder widget visibility (3-state) ─────────────────────────────────

    private val _recorderWidgetVisibility = MutableStateFlow(
        RecorderWidgetVisibility.fromString(
            prefs.getString(PREF_RECORDER_WIDGET_VISIBILITY, null)
        )
    )
    val recorderWidgetVisibility: StateFlow<RecorderWidgetVisibility> = _recorderWidgetVisibility

    fun setRecorderWidgetVisibility(mode: RecorderWidgetVisibility) {
        _recorderWidgetVisibility.value = mode
        prefs.edit().putString(PREF_RECORDER_WIDGET_VISIBILITY, mode.name).apply()
    }

    // ── Hide Recorder widget while on Record tab ──────────────────────────────

    private val _hideRecorderOnRecordTab = MutableStateFlow(
        prefs.getBoolean(PREF_HIDE_RECORDER_ON_RECORD_TAB, false)
    )
    val hideRecorderOnRecordTab: StateFlow<Boolean> = _hideRecorderOnRecordTab

    fun setHideRecorderOnRecordTab(hide: Boolean) {
        _hideRecorderOnRecordTab.value = hide
        prefs.edit().putBoolean(PREF_HIDE_RECORDER_ON_RECORD_TAB, hide).apply()
    }

    // ── Hide Listen widget while on Listen tab ────────────────────────────────

    private val _hidePlayerOnListenTab = MutableStateFlow(
        prefs.getBoolean(PREF_HIDE_PLAYER_ON_LISTEN_TAB, false)
    )
    val hidePlayerOnListenTab: StateFlow<Boolean> = _hidePlayerOnListenTab

    fun setHidePlayerOnListenTab(hide: Boolean) {
        _hidePlayerOnListenTab.value = hide
        prefs.edit().putBoolean(PREF_HIDE_PLAYER_ON_LISTEN_TAB, hide).apply()
    }

    // ── Mini widget minimized state (session-only, not persisted) ────────────
    //
    // When true the full widget is hidden and a compact pill appears in
    // the title bar overlay instead.  Intentionally NOT written to
    // SharedPreferences — restoring to full widgets on every app launch
    // is the desired default behaviour.

    private val _playerPillMinimized  = MutableStateFlow(false)
    val playerPillMinimized: StateFlow<Boolean> = _playerPillMinimized

    private val _recorderPillMinimized = MutableStateFlow(false)
    val recorderPillMinimized: StateFlow<Boolean> = _recorderPillMinimized

    fun setPlayerPillMinimized(minimized: Boolean)  { _playerPillMinimized.value  = minimized }
    fun setRecorderPillMinimized(minimized: Boolean) { _recorderPillMinimized.value = minimized }


    // ── Current page (tab index) — driven by MainActivity on every tab change ─

    private val _currentPage = MutableStateFlow(MainActivity.PAGE_RECORD)
    val currentPage: StateFlow<Int> = _currentPage

    fun setCurrentPage(page: Int) { _currentPage.value = page }

//    // ── Show last-mark timestamp in timeline ─────────────────────────────────
//
//    private val _showRecordMarkTimestamp =
//        MutableStateFlow(prefs.getBoolean(PREF_SHOW_RECORD_MARK_TIMESTAMP, false))
//    val showRecordMarkTimestamp: StateFlow<Boolean> = _showRecordMarkTimestamp
//    fun setShowRecordMarkTimestamp(show: Boolean) {
//        _showRecordMarkTimestamp.value = show
//        prefs.edit().putBoolean(PREF_SHOW_RECORD_MARK_TIMESTAMP, show).apply()
//    }

    // ── Selected recording ────────────────────────────────────────────
    private val _selectedRecordingId = MutableStateFlow(-1L)
    val selectedRecordingId: StateFlow<Long> = _selectedRecordingId
    fun selectRecording(id: Long) { _selectedRecordingId.value = id }

    // ── Marks ──────────────────────────────────────────────────────────
    private val _marks = MutableStateFlow<List<MarkEntity>>(emptyList())
    val marks: StateFlow<List<MarkEntity>> = _marks

    private val _selectedMarkId = MutableStateFlow<Long?>(null)
    val selectedMarkId: StateFlow<Long?> = _selectedMarkId

    private var marksJob: Job? = null

    private fun startObservingMarks(recordingId: Long) {
        marksJob?.cancel()
        marksJob = viewModelScope.launch {
            repo.getMarksForRecording(recordingId).collect { _marks.value = it }
        }
    }

    fun selectMark(id: Long?) { _selectedMarkId.value = id }

    fun addMark() {
        val posMs = mediaController?.currentPosition
            ?: _nowPlaying.value?.positionMs
            ?: return
        val recId = _nowPlaying.value?.recording?.id ?: return
        viewModelScope.launch {
            val newId = repo.addMark(recId, posMs)
            _selectedMarkId.value = newId
            _playbackMarkNudgeLocked.value = false
        }
    }

    fun deleteSelectedMark() {
        val id = _selectedMarkId.value ?: return
        viewModelScope.launch {
            repo.deleteMark(id)
            _selectedMarkId.value = null
        }
    }

    fun deleteSelectedRecordingMark() {
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

    private val _dropMarkEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val dropMarkEvent: SharedFlow<Unit> = _dropMarkEvent
    fun requestDropMark() { _dropMarkEvent.tryEmit(Unit) }

    private val _deleteMarkEvent = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val deleteMarkEvent: SharedFlow<Int> = _deleteMarkEvent

    // ── Mark nudge settings ───────────────────────────────────────────────────

    private val _markNudgeSecs = MutableStateFlow(prefs.getFloat(PREF_MARK_NUDGE_SECS, 5f))
    val markNudgeSecs: StateFlow<Float> = _markNudgeSecs

    fun setMarkNudgeSecs(secs: Float) {
        val v = secs.coerceIn(1f, 30f)
        _markNudgeSecs.value = v
        prefs.edit().putFloat(PREF_MARK_NUDGE_SECS, v).apply()
    }

    // ── Mark nudge lock ───────────────────────────────────────────────────────

    private val _markNudgeLocked = MutableStateFlow(false)
    val markNudgeLocked: StateFlow<Boolean> = _markNudgeLocked

    /** Commits the current mark position. Clears selection and locks nudging. */
    fun commitMarkNudge() {
        _markNudgeLocked.value = true
        _selectedRecordingMarkIndex.value = -1
    }

    fun resetMarkNudgeLock() {
        _markNudgeLocked.value = false
        _selectedRecordingMarkIndex.value = -1
    }

    /** Unlocks playback mark nudging. Called when the user selects a mark by
     *  tapping directly on the timeline dot. */
    fun unlockPlaybackMarkNudge() {
        _playbackMarkNudgeLocked.value = false
    }

    // ── Playback mark nudge ───────────────────────────────────────────────────
// Separate from the recording nudge (which operates on in-memory pending marks).
// Playback nudge writes directly to the DB; commit just clears selection.

    private val _playbackMarkNudgeLocked = MutableStateFlow(true)
    val playbackMarkNudgeLocked: StateFlow<Boolean> = _playbackMarkNudgeLocked

    /**
     * Jumps to and selects the nearest mark before the current position.
     * Falls back to the first mark if nothing precedes the playhead.
     */
    fun jumpAndSelectPrevMark() {
        val posMs = mediaController?.currentPosition
            ?: _nowPlaying.value?.positionMs ?: return
        val mark = _marks.value
            .filter { it.positionMs < posMs - 500L }
            .maxByOrNull { it.positionMs }
            ?: _marks.value.minByOrNull { it.positionMs }
        if (mark != null) {
            seekTo(mark.positionMs)
            _selectedMarkId.value = mark.id
            _playbackMarkNudgeLocked.value = false
        }
    }

    /**
     * Jumps to and selects the nearest mark after the current position.
     */
    fun jumpAndSelectNextMark() {
        val posMs = mediaController?.currentPosition
            ?: _nowPlaying.value?.positionMs ?: return
        val mark = _marks.value
            .filter { it.positionMs > posMs + 500L }
            .minByOrNull { it.positionMs }
        if (mark != null) {
            seekTo(mark.positionMs)
            _selectedMarkId.value = mark.id
            _playbackMarkNudgeLocked.value = false
        }
    }

    fun nudgePlaybackMarkBack() {
        if (_playbackMarkNudgeLocked.value) return
        val id = _selectedMarkId.value ?: return
        val deltaMs = -(_markNudgeSecs.value * 1000L).toLong()
        viewModelScope.launch { repo.nudgeMark(id, deltaMs) }
    }

    fun nudgePlaybackMarkForward() {
        if (_playbackMarkNudgeLocked.value) return
        val id = _selectedMarkId.value ?: return
        val deltaMs = (_markNudgeSecs.value * 1000L).toLong()
        viewModelScope.launch { repo.nudgeMark(id, deltaMs) }
    }

    /** Clears selection and re-locks nudging until the next jump-and-select. */
    fun commitPlaybackMarkNudge() {
        _playbackMarkNudgeLocked.value = true
        _selectedMarkId.value = null
    }

    // ── Nudge events (observed by RecordFragment to forward to the service) ───

    data class NudgeEvent(val secs: Float, val markIndex: Int)

    // extraBufferCapacity=1 so tryEmit never drops while RecordFragment is briefly
    // away (e.g. configuration change mid-recording).
    private val _nudgeBackEvent    = MutableSharedFlow<NudgeEvent>(extraBufferCapacity = 1)
    private val _nudgeForwardEvent = MutableSharedFlow<NudgeEvent>(extraBufferCapacity = 1)
    val nudgeBackEvent:    SharedFlow<NudgeEvent> = _nudgeBackEvent
    val nudgeForwardEvent: SharedFlow<NudgeEvent> = _nudgeForwardEvent

    fun requestNudgeBack() {
        if (_markNudgeLocked.value) return
        val marks = _recordingMarks.value
        if (marks.isEmpty()) return
        val idx = _selectedRecordingMarkIndex.value.takeIf { it >= 0 } ?: marks.lastIndex
        _nudgeBackEvent.tryEmit(NudgeEvent(_markNudgeSecs.value, idx))
    }

    fun requestNudgeForward() {
        if (_markNudgeLocked.value) return
        val marks = _recordingMarks.value
        if (marks.isEmpty()) return
        val idx = _selectedRecordingMarkIndex.value.takeIf { it >= 0 } ?: marks.lastIndex
        _nudgeForwardEvent.tryEmit(NudgeEvent(_markNudgeSecs.value, idx))
    }

    // ── Waveform ──────────────────────────────────────────────────────
    private val waveformCache = WaveformCache(app)
    /**
     * Emits (recordingId, amplitudes) whenever a real waveform finishes
     * loading (from cache or freshly extracted). The fragment observes this
     * and passes the array to [PlaybackWaveformView.setAmplitudes].
     *
     * Null means "no waveform loaded yet / loading in progress".
     */
    private val _waveformState = MutableStateFlow<Pair<Long, FloatArray>?>(null)
    val waveformState: StateFlow<Pair<Long, FloatArray>?> = _waveformState.asStateFlow()

    private var waveformJob: Job? = null

    /**
     * Kicks off waveform loading for [recordingId] / [filePath].
     *
     * Execution order:
     *   1. Cancel any in-flight extraction for a previous recording.
     *   2. If a cached array exists on disk, emit it immediately (< 5 ms).
     *   3. Otherwise, extract from the audio file on an IO thread
     *      (typically 300–800 ms even for a 1-hour M4A), cache the result,
     *      then emit.
     *
     * The fragment keeps displaying the seed-based fake waveform until
     * this emits, so there is no blank period during extraction.
     */
    fun loadWaveform(recordingId: Long, filePath: String) {
        waveformJob?.cancel()
        waveformJob = viewModelScope.launch(Dispatchers.IO) {
            // Fast path: already cached
            val cached = waveformCache.load(recordingId)
            if (cached != null) {
                _waveformState.value = recordingId to cached
                return@launch
            }

            // Slow path: extract from file then persist
            val amps = WaveformExtractor.extract(filePath)
            waveformCache.save(recordingId, amps)
            _waveformState.value = recordingId to amps
        }
    }

    // ── Processing status refresh tick ────────────────────────────────
    //
    // WorkManager's getWorkInfosByTagFlow can miss the terminal-state emission
    // when the last job completes (a known race in some API levels). SettingsFragment
    // bumps this tick every ~3 s while it's visible, forcing the combine chain to
    // re-evaluate and pick up any completed jobs WorkManager may not have re-emitted.
    private val _processingRefreshTick = MutableStateFlow(0L)

    fun tickProcessingRefresh() {
        _processingRefreshTick.value = System.currentTimeMillis()
    }

    // ── Processing status (for Settings tab) ──────────────────────────────────

    private val recentlyCompletedJobs = mutableListOf<ProcessingJobInfo>()
    private val startupTerminalIds = mutableSetOf<java.util.UUID>()
    private var processingStatusInitialized = false

    /**
     * Live snapshot of the waveform processing queue.
     *
     * Combines WorkManager's WorkInfo flow with allRecordings and allTopics so
     * each row can be labelled with the recording's topic emoji and title.
     * WorkInfo.inputData is not accessible to observers, so we embed the
     * recording ID as a tag ("rid:<id>") and cross-reference from there.
     *
     * A periodic refresh tick from SettingsFragment is folded into the combine
     * chain as a safety net against missed WorkManager emissions.
     */
    val processingStatus: StateFlow<ProcessingStatus> =
        WorkManager.getInstance(getApplication<Application>())
            .getWorkInfosByTagFlow(WaveformWorker.TAG)
            .combine(_processingRefreshTick) { workInfos, _ -> workInfos }
            .combine(allRecordings) { workInfos, recordings -> workInfos to recordings }
            .combine(allTopics)     { (workInfos, recordings), topics ->
                val recordingById = recordings.associateBy { it.id }
                val topicById     = topics.associateBy { it.id }

                fun infoFor(wi: WorkInfo) = ProcessingJobInfo(
                    id          = wi.id,
                    recordingId = WaveformWorker.recordingIdFromTags(wi.tags) ?: -1L,
                    state       = wi.state
                )

                if (!processingStatusInitialized) {
                    workInfos
                        .filter { it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.FAILED }
                        .forEach { startupTerminalIds.add(it.id) }
                    processingStatusInitialized = true
                } else {
                    workInfos
                        .filter { it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.FAILED }
                        .filter { it.id !in startupTerminalIds }
                        .forEach { wi ->
                            if (recentlyCompletedJobs.none { it.id == wi.id }) {
                                recentlyCompletedJobs.add(
                                    infoFor(wi).copy(completedAt = System.currentTimeMillis())
                                )
                            }
                        }
                }

                ProcessingStatus(
                    active  = workInfos.firstOrNull { it.state == WorkInfo.State.RUNNING  }?.let(::infoFor),
                    pending = workInfos.filter      { it.state == WorkInfo.State.ENQUEUED }.map(::infoFor),
                    recent  = recentlyCompletedJobs.toList().reversed()
                )
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ProcessingStatus.IDLE)


    /**
     * Returns a display label for [job] in the form "$topicIcon $title".
     * Falls back to "📥 $title" for inbox recordings and "Recording" if the
     * recording can no longer be found (e.g. deleted mid-job).
     */
    fun labelForJob(job: ProcessingJobInfo): String {
        val recording = allRecordings.value.firstOrNull { it.id == job.recordingId }
            ?: return "Recording"
        val icon = recording.topicId
            ?.let { allTopics.value.firstOrNull { t -> t.id == it }?.icon }
            ?: "📥"
        return "$icon ${recording.title}"
    }

    /**
     * Cancels all queued waveform jobs, deletes all cached .wfm files, resets
     * every recording's status to PENDING in the DB, clears the in-memory
     * completed-jobs list, then re-enqueues a fresh job for every recording.
     *
     * Safe to call from the UI thread — all heavy work runs on IO dispatcher
     * inside the viewModelScope coroutine.
     */
    fun reprocessAllWaveforms() {
        viewModelScope.launch {
            // 1. Cancel whatever WorkManager currently has queued.
            WorkManager.getInstance(getApplication<Application>())
                .cancelAllWorkByTag(WaveformWorker.TAG)

            withContext(Dispatchers.IO) {
                // 2. Delete all cached .wfm files.
                waveformCache.deleteAll()

                // 3. Reset every row in the DB to PENDING.
                repo.resetAllWaveformStatuses()
            }

            // 4. Clear the in-memory completed log.
            recentlyCompletedJobs.clear()

            // 5. Re-enqueue a fresh job for every recording.
            withContext(Dispatchers.IO) {
                repo.getAllRecordingsOnce().forEach { recording ->
                    WaveformWorker.enqueue(
                        context     = getApplication(),
                        recordingId = recording.id,
                        filePath    = recording.filePath
                    )
                }
            }
        }
    }

    // ── Storage State ─────────────────────────────────────────────────

    //  Default storage volume UUID — persisted in SharedPreferences.
    //  Null means "use whatever getVolumes() returns first" (== primary external).
    private val _defaultStorageUuid = MutableStateFlow(
        prefs.getString(PREF_DEFAULT_STORAGE_UUID, StorageVolumeHelper.UUID_PRIMARY)
            ?: StorageVolumeHelper.UUID_PRIMARY
    )
    val defaultStorageUuid: StateFlow<String> = _defaultStorageUuid

    //  Live list of available volumes, refreshed every time the UI asks.
    //  Stored as StateFlow so Settings observes it reactively.
    private val _storageVolumes = MutableStateFlow(
        StorageVolumeHelper.getVolumes(getApplication())
    )
    val storageVolumes: StateFlow<List<AppVolume>> = _storageVolumes

    //  Per-volume used bytes, derived from the DB via a Flow.
    //  Keyed by volume UUID.
    val storageUsageByVolume: StateFlow<Map<String, Long>> =
        repo.getStorageUsageByVolume()                         // Flow<List<VolumeUsage>>
            .map { list -> list.associate { it.storageVolumeUuid to it.totalBytes } }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    /**
     * UUIDs of storage volumes that have recordings in the DB but are not
     * currently mounted (e.g. an SD card that has been ejected).
     *
     * Derived reactively from [storageVolumes] and [storageUsageByVolume],
     * so it updates automatically whenever either changes — including when
     * [refreshStorageVolumes] is called from a fragment's onResume().
     *
     * Consumed by both [RecordingsAdapter] and [TopicItemAdapter] to show
     * the offline-storage warning on affected recording rows.
     */
    val orphanVolumeUuids: StateFlow<Set<String>> = combine(
        storageVolumes,
        storageUsageByVolume
    ) { volumes, usageMap ->
        val mountedUuids = volumes.map { it.uuid }.toSet()
        // A UUID is orphaned if recordings exist on it but its volume isn't mounted.
        usageMap.keys.filter { it !in mountedUuids }.toSet()
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    /**
     * Re-queries [StorageVolumeHelper] and updates [storageVolumes].
     * Call from SettingsFragment.onResume() and after the user changes
     * the default storage, so the volume list and free-space stats stay fresh.
     */
    fun refreshStorageVolumes() {
        _storageVolumes.value = StorageVolumeHelper.getVolumes(getApplication())
    }

    /**
     * Persists the user's preferred storage volume.
     * [RecordFragment] reads [defaultStorageUuid] before starting a recording
     * to resolve the output directory.
     */
    fun setDefaultStorageUuid(uuid: String) {
        _defaultStorageUuid.value = uuid
        prefs.edit().putString(PREF_DEFAULT_STORAGE_UUID, uuid).apply()
    }

    /**
     * Resolves the [AppVolume] the next recording should be written to.
     * Falls back gracefully if the preferred volume is currently unmounted.
     */
    fun resolveRecordingVolume(): AppVolume {
        val preferred = _defaultStorageUuid.value
        return StorageVolumeHelper.getVolumeByUuid(getApplication(), preferred)
            ?: StorageVolumeHelper.getDefaultVolume(getApplication())
    }
}