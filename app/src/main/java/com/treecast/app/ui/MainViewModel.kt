package com.treecast.app.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.treecast.app.TreeCastApp
import com.treecast.app.data.entities.CategoryEntity
import com.treecast.app.data.entities.MarkEntity
import com.treecast.app.data.entities.RecordingEntity
import com.treecast.app.data.repository.TreeCastRepository
import com.treecast.app.data.repository.TreeBuilder
import com.treecast.app.data.repository.TreeItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// ── Playback state model ───────────────────────────────────────────────────

data class NowPlayingState(
    val recording: RecordingEntity,
    val isPlaying: Boolean       = false,
    val positionMs: Long         = 0L,
    val durationMs: Long         = 0L
)

// ── ViewModel ──────────────────────────────────────────────────────────────

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: TreeCastRepository = (app as TreeCastApp).repository

    // ── Persistent settings ───────────────────────────────────────────
    private val prefs: SharedPreferences =
        app.getSharedPreferences("treecast_settings", Context.MODE_PRIVATE)

    // ── Session ───────────────────────────────────────────────────────
    private var currentSessionId: Long = -1L

    fun onAppOpen() = viewModelScope.launch { currentSessionId = repo.openSession() }
    fun onAppClose() = viewModelScope.launch {
        pausePlayback()
        if (currentSessionId != -1L) repo.closeSession(currentSessionId)
    }
    suspend fun getLastSession() = repo.getLastSession()

    // ── Top title ─────────────────────────────────────────────────────
    private val _topTitle = MutableStateFlow("Record")
    val topTitle: StateFlow<String> = _topTitle
    fun setTopTitle(title: String) { _topTitle.value = title }

    // ── Now Playing / Mini Player ─────────────────────────────────────
    private val _nowPlaying = MutableStateFlow<NowPlayingState?>(null)
    val nowPlaying: StateFlow<NowPlayingState?> = _nowPlaying

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    /**
     * Load and begin playing a recording.
     * Safe to call when another recording is already playing — stops it first.
     */
    fun play(recording: RecordingEntity) {
        stopProgressPolling()
        mediaPlayer?.release()
        mediaPlayer = null
        // Clear state first so collectors always see a fresh emission,
        // even when replaying the same recording that is already in nowPlaying.
        _nowPlaying.value = null

        val resumePos = recording.playbackPositionMs

        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(recording.filePath)
                prepare()
                if (resumePos > 0) seekTo(resumePos.toInt())
                start()
                setOnCompletionListener {
                    _nowPlaying.value = _nowPlaying.value?.copy(
                        isPlaying  = false,
                        positionMs = 0L
                    )
                    viewModelScope.launch {
                        repo.updatePlayback(recording.id, 0L, true)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "MediaPlayer error: ${e.message}")
                release()
                return@apply
            }

            startObservingMarks(recording.id)
            _nowPlaying.value = NowPlayingState(
                recording  = recording,
                isPlaying  = true,
                positionMs = resumePos,
                durationMs = duration.toLong()
            )
            startProgressPolling(recording.id)
        }
    }

    fun togglePlayPause() {
        val mp    = mediaPlayer ?: return
        val state = _nowPlaying.value ?: return
        if (mp.isPlaying) {
            mp.pause()
            stopProgressPolling()
            _nowPlaying.value = state.copy(isPlaying = false, positionMs = mp.currentPosition.toLong())
        } else {
            mp.start()
            _nowPlaying.value = state.copy(isPlaying = true)
            startProgressPolling(state.recording.id)
        }
    }

    fun seekTo(ms: Long) {
        mediaPlayer?.seekTo(ms.toInt())
        _nowPlaying.value = _nowPlaying.value?.copy(positionMs = ms)
    }


    fun skipBack() {
        val mp = mediaPlayer ?: return
        seekTo((mp.currentPosition - _scrubBackSecs.value * 1_000L).coerceAtLeast(0L))
    }

    fun skipForward() {
        val mp = mediaPlayer ?: return
        seekTo((mp.currentPosition + _scrubForwardSecs.value * 1_000L).coerceAtMost(mp.duration.toLong()))
    }

    fun pausePlayback() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            stopProgressPolling()
            _nowPlaying.value = _nowPlaying.value?.copy(isPlaying = false)
        }
    }

    fun stopPlayback() {
        stopProgressPolling()
        _nowPlaying.value?.let { state ->
            val pos = mediaPlayer?.currentPosition?.toLong() ?: 0L
            viewModelScope.launch { repo.updatePlayback(state.recording.id, pos, false) }
        }
        mediaPlayer?.release()
        mediaPlayer = null
        _nowPlaying.value = null
    }

    private fun startProgressPolling(recordingId: Long) {
        progressJob = viewModelScope.launch {
            while (isActive) {
                delay(500)
                val mp = mediaPlayer ?: break
                val pos = mp.currentPosition.toLong()
                _nowPlaying.value = _nowPlaying.value?.copy(positionMs = pos)
                repo.updatePlayback(recordingId, pos, false)
            }
        }
    }

    private fun stopProgressPolling() {
        progressJob?.cancel()
        progressJob = null
    }

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

    fun toggleCollapse(categoryId: Long, currentlyCollapsed: Boolean) {
        _collapsedIds.value = if (currentlyCollapsed)
            _collapsedIds.value - categoryId
        else
            _collapsedIds.value + categoryId
        viewModelScope.launch { repo.toggleCollapse(categoryId, !currentlyCollapsed) }
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

    fun saveRecording(filePath: String, durationMs: Long, fileSizeBytes: Long, title: String, categoryId: Long? = null) =
        viewModelScope.launch { repo.saveRecording(filePath, durationMs, fileSizeBytes, title, categoryId) }

    fun deleteRecording(r: RecordingEntity) = viewModelScope.launch { repo.deleteRecording(r) }
    fun moveRecording(id: Long, catId: Long?) = viewModelScope.launch { repo.moveRecording(id, catId) }
    fun setFavourite(id: Long, fav: Boolean) = viewModelScope.launch { repo.setFavourite(id, fav) }
    fun renameRecording(id: Long, title: String) = viewModelScope.launch { repo.renameRecording(id, title) }
    fun updatePlayback(id: Long, posMs: Long, listened: Boolean) =
        viewModelScope.launch { repo.updatePlayback(id, posMs, listened) }

    // ── Categories ────────────────────────────────────────────────────
    val allCategories: StateFlow<List<CategoryEntity>> = repo.getAllCategories()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun createCategory(name: String, parentId: Long?, icon: String = "🎙️", color: String = "#6C63FF") =
        viewModelScope.launch { repo.createCategory(name, parentId, icon, color) }

    fun renameCategory(id: Long, name: String) = viewModelScope.launch {
        val cat = repo.getCategoryById(id) ?: return@launch
        repo.updateCategory(cat.copy(name = name, updatedAt = System.currentTimeMillis()))
    }
    fun updateCategoryIcon(id: Long, icon: String) = viewModelScope.launch {
        val cat = repo.getCategoryById(id) ?: return@launch
        repo.updateCategory(cat.copy(icon = icon, updatedAt = System.currentTimeMillis()))
    }

    fun deleteCategory(cat: CategoryEntity) = viewModelScope.launch {
        repo.deleteCategory(cat)
    }


    // ── Lock ──────────────────────────────────────────────────────────
    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked
    fun setLocked(locked: Boolean) { _isLocked.value = locked }

    // ── Playback preferences ───────────────────────────────────────────

    // ── Playback preferences ───────────────────────────────────────────
    //
    // Each preference is backed by SharedPreferences so values survive
    // process death. The initial value is read from prefs on first load;
    // every setter writes back immediately via apply() (async, non-blocking).

    /** When true, starting playback automatically switches to the Listen tab. */
    private val _autoNavigateToListen =
        MutableStateFlow(prefs.getBoolean(PREF_AUTO_NAVIGATE, false))
    val autoNavigateToListen: StateFlow<Boolean> = _autoNavigateToListen
    fun setAutoNavigateToListen(enabled: Boolean) {
        _autoNavigateToListen.value = enabled
        prefs.edit().putBoolean(PREF_AUTO_NAVIGATE, enabled).apply()
    }

    /** Seconds to seek backwards when the skip-back button is tapped (default 15). */
    private val _scrubBackSecs =
        MutableStateFlow(prefs.getInt(PREF_SCRUB_BACK_SECS, 15))
    val scrubBackSecs: StateFlow<Int> = _scrubBackSecs
    fun setScrubBackSecs(secs: Int) {
        val v = secs.coerceAtLeast(5)
        _scrubBackSecs.value = v
        prefs.edit().putInt(PREF_SCRUB_BACK_SECS, v).apply()
    }

    /** Seconds to seek forward when the skip-forward button is tapped (default 15). */
    private val _scrubForwardSecs =
        MutableStateFlow(prefs.getInt(PREF_SCRUB_FORWARD_SECS, 15))
    val scrubForwardSecs: StateFlow<Int> = _scrubForwardSecs
    fun setScrubForwardSecs(secs: Int) {
        val v = secs.coerceAtLeast(5)
        _scrubForwardSecs.value = v
        prefs.edit().putInt(PREF_SCRUB_FORWARD_SECS, v).apply()
    }

    // ── Marks ──────────────────────────────────────────────────────────
    /** Marks for the currently loaded recording, live from DB. */
    private val _marks = MutableStateFlow<List<MarkEntity>>(emptyList())
    val marks: StateFlow<List<MarkEntity>> = _marks

    /** The mark the user has tapped in the marks list, or null. */
    private val _selectedMarkId = MutableStateFlow<Long?>(null)
    val selectedMarkId: StateFlow<Long?> = _selectedMarkId

    private var marksJob: kotlinx.coroutines.Job? = null

    /** Call whenever the playing recording changes to reload marks. */
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

    // ── Prefs keys ────────────────────────────────────────────────────
    companion object {
        private const val PREF_AUTO_NAVIGATE    = "auto_navigate_to_listen"
        private const val PREF_SCRUB_BACK_SECS  = "scrub_back_secs"
        private const val PREF_SCRUB_FORWARD_SECS = "scrub_forward_secs"
    }

}
