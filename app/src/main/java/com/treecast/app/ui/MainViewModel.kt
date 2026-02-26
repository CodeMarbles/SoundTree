package com.treecast.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.treecast.app.TreeCastApp
import com.treecast.app.data.entities.MarkEntity
import com.treecast.app.data.entities.RecordingEntity
import com.treecast.app.data.entities.TopicEntity
import com.treecast.app.data.repository.TreeBuilder
import com.treecast.app.data.repository.TreeCastRepository
import com.treecast.app.data.repository.TreeItem
import com.treecast.app.service.PlaybackService
import com.treecast.app.util.WaveformCache
import com.treecast.app.util.WaveformExtractor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

data class NowPlayingState(
    val recording:  RecordingEntity,
    val isPlaying:  Boolean = false,
    val positionMs: Long    = 0L,
    val durationMs: Long    = 0L
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: TreeCastRepository = (app as TreeCastApp).repository

    private val prefs: SharedPreferences =
        app.getSharedPreferences("treecast_settings", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "MainViewModel"
        private const val PREF_AUTO_NAVIGATE      = "auto_navigate_to_listen"
        private const val PREF_SCRUB_BACK_SECS    = "scrub_back_secs"
        private const val PREF_SCRUB_FORWARD_SECS = "scrub_forward_secs"
        private const val PREF_JUMP_TO_LIBRARY    = "jump_to_library_on_save"
    }

    // ── Session ───────────────────────────────────────────────────────
    private var currentSessionId: Long = -1L
    fun onAppOpen()  = viewModelScope.launch { currentSessionId = repo.openSession() }

    fun onAppClose() = viewModelScope.launch {
        saveCurrentPosition()
        if (currentSessionId != -1L) repo.closeSession(currentSessionId)
    }

    suspend fun getLastSession() = repo.getLastSession()

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
        controller.setMediaItem(MediaItem.fromUri(uri))
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
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
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
    private val _collapsedIds = MutableStateFlow<Set<Long>>(emptySet())

    val treeItems: StateFlow<List<TreeItem>> = repo.getTreeFlow()
        .combine(_collapsedIds) { roots, collapsed -> TreeBuilder.flatten(roots, collapsed) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun toggleCollapse(topicId: Long, currentlyCollapsed: Boolean) {
        _collapsedIds.value = if (currentlyCollapsed)
            _collapsedIds.value - topicId else _collapsedIds.value + topicId
        viewModelScope.launch { repo.toggleCollapse(topicId, !currentlyCollapsed) }
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
        filePath: String, durationMs: Long, fileSizeBytes: Long,
        title: String, topicId: Long? = null,
        markTimestamps: List<Long>
    ): Deferred<Long> = viewModelScope.async {
        repo.saveRecordingWithMarks(filePath, durationMs, fileSizeBytes, title, topicId, markTimestamps)
    }

    fun deleteRecording(r: RecordingEntity) = viewModelScope.launch {
        repo.deleteRecording(r)
        waveformCache.delete(r.id)
    }
    fun moveRecording(id: Long, topicId: Long?) = viewModelScope.launch { repo.moveRecording(id, topicId) }
    fun setFavourite(id: Long, fav: Boolean)    = viewModelScope.launch { repo.setFavourite(id, fav) }
    fun renameRecording(id: Long, title: String)= viewModelScope.launch { repo.renameRecording(id, title) }
    fun updatePlayback(id: Long, posMs: Long, listened: Boolean) =
        viewModelScope.launch { repo.updatePlayback(id, posMs, listened) }

    // ── Topics ────────────────────────────────────────────────────────
    val allTopics: StateFlow<List<TopicEntity>> = repo.getAllTopics()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun createTopic(name: String, parentId: Long?, icon: String = "🎙️", color: String = "#6C63FF") =
        viewModelScope.launch { repo.createTopic(name, parentId, icon, color) }
    fun updateTopic(t: TopicEntity) = viewModelScope.launch { repo.updateTopic(t) }
    fun deleteTopic(t: TopicEntity) = viewModelScope.launch { repo.deleteTopic(t) }

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

    // ── Jump to Library on save ───────────────────────────────────────
    private val _jumpToLibraryOnSave =
        MutableStateFlow(prefs.getBoolean(PREF_JUMP_TO_LIBRARY, true))
    val jumpToLibraryOnSave: StateFlow<Boolean> = _jumpToLibraryOnSave
    fun setJumpToLibraryOnSave(enabled: Boolean) {
        _jumpToLibraryOnSave.value = enabled
        prefs.edit().putBoolean(PREF_JUMP_TO_LIBRARY, enabled).apply()
    }

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
        viewModelScope.launch { repo.addMark(recId, posMs) }
    }

    fun deleteSelectedMark() {
        val id = _selectedMarkId.value ?: return
        viewModelScope.launch {
            repo.deleteMark(id)
            _selectedMarkId.value = null
        }
    }

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
}