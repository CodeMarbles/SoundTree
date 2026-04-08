@file:OptIn(ExperimentalCoroutinesApi::class)

package app.treecast.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.treecast.data.entities.BackupLogEntity
import app.treecast.data.entities.BackupTargetEntity
import app.treecast.data.entities.MarkEntity
import app.treecast.data.entities.RecordingEntity
import app.treecast.data.entities.TopicEntity
import app.treecast.data.repository.TreeBuilder
import app.treecast.data.repository.TreeCastRepository
import app.treecast.data.repository.TreeItem
import app.treecast.service.PlaybackService
import app.treecast.service.RecordingService
import app.treecast.storage.AppVolume
import app.treecast.storage.StorageVolumeHelper
import app.treecast.ui.MainViewModel.MigrationState.Done
import app.treecast.ui.MainViewModel.MigrationState.Idle
import app.treecast.ui.MainViewModel.MigrationState.Running
import app.treecast.ui.waveform.WaveformDisplayConfig
import app.treecast.ui.waveform.WaveformStyle
import app.treecast.util.MarkJumpLogic
import app.treecast.util.OrphanRecording
import app.treecast.util.OrphanRecordingScanner
import app.treecast.util.PlaybackPositionHelper
import app.treecast.util.WaveformCache
import app.treecast.worker.WaveformWorker
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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

// ─────────────────────────────────────────────────────────────────────────────
// Backup progress UI models
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Snapshot of a single currently-running backup job, including the live DB
 * row (updated by BackupWorker.updateStats) and the most recent INFO event
 * message for the optional status line.
 */
data class ActiveBackupInfo(
    val log: BackupLogEntity,
    /**
     * Most recent INFO event message, or null when verbose logging is off
     * or no INFO events have been flushed yet. UI falls back to "Copying files…".
     */
    val latestEventMessage: String?,
)

/**
 * State snapshot shared by the in-settings progress card and the title-bar
 * strip. Both surfaces observe [MainViewModel.backupUiState]; neither
 * independently subscribes to WorkManager or the DB.
 */
data class BackupUiState(
    /** All currently-running backup jobs, ordered newest-started first. */
    val activeJobs: List<ActiveBackupInfo>,
    /**
     * Jobs that completed since this process started, newest first.
     * Memory-only — cleared on process restart. Used by the title-bar strip
     * to show outcome summaries.
     */
    val recentlyCompletedJobs: List<BackupLogEntity>,
) {
    val isAnyRunning: Boolean       get() = activeJobs.isNotEmpty()
    val primaryActive: ActiveBackupInfo? get() = activeJobs.firstOrNull()
    val extraActiveCount: Int       get() = maxOf(0, activeJobs.size - 1)

    companion object {
        val IDLE = BackupUiState(emptyList(), emptyList())
    }
}

/** Three-state model driving the title-bar status strip. */
sealed class BackupStripState {
    /** Strip is hidden. */
    object Hidden : BackupStripState()

    /**
     * One or more jobs are running.
     * [primary] is the job shown in the strip; [extraCount] drives the "+N more" badge.
     */
    data class Running(
        val primary: ActiveBackupInfo,
        val extraCount: Int,
    ) : BackupStripState()

    /**
     * All jobs have finished; the strip is showing the outcome of [log].
     * Persists until the user explicitly dismisses via the X button.
     */
    data class Completed(
        val log: BackupLogEntity,
    ) : BackupStripState()
}

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

/**
 * UI model combining a [BackupTargetEntity] with the live [AppVolume] for
 * that volume (null when the drive is not currently connected).
 *
 * Emitted by [MainViewModel.backupTargetUiStates] so the Storage tab never
 * needs to cross-reference the two collections itself.
 */
data class BackupTargetUiState(
    val entity: BackupTargetEntity,
    /** Null when the backup volume is not currently mounted. */
    val volume: AppVolume?,
) {
    val isMounted: Boolean get() = volume?.isMounted == true
    val displayLabel: String get() = volume?.label ?: entity.volumeLabel ?: entity.volumeUuid
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    internal val repo: TreeCastRepository = (app as app.treecast.TreeCastApp).repository

    internal val prefs: SharedPreferences =
        app.getSharedPreferences("treecast_settings", Context.MODE_PRIVATE)

    companion object {
        internal const val TAG = "MainViewModel"
        internal const val PREF_THEME_MODE = "theme_mode"
        internal const val PREF_LAST_SESSION_OPENED_AT = "last_session_opened_at"
        internal const val PREF_AUTO_NAVIGATE      = "auto_navigate_to_listen"
        internal const val PREF_SCRUB_BACK_SECS    = "scrub_back_secs"
        internal const val PREF_SCRUB_FORWARD_SECS = "scrub_forward_secs"
        internal const val PREF_JUMP_TO_LIBRARY    = "jump_to_library_on_save"
        internal const val PREF_DEFAULT_STORAGE_UUID = "default_storage_uuid"
        internal const val PREF_LAYOUT_ORDER    = "layout_element_order"
        internal const val PREF_SHOW_TITLE_BAR  = "show_title_bar"
        internal const val PREF_RECORDER_WIDGET_VISIBILITY  = "recorder_widget_visibility"
        internal const val PREF_PLAYER_WIDGET_VISIBILITY   = "player_widget_visibility"
        internal const val PREF_ALWAYS_SHOW_PLAYER_PILL    = "always_show_player_pill"
        internal const val PREF_ALWAYS_SHOW_RECORDER_PILL  = "always_show_recorder_pill"
        internal const val PREF_HIDE_RECORDER_ON_RECORD_TAB = "hide_recorder_on_record_tab"
        internal const val PREF_HIDE_PLAYER_ON_LISTEN_TAB   = "hide_player_on_listen_tab"
        internal const val PREF_MARK_NUDGE_SECS            = "mark_nudge_secs"
        internal const val PREF_COLLAPSED_TOPIC_IDS = "collapsed_topic_ids"
        internal const val PREF_FUTURE_MODE = "future_mode"
        internal const val PREF_PLAYHEAD_VIS_ENABLED = "playhead_vis_enabled"
        internal const val PREF_PLAYHEAD_VIS_INTENSITY = "playhead_vis_intensity"

        internal const val PREF_PLAYBACK_SPEED = "playback_speed"
        const val SPEED_MIN  = 0.25f
        const val SPEED_MAX  = 4.0f
        const val SPEED_STEP = 0.05f
        const val SPEED_DEFAULT = 1.0f


        // ── Waveform style prefs ──────────────────────────────────────────────
        internal const val PREF_WAVEFORM_STYLE        = "waveform_style"          // "standard" | "sky" | "sky_lights"
        internal const val PREF_INVERT_WAVEFORM_THEME = "invert_waveform_theme"

        // ── Waveform display config prefs ─────────────────────────────────────
        internal const val PREF_BG_ALPHA               = "waveform_bg_alpha"
        internal const val PREF_BG_EXTENDS_UNDER_RULER = "waveform_bg_extends_under_ruler"
        internal const val PREF_BG_UNPLAYED_ONLY       = "waveform_bg_unplayed_only"

        // ── Waveform style key constants ──────────────────────────────────────
        // Public so SettingsFragment can reference them without string literals.
        const val STYLE_STANDARD   = "standard"
        const val STYLE_SKY        = "sky"
        const val STYLE_SKY_LIGHTS = "sky_lights"
    }

    // ── Top title ─────────────────────────────────────────────────────
    internal val _topTitle = MutableStateFlow("Record")
    val topTitle: StateFlow<String> = _topTitle

    // ── Now Playing ───────────────────────────────────────────────────
    internal val _nowPlaying = MutableStateFlow<NowPlayingState?>(null)
    val nowPlaying: StateFlow<NowPlayingState?> = _nowPlaying

    // ── Media3 MediaController ────────────────────────────────────────
    private val controllerFuture: ListenableFuture<MediaController>
    internal var mediaController: MediaController? = null
    internal var progressJob: Job? = null

    // ── Recording State ───────────────────────────────────────────────────────

    // Current state of RecordingService — pushed by RecordFragment.
    internal val _recordingState = MutableStateFlow(RecordingService.State.IDLE)
    val recordingState: StateFlow<RecordingService.State> = _recordingState

    // Add alongside the other recording state fields (_recordingState, etc.):

    internal val _recordingTopicId = MutableStateFlow<Long?>(null)
    val recordingTopicId: StateFlow<Long?> = _recordingTopicId

    // Current elapsed recording time in ms — pushed by RecordFragment.
    internal val _recordingElapsedMs = MutableStateFlow(0L)
    val recordingElapsedMs: StateFlow<Long> = _recordingElapsedMs

    // Live mic amplitude (0f–1f) — pushed by RecordFragment on every sample tick.
    // Consumed by MainActivity to drive the Mini Recorder timeline's waveform
    // and shimmer. Reset to 0 when recording stops.
    internal val _liveAmplitude = MutableStateFlow(0f)
    val liveAmplitude: StateFlow<Float> = _liveAmplitude.asStateFlow()

    // Current pending mark timestamps — pushed by RecordFragment.
    internal val _recordingMarks = MutableStateFlow<List<Long>>(emptyList())
    val recordingMarks: StateFlow<List<Long>> = _recordingMarks

    /** Index into recordingMarks of the mark currently targeted by nudge controls.
     *  -1 means no explicit selection (UI falls back to displaying last mark as teal
     *  but nudge buttons remain in their locked/unlocked state independently). */
    internal val _selectedRecordingMarkIndex = MutableStateFlow(-1)
    val selectedRecordingMarkIndex: StateFlow<Int> = _selectedRecordingMarkIndex

    // ── Mark rewind threshold ─────────────────────────────────────────────────
    // Mirrors the pref key read by PlaybackService.jumpMark() so both use the
    // same configured value.
    internal val markRewindThresholdMs: Long
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
                    mediaController?.playbackState != Player.STATE_BUFFERING &&
                    mediaController?.playbackState != Player.STATE_IDLE)  {
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
                // Guard against stale STATE_ENDED callbacks from a previous recording.
                // After play() is called for a new recording, Media3's masking
                // immediately advances controller.playbackState to STATE_BUFFERING (or
                // beyond). A STATE_ENDED callback still in flight for the old recording
                // therefore arrives when the controller no longer reports STATE_ENDED,
                // and we should not let it corrupt the new recording's play state.
                if (mediaController?.playbackState != Player.STATE_ENDED) return

                val recId = _nowPlaying.value?.recording?.id ?: return
                viewModelScope.launch { repo.updatePlayback(recId, 0L, true) }
                _nowPlaying.value = _nowPlaying.value?.copy(isPlaying = false, positionMs = 0L)
                stopProgressPolling()
            }
        }
    }

    // ── Playback commands ─────────────────────────────────────────────

    internal val _toggleRecordingPauseEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val toggleRecordingPauseEvent: SharedFlow<Unit> = _toggleRecordingPauseEvent

    // ── Mark-jump event — signals MLWV scroll in any SNAP_* mode ─────────────

    internal val _markJumpMs = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    /** Emitted by every "jump to mark" action. Collected by ListenFragment. */
    val markJumpMs: SharedFlow<Long> = _markJumpMs

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
    internal val _collapsedIds = MutableStateFlow<Set<Long>>(
        prefs.getString(PREF_COLLAPSED_TOPIC_IDS, "")
            ?.split(",")
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet()
            ?: emptySet()
    )

    val treeItems: StateFlow<List<TreeItem>> = repo.getTreeFlow()
        .combine(_collapsedIds) { roots, collapsed -> TreeBuilder.flatten(roots, collapsed) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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

    // ── Topics ────────────────────────────────────────────────────────
    val allTopics: StateFlow<List<TopicEntity>> = repo.getAllTopics()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Library Details navigation ────────────────────────────────────
    internal val _libraryDetailsTopicId = MutableStateFlow<Long?>(null)
    val libraryDetailsTopicId: StateFlow<Long?> = _libraryDetailsTopicId

    // ── Lock ──────────────────────────────────────────────────────────
    internal val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked

    // ── Playback preferences ──────────────────────────────────────────
    internal val _autoNavigateToListen =
        MutableStateFlow(prefs.getBoolean(PREF_AUTO_NAVIGATE, false))
    val autoNavigateToListen: StateFlow<Boolean> = _autoNavigateToListen

    internal val _scrubBackSecs =
        MutableStateFlow(prefs.getInt(PREF_SCRUB_BACK_SECS, 15))
    val scrubBackSecs: StateFlow<Int> = _scrubBackSecs

    internal val _scrubForwardSecs =
        MutableStateFlow(prefs.getInt(PREF_SCRUB_FORWARD_SECS, 15))
    val scrubForwardSecs: StateFlow<Int> = _scrubForwardSecs

    internal val _markRewindThresholdSecs =
        MutableStateFlow(prefs.getFloat(MarkJumpLogic.PREF_REWIND_THRESHOLD_SECS, MarkJumpLogic.DEFAULT_REWIND_THRESHOLD_SECS))
    val markRewindThresholdSecs: StateFlow<Float> = _markRewindThresholdSecs

    internal val _playbackSpeed = MutableStateFlow(
        prefs.getFloat(PREF_PLAYBACK_SPEED, SPEED_DEFAULT)
    )
    val playbackSpeed: StateFlow<Float> = _playbackSpeed

    // ── Jump to Library on save ───────────────────────────────────────
    internal val _jumpToLibraryOnSave =
        MutableStateFlow(prefs.getBoolean(PREF_JUMP_TO_LIBRARY, true))
    val jumpToLibraryOnSave: StateFlow<Boolean> = _jumpToLibraryOnSave

    // ── Theme mode ────────────────────────────────────────────────────
    internal val _themeMode =
        MutableStateFlow(prefs.getString(PREF_THEME_MODE, "system") ?: "system")
    val themeMode: StateFlow<String> = _themeMode

    // ── Waveform style ────────────────────────────────────────────────────────
    //
    // The active style is stored as a string pref ("standard" | "sky" |
    // "sky_lights") so that adding a new style never requires a pref migration.
    // waveformDisplayConfig carries the three draw-time settings (alpha, ruler
    // coverage, unplayed-only clip).  Changing these does NOT require a bitmap
    // rebuild — only invalidateItemDecorations() in MultiLineWaveformView.

    internal val _waveformStyleKey = MutableStateFlow(
        prefs.getString(PREF_WAVEFORM_STYLE, STYLE_STANDARD) ?: STYLE_STANDARD
    )

    val waveformStyleKey: StateFlow<String> = _waveformStyleKey

    internal val _invertWaveformTheme = MutableStateFlow(
        prefs.getBoolean(PREF_INVERT_WAVEFORM_THEME, false)
    )
    val invertWaveformTheme: StateFlow<Boolean> = _invertWaveformTheme

    // ── Waveform display config ───────────────────────────────────────────────

    internal val _bgAlpha = MutableStateFlow(
        prefs.getFloat(PREF_BG_ALPHA, WaveformDisplayConfig.DEFAULT_BACKGROUND_ALPHA)
    )

    internal val _bgExtendsUnderRuler = MutableStateFlow(
        prefs.getBoolean(PREF_BG_EXTENDS_UNDER_RULER, WaveformDisplayConfig.DEFAULT_EXTENDS_UNDER_RULER)
    )

    internal val _bgUnplayedOnly = MutableStateFlow(
        prefs.getBoolean(PREF_BG_UNPLAYED_ONLY, WaveformDisplayConfig.DEFAULT_UNPLAYED_ONLY)
    )

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
    internal val _layoutOrder = MutableStateFlow(
        LayoutElement.parseOrder(
            prefs.getString(PREF_LAYOUT_ORDER, null)
                ?: LayoutElement.toOrderString(LayoutElement.DEFAULT_ORDER)
        )
    )
    val layoutOrder: StateFlow<List<LayoutElement>> = _layoutOrder

    internal val _showTitleBar = MutableStateFlow(
        prefs.getBoolean(PREF_SHOW_TITLE_BAR, true)
    )
    val showTitleBar: StateFlow<Boolean> = _showTitleBar

    // ── Recorder widget visibility (3-state) ─────────────────────────────────

    internal val _recorderWidgetVisibility = MutableStateFlow(
        RecorderWidgetVisibility.fromString(
            prefs.getString(PREF_RECORDER_WIDGET_VISIBILITY, null)
        )
    )
    val recorderWidgetVisibility: StateFlow<RecorderWidgetVisibility> = _recorderWidgetVisibility

    // ── Hide Recorder widget while on Record tab ──────────────────────────────

    internal val _hideRecorderOnRecordTab = MutableStateFlow(
        prefs.getBoolean(PREF_HIDE_RECORDER_ON_RECORD_TAB, false)
    )
    val hideRecorderOnRecordTab: StateFlow<Boolean> = _hideRecorderOnRecordTab

    // ── Hide Listen widget while on Listen tab ────────────────────────────────

    internal val _hidePlayerOnListenTab = MutableStateFlow(
        prefs.getBoolean(PREF_HIDE_PLAYER_ON_LISTEN_TAB, false)
    )
    val hidePlayerOnListenTab: StateFlow<Boolean> = _hidePlayerOnListenTab

    // ── Player widget visibility (3-state) ───────────────────────────────────

    internal val _playerWidgetVisibility = MutableStateFlow(
        PlayerWidgetVisibility.fromString(
            prefs.getString(PREF_PLAYER_WIDGET_VISIBILITY, null)
        )
    )
    val playerWidgetVisibility: StateFlow<PlayerWidgetVisibility> = _playerWidgetVisibility

    // ── Always show player pill / recorder pill ───────────────────────────────
    //
    // When true the pill in the title bar is unconditionally visible regardless
    // of widget visibility mode or minimized state.  Persisted so the user's
    // preference survives app restarts.
    internal val _alwaysShowPlayerPill = MutableStateFlow(
        prefs.getBoolean(PREF_ALWAYS_SHOW_PLAYER_PILL, false)
    )
    val alwaysShowPlayerPill: StateFlow<Boolean> = _alwaysShowPlayerPill

    internal val _alwaysShowRecorderPill = MutableStateFlow(
        prefs.getBoolean(PREF_ALWAYS_SHOW_RECORDER_PILL, false)
    )
    val alwaysShowRecorderPill: StateFlow<Boolean> = _alwaysShowRecorderPill

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

    internal val _playerPillMinimized  = MutableStateFlow(false)
    val playerPillMinimized: StateFlow<Boolean> = _playerPillMinimized

    internal val _recorderPillMinimized = MutableStateFlow(false)
    val recorderPillMinimized: StateFlow<Boolean> = _recorderPillMinimized

    // ── Future Mode (Dev Options) ─────────────────────────────────────
    internal val _futureMode = MutableStateFlow(
        prefs.getBoolean(PREF_FUTURE_MODE, false)
    )
    val futureMode: StateFlow<Boolean> = _futureMode

    // ── Current page (tab index) — driven by MainActivity on every tab change ─

    internal val _currentPage = MutableStateFlow(MainActivity.PAGE_RECORD)
    val currentPage: StateFlow<Int> = _currentPage

    // ── Selected recording ────────────────────────────────────────────
    internal val _selectedRecordingId = MutableStateFlow(-1L)
    val selectedRecordingId: StateFlow<Long> = _selectedRecordingId

    // ── Marks ──────────────────────────────────────────────────────────
    internal val _marks = MutableStateFlow<List<MarkEntity>>(emptyList())
    val marks: StateFlow<List<MarkEntity>> = _marks

    internal val _selectedMarkId = MutableStateFlow<Long?>(null)
    val selectedMarkId: StateFlow<Long?> = _selectedMarkId

    internal var marksJob: Job? = null

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

    private val _dropMarkEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val dropMarkEvent: SharedFlow<Unit> = _dropMarkEvent
    fun requestDropMark() { _dropMarkEvent.tryEmit(Unit) }

    internal val _deleteMarkEvent = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val deleteMarkEvent: SharedFlow<Int> = _deleteMarkEvent

    // ── Mark nudge settings ───────────────────────────────────────────────────

    internal val _markNudgeSecs = MutableStateFlow(prefs.getFloat(PREF_MARK_NUDGE_SECS, 5f))
    val markNudgeSecs: StateFlow<Float> = _markNudgeSecs

    // ── Mark nudge lock ───────────────────────────────────────────────────────

    internal val _markNudgeLocked = MutableStateFlow(false)
    val markNudgeLocked: StateFlow<Boolean> = _markNudgeLocked

    /** Commits the current mark position. Clears selection and locks nudging. */
    fun commitMarkNudge() {
        _markNudgeLocked.value = true
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

    internal val _playbackMarkNudgeLocked = MutableStateFlow(true)
    val playbackMarkNudgeLocked: StateFlow<Boolean> = _playbackMarkNudgeLocked

    // ── Nudge events (observed by RecordFragment to forward to the service) ───

    data class NudgeEvent(val secs: Float, val markIndex: Int)

    // extraBufferCapacity=1 so tryEmit never drops while RecordFragment is briefly
    // away (e.g. configuration change mid-recording).
    internal val _nudgeBackEvent    = MutableSharedFlow<NudgeEvent>(extraBufferCapacity = 1)
    internal val _nudgeForwardEvent = MutableSharedFlow<NudgeEvent>(extraBufferCapacity = 1)
    val nudgeBackEvent:    SharedFlow<NudgeEvent> = _nudgeBackEvent
    val nudgeForwardEvent: SharedFlow<NudgeEvent> = _nudgeForwardEvent

    // ── Waveform ──────────────────────────────────────────────────────
    internal val waveformCache = WaveformCache(app)
    /**
     * Emits (recordingId, amplitudes) whenever a real waveform finishes
     * loading (from cache or freshly extracted). The fragment observes this
     * and passes the array to [PlaybackWaveformView.setAmplitudes].
     *
     * Null means "no waveform loaded yet / loading in progress".
     */
    internal val _waveformState = MutableStateFlow<Pair<Long, FloatArray>?>(null)
    val waveformState: StateFlow<Pair<Long, FloatArray>?> = _waveformState.asStateFlow()

    internal var waveformJob: Job? = null

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
     * Called by [app.treecast.ui.MainActivity] immediately after reading
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
     * Called by [app.treecast.ui.recovery.OrphanRecoveryDialogFragment]
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
    internal val _processingRefreshTick = MutableStateFlow(0L)

    // ── Processing status (for Settings tab) ──────────────────────────────────

    internal val recentlyCompletedJobs = mutableListOf<ProcessingJobInfo>()
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

    // ── Storage State ─────────────────────────────────────────────────

    //  Default storage volume UUID — persisted in SharedPreferences.
    //  Null means "use whatever getVolumes() returns first" (== primary external).
    internal val _defaultStorageUuid = MutableStateFlow(
        prefs.getString(PREF_DEFAULT_STORAGE_UUID, StorageVolumeHelper.UUID_PRIMARY)
            ?: StorageVolumeHelper.UUID_PRIMARY
    )
    val defaultStorageUuid: StateFlow<String> = _defaultStorageUuid

    //  Live list of available volumes, refreshed every time the UI asks.
    //  Stored as StateFlow so Settings observes it reactively.
    internal val _storageVolumes = MutableStateFlow(
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

    // ── Backup state ──────────────────────────────────────────────────────────

    /**
     * All configured backup targets, each paired with their live [AppVolume].
     * Observed by the Storage tab to render the designated-targets list.
     *
     * Re-evaluates whenever either the DB target list or the mounted volume
     * list changes, so mount/unmount events update the UI automatically.
     */
    val backupTargetUiStates: StateFlow<List<BackupTargetUiState>> = combine(
        repo.getBackupTargets(),
        storageVolumes,
    ) { targets, volumes ->
        val volumeMap = volumes.associateBy { it.uuid }
        targets.map { target ->
            BackupTargetUiState(
                entity = target,
                volume = volumeMap[target.volumeUuid],
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Removable volumes that are currently mounted and not yet designated as
     * backup targets (and not the primary internal volume).
     *
     * Shown as the "available to add" list in the Storage tab. Empty when no
     * undesignated removable volumes are connected.
     */
    val backupAvailableVolumes: StateFlow<List<AppVolume>> = combine(
        storageVolumes,
        repo.getBackupTargets(),
    ) { volumes, targets ->
        val targetUuids = targets.map { it.volumeUuid }.toSet()
        volumes.filter { vol ->
            vol.isMounted
                    && vol.uuid != StorageVolumeHelper.UUID_PRIMARY
                    && vol.uuid !in targetUuids
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Backup progress state ──────────────────────────────────────────────────

    // Tracks jobs completed in this process lifetime so the strip can show outcomes.
    private val backupRecentlyCompleted       = mutableListOf<BackupLogEntity>()
    private val backupKnownCompletedIds       = mutableSetOf<Long>()
    private var backupStateInitialized        = false

    // IDs that the user (or auto-dismiss timer) has already dismissed from the strip.
    internal val _stripDismissedIds  = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * Merges in-progress log rows with their per-log latest INFO events.
     * flatMapLatest restarts the event subscriptions whenever the set of
     * in-progress logs changes (new job starts or a job finishes).
     */
    private val activeBackupInfoFlow: Flow<List<ActiveBackupInfo>> =
        repo.getInProgressBackupLogs()
            .flatMapLatest { inProgressLogs ->
                when (inProgressLogs.size) {
                    0    -> flowOf(emptyList())
                    1    -> {
                        val log = inProgressLogs[0]
                        repo.getLatestInfoMessageForLog(log.id)
                            .map { msg -> listOf(ActiveBackupInfo(log, msg)) }
                    }
                    else -> combine(
                        inProgressLogs.map { log ->
                            repo.getLatestInfoMessageForLog(log.id)
                                .map { msg -> ActiveBackupInfo(log, msg) }
                        }
                    ) { array -> array.toList() }
                }
            }

    // ── Backup log state ──────────────────────────────────────────────────────

    /**
     * All backup log entries, newest first.
     * Observed by [BackupLogHistoryDialog] and the Settings backup card mini-list.
     */
    val backupLogs: StateFlow<List<BackupLogEntity>> =
        repo.getBackupLogs()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Live snapshot of all backup activity.
     *
     * Combines [activeBackupInfoFlow] (in-progress rows + events) with [backupLogs]
     * (all rows) to detect transitions from in-progress → completed and populate
     * [BackupUiState.recentlyCompletedJobs] for the title-bar strip.
     *
     * Observed by both [SettingsFragment] (progress card) and [MainActivity] (strip).
     */
    val backupUiState: StateFlow<BackupUiState> = combine(
        activeBackupInfoFlow,
        backupLogs,
    ) { activeInfos, allLogs ->
        if (!backupStateInitialized) {
            // Snapshot existing completed rows at startup so we don't re-show them.
            allLogs.filter { it.status != null }.forEach { backupKnownCompletedIds.add(it.id) }
            backupStateInitialized = true
        } else {
            // Detect newly-completed jobs (status flipped from null → non-null).
            allLogs
                .filter { it.status != null && it.id !in backupKnownCompletedIds }
                .forEach { log ->
                    backupKnownCompletedIds.add(log.id)
                    backupRecentlyCompleted.removeAll { it.id == log.id }
                    backupRecentlyCompleted.add(0, log)   // prepend — newest first
                }
        }
        BackupUiState(activeInfos, backupRecentlyCompleted.toList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BackupUiState.IDLE)

    /**
     * Derived strip state: what the title-bar strip should currently show.
     * [BackupStripState.Hidden] collapses the strip; Running/Completed show it.
     */
    val backupStripState: StateFlow<BackupStripState> = combine(
        backupUiState,
        _stripDismissedIds,
    ) { state, dismissedIds ->
        when {
            state.activeJobs.isNotEmpty() -> BackupStripState.Running(
                primary    = state.activeJobs.first(),
                extraCount = state.activeJobs.size - 1,
            )
            else -> {
                val undismissed = state.recentlyCompletedJobs
                    .firstOrNull { it.id !in dismissedIds }
                if (undismissed != null) BackupStripState.Completed(
                    log = undismissed,
                ) else BackupStripState.Hidden
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BackupStripState.Hidden)


    // ── Navigation event ───────────────────────────────────────────────────────

    /**
     * Emitted when the title-bar strip is tapped, requesting navigation to the
     * Settings → Storage tab. Observed by both [MainActivity] (switches tab page)
     * and [SettingsFragment] (switches inner tab to Storage).
     */
    internal val _navigateToStorageTab = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateToStorageTab: SharedFlow<Unit> = _navigateToStorageTab.asSharedFlow()

    // ── Recording folder structure migration (Future Mode only) ──────────────

    /**
     * Represents the lifecycle of a single migration run.
     * [Idle]    — no run in progress, button enabled.
     * [Running] — migration is executing; [movedSoFar] and [currentFile]
     *             are updated on each successful move for the progress line.
     * [Done]    — last run is complete; counters shown in status text.
     *             Stays in Done until the next run starts (i.e. survives
     *             tab switches within the same session).
     */
    sealed class MigrationState {
        object Idle : MigrationState()
        data class Running(val movedSoFar: Int, val currentFile: String) : MigrationState()
        data class Done(val moved: Int, val failed: Int) : MigrationState()
    }

    internal val _migrationState =
        MutableStateFlow<MigrationState>(Idle)
    val migrationState: StateFlow<MigrationState> = _migrationState.asStateFlow()

    internal val _playheadVisEnabled =
        MutableStateFlow(prefs.getBoolean(PREF_PLAYHEAD_VIS_ENABLED, true))
    val playheadVisEnabled: StateFlow<Boolean> = _playheadVisEnabled

    internal val _playheadVisIntensity =
        MutableStateFlow(prefs.getFloat(PREF_PLAYHEAD_VIS_INTENSITY, 0.5f))
    val playheadVisIntensity: StateFlow<Float> = _playheadVisIntensity

    // Remember-mode is read directly from prefs by PlaybackPositionHelper —
    // no separate StateFlow needed for playback logic.  But the Settings UI
    // wants a reactive value to drive its picker highlight, so we expose one.
    internal val _rememberPositionMode =
        MutableStateFlow(
            prefs.getString(
                PlaybackPositionHelper.PREF_REMEMBER_POSITION_MODE,
                PlaybackPositionHelper.DEFAULT_REMEMBER_MODE
            ) ?: PlaybackPositionHelper.DEFAULT_REMEMBER_MODE
        )
    val rememberPositionMode: StateFlow<String> = _rememberPositionMode
}