package com.treecast.app.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.treecast.app.TreeCastApp
import com.treecast.app.data.entities.MarkEntity
import com.treecast.app.data.entities.RecordingEntity
import com.treecast.app.data.entities.TopicEntity
import com.treecast.app.data.repository.TreeBuilder
import com.treecast.app.data.repository.TreeCastRepository
import com.treecast.app.data.repository.TreeItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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
        private const val PREF_AUTO_NAVIGATE      = "auto_navigate_to_listen"
        private const val PREF_SCRUB_BACK_SECS    = "scrub_back_secs"
        private const val PREF_SCRUB_FORWARD_SECS = "scrub_forward_secs"
        private const val PREF_JUMP_TO_LIBRARY = "jump_to_library_on_save"
    }

    // ── Session ───────────────────────────────────────────────────────
    private var currentSessionId: Long = -1L
    fun onAppOpen()  = viewModelScope.launch { currentSessionId = repo.openSession() }
    fun onAppClose() = viewModelScope.launch {
        pausePlayback()
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

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    fun play(recording: RecordingEntity) {
        stopProgressPolling()
        mediaPlayer?.release()
        mediaPlayer = null
        _nowPlaying.value = null
        try {
            val mp = MediaPlayer().apply {
                setDataSource(recording.filePath)
                prepare()
                start()
            }
            mediaPlayer = mp
            _nowPlaying.value = NowPlayingState(
                recording  = recording,
                isPlaying  = true,
                positionMs = 0L,
                durationMs = mp.duration.toLong()
            )
            startProgressPolling(recording.id)
            startObservingMarks(recording.id)
            mp.setOnCompletionListener {
                _nowPlaying.value = _nowPlaying.value?.copy(isPlaying = false)
                stopProgressPolling()
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "play() failed: ${e.message}")
        }
    }

    fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            stopProgressPolling()
            _nowPlaying.value = _nowPlaying.value?.copy(isPlaying = false)
        } else {
            mp.start()
            _nowPlaying.value = _nowPlaying.value?.copy(isPlaying = true)
            startProgressPolling(_nowPlaying.value?.recording?.id ?: return)
        }
    }

    fun seekTo(posMs: Long) {
        mediaPlayer?.seekTo(posMs.toInt())
        _nowPlaying.value = _nowPlaying.value?.copy(positionMs = posMs)
    }

    fun skipBack() {
        val pos = ((_nowPlaying.value?.positionMs ?: 0L) - scrubBackSecs.value * 1000L).coerceAtLeast(0L)
        seekTo(pos)
    }

    fun skipForward() {
        val dur = _nowPlaying.value?.durationMs ?: return
        val pos = ((_nowPlaying.value?.positionMs ?: 0L) + scrubForwardSecs.value * 1000L).coerceAtMost(dur)
        seekTo(pos)
    }

    private fun pausePlayback() {
        val state = _nowPlaying.value ?: return
        val pos   = mediaPlayer?.currentPosition?.toLong() ?: 0L
        viewModelScope.launch { repo.updatePlayback(state.recording.id, pos, false) }
        mediaPlayer?.release()
        mediaPlayer = null
        _nowPlaying.value = null
    }

    private fun startProgressPolling(recordingId: Long) {
        progressJob = viewModelScope.launch {
            while (isActive) {
                delay(500)
                val mp  = mediaPlayer ?: break
                val pos = mp.currentPosition.toLong()
                _nowPlaying.value = _nowPlaying.value?.copy(positionMs = pos)
                repo.updatePlayback(recordingId, pos, false)
            }
        }
    }

    private fun stopProgressPolling() { progressJob?.cancel(); progressJob = null }

    override fun onCleared() {
        super.onCleared()
        stopProgressPolling()
        mediaPlayer?.release()
        mediaPlayer = null
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

    fun saveRecording(
        filePath: String, durationMs: Long, fileSizeBytes: Long,
        title: String, topicId: Long? = null
    ): Deferred<Long> = viewModelScope.async {
        repo.saveRecording(filePath, durationMs, fileSizeBytes, title, topicId)
    }

    fun deleteRecording(r: RecordingEntity)        = viewModelScope.launch { repo.deleteRecording(r) }
    fun moveRecording(id: Long, topicId: Long?)    = viewModelScope.launch { repo.moveRecording(id, topicId) }
    fun setFavourite(id: Long, fav: Boolean)       = viewModelScope.launch { repo.setFavourite(id, fav) }
    fun renameRecording(id: Long, title: String)   = viewModelScope.launch { repo.renameRecording(id, title) }
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

    // ── Playback preferences ───────────────────────────────────────────
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

    private fun startObservingMarks(recordingId: Long) {
        marksJob?.cancel()
        marksJob = viewModelScope.launch {
            repo.getMarksForRecording(recordingId).collect { _marks.value = it }
        }
    }

    fun selectMark(id: Long?) { _selectedMarkId.value = id }

    fun addMark() {
        val posMs = _nowPlaying.value?.positionMs ?: return
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
}