@file:OptIn(ExperimentalCoroutinesApi::class)

package com.treecast.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
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
import com.treecast.app.service.PlaybackService
import com.treecast.app.service.RecordingService
import com.treecast.app.ui.waveform.WaveformDisplayConfig
import com.treecast.app.ui.waveform.WaveformStyle
import com.treecast.app.util.AppVolume
import com.treecast.app.util.Icons
import com.treecast.app.util.MarkJumpLogic
import com.treecast.app.util.OrphanRecording
import com.treecast.app.util.OrphanRecordingScanner
import com.treecast.app.util.StorageVolumeHelper
import com.treecast.app.util.WaveformCache
import com.treecast.app.util.WaveformExtractor
import com.treecast.app.util.bitmapToPngByteArray
import com.treecast.app.util.buildTopicArtwork
import com.treecast.app.worker.WaveformWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import kotlin.math.roundToInt

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
        private const val PREF_LAST_SESSION_OPENED_AT = "last_session_opened_at"
        private const val PREF_AUTO_NAVIGATE      = "auto_navigate_to_listen"
        private const val PREF_SCRUB_BACK_SECS    = "scrub_back_secs"
        private const val PREF_SCRUB_FORWARD_SECS = "scrub_forward_secs"
        private const val PREF_JUMP_TO_LIBRARY    = "jump_to_library_on_save"
        private const val PREF_DEFAULT_STORAGE_UUID = "default_storage_uuid"
        private const val PREF_LAYOUT_ORDER    = "layout_element_order"
        private const val PREF_SHOW_TITLE_BAR  = "show_title_bar"
        private const val PREF_RECORDER_WIDGET_VISIBILITY  = "recorder_widget_visibility"
        private const val PREF_PLAYER_WIDGET_VISIBILITY   = "player_widget_visibility"
        private const val PREF_ALWAYS_SHOW_PLAYER_PILL    = "always_show_player_pill"
        private const val PREF_ALWAYS_SHOW_RECORDER_PILL  = "always_show_recorder_pill"
        private const val PREF_HIDE_RECORDER_ON_RECORD_TAB = "hide_recorder_on_record_tab"
        private const val PREF_HIDE_PLAYER_ON_LISTEN_TAB   = "hide_player_on_listen_tab"
        private const val PREF_MARK_NUDGE_SECS            = "mark_nudge_secs"
        private const val PREF_COLLAPSED_TOPIC_IDS = "collapsed_topic_ids"
        private const val PREF_FUTURE_MODE = "future_mode"

        private const val PREF_PLAYBACK_SPEED = "playback_speed"
        const val SPEED_MIN  = 0.25f
        const val SPEED_MAX  = 4.0f
        const val SPEED_STEP = 0.05f
        const val SPEED_DEFAULT = 1.0f


        // ── Waveform style prefs ──────────────────────────────────────────────
        //
        // PREF_STYLIZED_WAVEFORMS is the legacy boolean pref (v1).  It is never
        // written again — only read once during migration in
        // readOrMigrateWaveformStyleKey().  The source of truth is now the string
        // pref PREF_WAVEFORM_STYLE.
        private const val PREF_STYLIZED_WAVEFORMS    = "stylized_waveforms"      // read-only (migration)
        private const val PREF_WAVEFORM_STYLE        = "waveform_style"          // "standard" | "sky" | "sky_lights"
        private const val PREF_INVERT_WAVEFORM_THEME = "invert_waveform_theme"

        // ── Waveform display config prefs ─────────────────────────────────────
        private const val PREF_BG_ALPHA               = "waveform_bg_alpha"
        private const val PREF_BG_EXTENDS_UNDER_RULER = "waveform_bg_extends_under_ruler"
        private const val PREF_BG_UNPLAYED_ONLY       = "waveform_bg_unplayed_only"

        // ── Waveform style key constants ──────────────────────────────────────
        // Public so SettingsFragment can reference them without string literals.
        const val STYLE_STANDARD   = "standard"
        const val STYLE_SKY        = "sky"
        const val STYLE_SKY_LIGHTS = "sky_lights"
    }

    // ── Session ───────────────────────────────────────────────────────
    fun onAppClose() = viewModelScope.launch {
        saveCurrentPosition()
        prefs.edit().putLong(PREF_LAST_SESSION_OPENED_AT, System.currentTimeMillis()).apply()
    }

    /**
     * Last session logic used to drive what the default landing page is when opening the app
     */
    fun getLastSessionOpenedAt(): Long? {
        val v = prefs.getLong(PREF_LAST_SESSION_OPENED_AT, -1L)
        return if (v == -1L) null else v
    }

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

    // Live mic amplitude (0f–1f) — pushed by RecordFragment on every sample tick.
    // Consumed by MainActivity to drive the Mini Recorder timeline's waveform
    // and shimmer. Reset to 0 when recording stops.
    private val _liveAmplitude = MutableStateFlow(0f)
    val liveAmplitude: StateFlow<Float> = _liveAmplitude.asStateFlow()
    fun setLiveAmplitude(amplitude: Float) { _liveAmplitude.value = amplitude }

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

    // ── Mark rewind threshold ─────────────────────────────────────────────────
    // Mirrors the pref key read by PlaybackService.jumpMark() so both use the
    // same configured value.
    private val markRewindThresholdMs: Long
        get() = (prefs.getFloat("mark_rewind_threshold_secs", 1.5f) * 1000f).toLong()

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
                startProgressPolling()
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
        val topicName = topic?.name ?: getApplication<Application>().getString(R.string.topic_label_unsorted)

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
        controller.setPlaybackParameters(PlaybackParameters(_playbackSpeed.value))

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

        startProgressPolling()
        startObservingMarks(recording.id)
        _selectedMarkId.value = null           // clear stale selection from previous recording
        _playbackMarkNudgeLocked.value = true  // re-lock nudge for the fresh recording

        loadWaveform(recording.id, recording.filePath)
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

    // ── Mark-jump event — signals MLWV scroll in any SNAP_* mode ─────────────

    private val _markJumpMs = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    /** Emitted by every "jump to mark" action. Collected by ListenFragment. */
    val markJumpMs: SharedFlow<Long> = _markJumpMs

    /**
     * Single entry point for all playback mark jumps.
     *
     * @param forward  true = jump to next mark; false = jump to prev (or track start).
     * @param select   true = select the landed mark and unlock nudging (mini player);
     *                 false = seek only, no selection change (Listen tab).
     */
    fun jumpMark(forward: Boolean, select: Boolean) {
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
                            _selectedMarkId.value = mark.id
                            _playbackMarkNudgeLocked.value = false
                        }
                }
            }
            is MarkJumpLogic.JumpTarget.ToTrackStart -> seekToMark(0L)
            is MarkJumpLogic.JumpTarget.NoTarget     -> { /* next-jump with no marks ahead; no-op */ }
        }
    }

    /**
     * Like [seekTo] but also fires [markJumpMs] so the Listen tab's MLWV
     * can jump-scroll to the correct bar regardless of splitter state.
     * Use this everywhere the seek target IS a mark position.
     */
    fun seekToMark(posMs: Long) {
        seekTo(posMs)
        _markJumpMs.tryEmit(posMs)
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

    private fun startProgressPolling() {
        stopProgressPolling()
        progressJob = viewModelScope.launch {
            while (isActive) {
                delay(500)
                val controller = mediaController ?: break
                val pos = controller.currentPosition
                _nowPlaying.value = _nowPlaying.value?.copy(positionMs = pos)
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

    val unsortedRecordings: StateFlow<List<RecordingEntity>> = repo.getUnsorted()
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
        storageVolumeUuid: String = StorageVolumeHelper.UUID_PRIMARY,
        createdAt: Long = System.currentTimeMillis()
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

    /**
     * Stops playback and fully clears the player state, returning the app to
     * the same state as if it had just been launched with no recording selected.
     *
     * Called by the Mini Player close (×) button.
     */
    fun stopAndClear() {
        mediaController?.stop()
        _nowPlaying.value = null
        _selectedRecordingId.value = -1L
        _playerPillMinimized.value = false   // restore pill-only users to widget view
        marksJob?.cancel()
        _marks.value = emptyList()
        _selectedMarkId.value = null
    }

    // ── Topics ────────────────────────────────────────────────────────
    val allTopics: StateFlow<List<TopicEntity>> = repo.getAllTopics()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun createTopic(name: String, parentId: Long?, icon: String = Icons.DEFAULT_TOPIC, color: String = "#6C63FF") =
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
        MutableStateFlow(prefs.getFloat(MarkJumpLogic.PREF_REWIND_THRESHOLD_SECS, MarkJumpLogic.DEFAULT_REWIND_THRESHOLD_SECS))
    val markRewindThresholdSecs: StateFlow<Float> = _markRewindThresholdSecs
    fun setMarkRewindThresholdSecs(secs: Float) {
        val v = secs.coerceIn(0.5f, 5.0f)
        _markRewindThresholdSecs.value = v
        prefs.edit().putFloat(MarkJumpLogic.PREF_REWIND_THRESHOLD_SECS, v).apply()
    }

    private val _playbackSpeed = MutableStateFlow(
        prefs.getFloat(PREF_PLAYBACK_SPEED, SPEED_DEFAULT)
    )
    val playbackSpeed: StateFlow<Float> = _playbackSpeed

    fun setPlaybackSpeed(speed: Float) {
        // Round to nearest 0.05 step to avoid floating-point drift
        // (e.g. slider producing 1.2999999 instead of 1.30).
        val rounded = (speed / SPEED_STEP).roundToInt() * SPEED_STEP
        val clamped = rounded.coerceIn(SPEED_MIN, SPEED_MAX)
        _playbackSpeed.value = clamped
        prefs.edit().putFloat(PREF_PLAYBACK_SPEED, clamped).apply()
        mediaController?.setPlaybackParameters(PlaybackParameters(clamped))
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

    // ── Waveform style ────────────────────────────────────────────────────────
    //
    // The active style is stored as a string pref ("standard" | "sky" |
    // "sky_lights") so that adding a new style never requires a pref migration.
    //
    // On first launch after the v1→v2 migration, readOrMigrateWaveformStyleKey()
    // checks the legacy boolean pref (PREF_STYLIZED_WAVEFORMS) and seeds the new
    // string pref accordingly, then never reads the boolean pref again.
    //
    // The invertTheme sub-option is a single boolean shared by all themed styles.
    // Whichever style is active, invertTheme swaps its day/night variant.
    //
    // waveformDisplayConfig carries the three draw-time settings (alpha, ruler
    // coverage, unplayed-only clip).  Changing these does NOT require a bitmap
    // rebuild — only invalidateItemDecorations() in MultiLineWaveformView.
    //
    // ── Compatibility shim ────────────────────────────────────────────────────
    // stylizedWaveforms (Boolean) is kept as a derived read-only property so
    // that SettingsFragment continues to compile until step 7 rewrites it.
    // It will be removed when the RadioGroup style picker lands.

    private fun readOrMigrateWaveformStyleKey(): String {
        val stored = prefs.getString(PREF_WAVEFORM_STYLE, null)
        if (stored != null) return stored
        // Legacy migration: if the old boolean was true the user had Sky active.
        val legacy = prefs.getBoolean(PREF_STYLIZED_WAVEFORMS, false)
        val migrated = if (legacy) STYLE_SKY else STYLE_STANDARD
        prefs.edit().putString(PREF_WAVEFORM_STYLE, migrated).apply()
        return migrated
    }

    private val _waveformStyleKey = MutableStateFlow(readOrMigrateWaveformStyleKey())

    val waveformStyleKey: StateFlow<String> = _waveformStyleKey

    fun setWaveformStyleKey(key: String) {
        _waveformStyleKey.value = key
        prefs.edit().putString(PREF_WAVEFORM_STYLE, key).apply()
    }

    private val _invertWaveformTheme = MutableStateFlow(
        prefs.getBoolean(PREF_INVERT_WAVEFORM_THEME, false)
    )
    val invertWaveformTheme: StateFlow<Boolean> = _invertWaveformTheme

    fun setInvertWaveformTheme(enabled: Boolean) {
        _invertWaveformTheme.value = enabled
        prefs.edit().putBoolean(PREF_INVERT_WAVEFORM_THEME, enabled).apply()
    }

    // ── Waveform display config ───────────────────────────────────────────────

    private val _bgAlpha = MutableStateFlow(
        prefs.getFloat(PREF_BG_ALPHA, WaveformDisplayConfig.DEFAULT_BACKGROUND_ALPHA)
    )

    fun setBgAlpha(alpha: Float) {
        _bgAlpha.value = alpha
        prefs.edit().putFloat(PREF_BG_ALPHA, alpha).apply()
    }

    private val _bgExtendsUnderRuler = MutableStateFlow(
        prefs.getBoolean(PREF_BG_EXTENDS_UNDER_RULER, WaveformDisplayConfig.DEFAULT_EXTENDS_UNDER_RULER)
    )

    fun setBgExtendsUnderRuler(extends: Boolean) {
        _bgExtendsUnderRuler.value = extends
        prefs.edit().putBoolean(PREF_BG_EXTENDS_UNDER_RULER, extends).apply()
    }

    private val _bgUnplayedOnly = MutableStateFlow(
        prefs.getBoolean(PREF_BG_UNPLAYED_ONLY, WaveformDisplayConfig.DEFAULT_UNPLAYED_ONLY)
    )

    fun setBgUnplayedOnly(unplayedOnly: Boolean) {
        _bgUnplayedOnly.value = unplayedOnly
        prefs.edit().putBoolean(PREF_BG_UNPLAYED_ONLY, unplayedOnly).apply()
    }

    /**
     * Draw-time display parameters for the background decoration.
     * Collect this in [ListenFragment] alongside [waveformStyle] and push it to
     * [MultiLineWaveformView].  Changes here do not require a bitmap rebuild.
     */
    val waveformDisplayConfig: StateFlow<WaveformDisplayConfig> = combine(
        _bgAlpha, _bgExtendsUnderRuler, _bgUnplayedOnly
    ) { alpha, ruler, unplayed ->
        WaveformDisplayConfig(
            backgroundAlpha    = alpha,
            extendsUnderRuler  = ruler,
            unplayedOnly       = unplayed,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WaveformDisplayConfig())

    // ── Derived WaveformStyle ─────────────────────────────────────────────────

    /**
     * Current waveform style.  Collect this in [ListenFragment] and push it to
     * [MultiLineWaveformView.waveformStyle].  Changes here trigger a full
     * background bitmap rebuild in the view.
     */
    val waveformStyle: StateFlow<WaveformStyle> = combine(
        _waveformStyleKey, _invertWaveformTheme
    ) { key, invert ->
        when (key) {
            STYLE_SKY        -> WaveformStyle.Sky(invertTheme = invert)
            STYLE_SKY_LIGHTS -> WaveformStyle.SkyLights(invertTheme = invert)
            else             -> WaveformStyle.Standard
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WaveformStyle.Standard)

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

    // ── Player widget visibility (3-state) ───────────────────────────────────

    private val _playerWidgetVisibility = MutableStateFlow(
        PlayerWidgetVisibility.fromString(
            prefs.getString(PREF_PLAYER_WIDGET_VISIBILITY, null)
        )
    )
    val playerWidgetVisibility: StateFlow<PlayerWidgetVisibility> = _playerWidgetVisibility

    fun setPlayerWidgetVisibility(mode: PlayerWidgetVisibility) {
        _playerWidgetVisibility.value = mode
        prefs.edit().putString(PREF_PLAYER_WIDGET_VISIBILITY, mode.name).apply()
    }

    // ── Always show player pill / recorder pill ───────────────────────────────
    //
    // When true the pill in the title bar is unconditionally visible regardless
    // of widget visibility mode or minimized state.  Persisted so the user's
    // preference survives app restarts.

    private val _alwaysShowPlayerPill = MutableStateFlow(
        prefs.getBoolean(PREF_ALWAYS_SHOW_PLAYER_PILL, false)
    )
    val alwaysShowPlayerPill: StateFlow<Boolean> = _alwaysShowPlayerPill

    fun setAlwaysShowPlayerPill(show: Boolean) {
        _alwaysShowPlayerPill.value = show
        prefs.edit().putBoolean(PREF_ALWAYS_SHOW_PLAYER_PILL, show).apply()
    }

    private val _alwaysShowRecorderPill = MutableStateFlow(
        prefs.getBoolean(PREF_ALWAYS_SHOW_RECORDER_PILL, false)
    )
    val alwaysShowRecorderPill: StateFlow<Boolean> = _alwaysShowRecorderPill

    fun setAlwaysShowRecorderPill(show: Boolean) {
        _alwaysShowRecorderPill.value = show
        prefs.edit().putBoolean(PREF_ALWAYS_SHOW_RECORDER_PILL, show).apply()
    }

    // ── Tab-suppress override (session-only, not persisted) ──────────────────
    //
    // Set to true when the user manually expands a widget while on the tab
    // that would normally suppress it (e.g. tapping the player pill on the
    // Listen tab when hidePlayerOnListenTab is true).
    // Reset to false when the user navigates away from that tab.

    private val _playerHideOverriddenThisVisit = MutableStateFlow(false)
    val playerHideOverriddenThisVisit: StateFlow<Boolean> = _playerHideOverriddenThisVisit
    fun setPlayerHideOverriddenThisVisit(v: Boolean) { _playerHideOverriddenThisVisit.value = v }

    private val _recorderHideOverriddenThisVisit = MutableStateFlow(false)
    val recorderHideOverriddenThisVisit: StateFlow<Boolean> = _recorderHideOverriddenThisVisit
    fun setRecorderHideOverriddenThisVisit(v: Boolean) { _recorderHideOverriddenThisVisit.value = v }

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

    // ── Future Mode (Dev Options) ─────────────────────────────────────
    private val _futureMode = MutableStateFlow(
        prefs.getBoolean(PREF_FUTURE_MODE, false)
    )
    val futureMode: StateFlow<Boolean> = _futureMode

    fun setFutureMode(enabled: Boolean) {
        _futureMode.value = enabled
        prefs.edit().putBoolean(PREF_FUTURE_MODE, enabled).apply()
    }

    // ── Current page (tab index) — driven by MainActivity on every tab change ─

    private val _currentPage = MutableStateFlow(MainActivity.PAGE_RECORD)
    val currentPage: StateFlow<Int> = _currentPage

    fun setCurrentPage(page: Int) { _currentPage.value = page }

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

    /**
     * True when a real mark exists behind the playhead (prev jump will land on a mark).
     * False means prev jump falls back to track start — button stays enabled but turns white.
     */
    val hasMarkBehind: StateFlow<Boolean> = combine(
        _marks, _nowPlaying, markRewindThresholdSecs
    ) { marks, state, threshSecs ->
        val posMs  = state?.positionMs ?: 0L
        val thresh = (threshSecs * 1000f).toLong()
        val target = MarkJumpLogic.findTarget(
            marks             = marks.map { it.positionMs },
            currentPositionMs = posMs,
            forward           = false,
            rewindThresholdMs = thresh
        )
        target is MarkJumpLogic.JumpTarget.ToMark
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * True when at least one mark exists ahead of the playhead.
     * False means next-jump does nothing — button is disabled and dimmed.
     */
    val hasMarkAhead: StateFlow<Boolean> = combine(
        _marks, _nowPlaying
    ) { marks, state ->
        val posMs  = state?.positionMs ?: 0L
        val target = MarkJumpLogic.findTarget(
            marks             = marks.map { it.positionMs },
            currentPositionMs = posMs,
            forward           = true,
            rewindThresholdMs = 0L
        )
        target is MarkJumpLogic.JumpTarget.ToMark
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

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

    // ── Orphan recordings ─────────────────────────────────────────────────────
    //
    // Populated by MainActivity on startup from the SplashActivity intent extras,
    // so the Settings card always shows accurate counts even when the recovery
    // dialog is not shown (zero orphans).
    //
    // Re-populated by rescanOrphans() which OrphanRecoveryDialogFragment calls
    // on dismiss, so any recoveries or deletions the user just made are reflected
    // immediately in the Settings card.

    private val _orphanRecordings = MutableStateFlow<List<OrphanRecording>>(emptyList())
    val orphanRecordings: StateFlow<List<OrphanRecording>> = _orphanRecordings.asStateFlow()

    /**
     * Called by [com.treecast.app.ui.MainActivity] immediately after reading
     * the orphan intent extras at startup.  Populates [orphanRecordings] from
     * the already-computed list so no extra I/O is needed here.
     *
     * Always called — even when [orphans] is empty — so the Settings card
     * updates to "None" rather than remaining on the initial "—" placeholder.
     */
    fun setOrphanResults(orphans: List<OrphanRecording>) {
        _orphanRecordings.value = orphans
    }

    /**
     * Re-scans all recording directories and refreshes [orphanRecordings].
     *
     * Called by [com.treecast.app.ui.recovery.OrphanRecoveryDialogFragment]
     * on dismiss so that counts and sizes in the Settings card reflect any
     * recoveries or deletions the user just made.
     *
     * The scan runs on [kotlinx.coroutines.Dispatchers.IO] inside
     * [OrphanRecordingScanner]; this function is safe to call from any thread.
     */
    fun rescanOrphans() {
        viewModelScope.launch {
            val knownPaths = repo.getKnownFilePaths()
            _orphanRecordings.value = OrphanRecordingScanner.scan(getApplication(), knownPaths)
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
     * Falls back to "📥 $title" for unsorted recordings and "Recording" if the
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