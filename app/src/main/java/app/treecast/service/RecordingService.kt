package app.treecast.service

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.StatFs
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import app.treecast.R
import app.treecast.service.RecordingService.StopResult
import app.treecast.storage.StorageVolumeHelper
import app.treecast.ui.MainActivity
import app.treecast.util.RecordingTitleHelper
import app.treecast.worker.WaveformWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Foreground service that manages audio recording via MediaRecorder.
 *
 * amplitude is a SharedFlow (not StateFlow) so every polling tick
 * emits even when the value hasn't changed. StateFlow deduplicates,
 * which causes the waveform to freeze whenever two consecutive
 * maxAmplitude readings are identical — common on emulators and
 * during quiet passages on real devices.
 *
 * Mark support:
 * Marks dropped during recording are held in [pendingMarks] (a plain
 * in-memory list of elapsed-time timestamps). They are returned as part
 * of [StopResult] when stopRecording() is called, at which point the
 * caller (RecordFragment → MainViewModel) inserts them into the DB
 * alongside the new recording row. On cancel, the result is discarded,
 * so no marks are ever written.
 *
 * Notification save:
 * When the user taps "Save" in the notification, [saveFromNotification]
 * handles the entire save pipeline internally — DB write, navigation
 * intent — without needing RecordFragment to be in the foreground.
 * RecordFragment observes [notificationSaveEvent] to perform post-save
 * navigation if the app happens to already be visible.
 */
class RecordingService : Service() {

    inner class RecordingBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = RecordingBinder()

    enum class State { IDLE, RECORDING, PAUSED }

    /**
     * Return value of [stopRecording].
     * Replaces the old Pair<String?, Long> — same first two fields,
     * plus the in-memory mark timestamps collected during this session.
     */
    data class StopResult(
        val filePath: String?,
        val durationMs: Long,
        val markTimestamps: List<Long>,   // elapsed-ms values, not wall-clock
        val storageVolumeUuid: String,
        val displayName: String,
        val userHasRenamed: Boolean
    )

    /**
     * Emitted on [notificationSaveEvent] once a notification-triggered save
     * completes. [RecordFragment] observes this to perform post-save
     * navigation when the app is in the foreground, exactly mirroring the
     * behaviour of [RecordFragment.stopAndSave].
     */
    data class SavedFromNotification(val recordingId: Long, val topicId: Long?)

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    // SharedFlow: replay=1 so a new collector gets the latest value immediately,
    // but every emit() call always delivers to active collectors regardless of
    // whether the value changed.
    private val _amplitude = MutableSharedFlow<Int>(replay = 1)
    val amplitude: SharedFlow<Int> = _amplitude.asSharedFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs

    // ── In-memory marks ───────────────────────────────────────────────
    // Accumulated during a recording session; flushed to DB on save,
    // silently discarded on cancel.
    private val pendingMarks = mutableListOf<Long>()

    // Expose count and full list so the UI can display mark indicators.
    private val _pendingMarkCount = MutableStateFlow(0)
    val pendingMarkCount: StateFlow<Int> = _pendingMarkCount

    // Full list exposed for the Mini Recorder timeline.
    private val _pendingMarksFlow = MutableStateFlow<List<Long>>(emptyList())
    val pendingMarksFlow: StateFlow<List<Long>> = _pendingMarksFlow

    // ── Notification save event ───────────────────────────────────────
    // replay=1 so RecordFragment catches the event even if it subscribes
    // slightly after the DB write completes (e.g. during a brief rebind).
    private val _notificationSaveEvent = MutableSharedFlow<SavedFromNotification>(replay = 1)
    val notificationSaveEvent: SharedFlow<SavedFromNotification> =
        _notificationSaveEvent.asSharedFlow()

    private var mediaRecorder: MediaRecorder? = null

    // ── Audio input source ────────────────────────────────────────────
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    // True while SCO is active for the current recording session.
    // Cleared in stopRecording() so we know to call stopBluetoothSco().
    private var scoActive = false

    // Holds the BroadcastReceiver registered during SCO handshake.
    // Null at all other times.
    private var scoReceiver: BroadcastReceiver? = null

    // Set by RecordFragment via setPreferredInputDevice() before startRecording()
    // is called. Null = system default routing (MediaRecorder.AudioSource.MIC
    // with no device override). Cleared back to null when the service goes idle
    // so stale BT device handles from a previous session can't leak forward.
    private var preferredInputDevice: AudioDeviceInfo? = null

    private var currentFilePath: String? = null
    private var startTimeMs: Long = 0L
    private var accumulatedMs: Long = 0L

    // ── Topic tracking ────────────────────────────────────────────────
    // Kept in sync with RecordFragment's selectedTopicId via startRecording()
    // and ACTION_SET_TOPIC. Used when saving from the notification so the
    // recording lands in the correct topic even if the UI is not alive.
    private var pendingTopicId: Long? = null

    // ── Display name tracking ──────────────────────────────────────────
    // Mirrors pendingTopicId. RecordFragment pushes the user's chosen name
    // here via setDisplayName() so saveFromNotification() can use it.
    private var pendingDisplayName: String = ""
    private var pendingUserHasRenamed: Boolean = false

    fun setDisplayName(name: String, userRenamed: Boolean) {
        pendingDisplayName  = name
        pendingUserHasRenamed = userRenamed
    }

    fun getPendingDisplayName(): String = pendingDisplayName
    fun getPendingUserHasRenamed(): Boolean = pendingUserHasRenamed

    // ── Audio Input Source ─────────────────────────────────────────────
    /**
     * Sets the preferred audio input device for the next recording.
     * Pass null to use the system default.
     * Must be called before [startRecording]; ignored once recording is underway.
     */
    fun setPreferredInputDevice(device: AudioDeviceInfo?) {
        preferredInputDevice = device
    }

    // ── Storage ───────────────────────────────────────────────────────
    /**
     * The directory that the next recording file will be written to.
     * Set by [RecordFragment] via [setStorageDir] before [startRecording] is
     * called. Falls back to getExternalFilesDir(null) if never set, preserving
     * the pre-storage-feature behaviour.
     */
    private var outputDir: File? = null

    /**
     * UUID of the volume that [outputDir] belongs to. Written into the DB row
     * alongside the file path so we can do per-volume stats and orphan detection
     * without parsing paths.
     */
    private var outputVolumeUuid: String = StorageVolumeHelper.UUID_PRIMARY

    // ── Coroutine scope ───────────────────────────────────────────────
    // Used for DB writes triggered by notification actions. Lives for the
    // lifetime of the service; cancelled in onDestroy.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Used to allow Notification Controls to be visible on the lock screen
    private var mediaSessionCompat: MediaSessionCompat? = null

    private val amplitudeRunnable = object : Runnable {
        override fun run() {
            if (_state.value == State.RECORDING) {
                val amp = mediaRecorder?.maxAmplitude ?: 0
                _amplitude.tryEmit(amp)
                _elapsedMs.value = accumulatedMs + (System.currentTimeMillis() - startTimeMs)
                mainHandler.postDelayed(this, 80)
            }
        }
    }

    private val scoTimeoutRunnable = Runnable {
        Log.w(TAG, "SCO connect timeout — falling back to default mic")
        unregisterScoReceiver()
        preferredInputDevice = null
        doStartMediaRecorder()
    }

    private val mainHandler by lazy { android.os.Handler(mainLooper) }

    override fun onBind(intent: Intent?): IBinder {
//        Log.d("TC_DEBUG", "RecordingService.onBind()")
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaSessionCompat = MediaSessionCompat(this, "TreeCastRecording").also {
            it.isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
//        Log.d("TC_DEBUG", "RecordingService.onStartCommand() action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> {
                // Accept the currently selected topic so the service can save
                // to the right place even if the UI goes away before the user
                // taps Save in the notification.
                pendingTopicId = intent.getLongExtra(EXTRA_TOPIC_ID, -1L).takeIf { it != -1L }
                startRecording()
            }
            ACTION_PAUSE     -> pauseRecording()
            ACTION_RESUME    -> resumeRecording()
            ACTION_STOP      -> stopRecording()
            ACTION_SAVE      -> saveFromNotification()
            ACTION_SET_TOPIC -> {
                // RecordFragment fires this whenever the topic picker selection
                // changes while a recording is in progress.
                pendingTopicId = intent.getLongExtra(EXTRA_TOPIC_ID, -1L).takeIf { it != -1L }
            }
            ACTION_DROP_MARK -> dropMark()
        }
        return START_STICKY
    }

    override fun onDestroy() {
//        Log.w("TC_DEBUG", "RecordingService.onDestroy() called — state was ${_state.value}")
        stopRecording()
        serviceScope.cancel()

        mediaSessionCompat?.release()
        mediaSessionCompat = null

        super.onDestroy()
    }

    // ── Binder API ────────────────────────────────────────────────────

    /**
     * Starts a new recording. [topicId] is the currently selected topic
     * from [RecordFragment]; passing it here keeps [pendingTopicId] in sync
     * from the very first moment of recording.
     */
    @SuppressLint("ObsoleteSdkInt")
    fun startRecording(topicId: Long? = null): String? {
        if (_state.value != State.IDLE) return currentFilePath
        if (topicId != null) pendingTopicId = topicId

        val file = createOutputFile()
        currentFilePath = file.absolutePath
        pendingMarks.clear()
        _pendingMarkCount.value = 0
        _pendingMarksFlow.value = emptyList()
        pendingDisplayName    = ""
        pendingUserHasRenamed = false

        // Start foreground immediately — before the SCO handshake — so the OS
        // doesn't kill us during the ~200–500 ms wait for SCO to connect.
        // buildNotification() reads _state, which is still IDLE here, so the
        // notification will show the pause action (correct for "about to record").
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_record_status_recording)))

        // setPreferredDevice is a best-effort routing hint introduced in API 28.
        // If the device becomes unavailable after recording starts (e.g. BT drops),
        // Android silently falls back to the built-in mic — the recording is not
        // interrupted and no error is thrown.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            preferredInputDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            startWithSco()
        } else {
            doStartMediaRecorder()
        }

        return currentFilePath
    }

    /**
     * Initiates the Bluetooth SCO handshake, then calls doStartMediaRecorder()
     * once the SCO audio channel is confirmed connected.
     *
     * SCO connection typically completes in 200–500 ms. A 3-second timeout
     * guards against headphones that are paired but not reachable — in that
     * case we fall back to the default mic so recording still starts.
     *
     * The receiver is registered BEFORE startBluetoothSco() is called to
     * eliminate the race where the state broadcast fires before we're listening.
     */
    private fun startWithSco() {
        scoReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        mainHandler.removeCallbacks(scoTimeoutRunnable)
                        unregisterScoReceiver()
                        scoActive = true
                        doStartMediaRecorder()
                    }
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        mainHandler.removeCallbacks(scoTimeoutRunnable)
                        unregisterScoReceiver()
                        Log.w(TAG, "SCO failed (state=$state) — falling back to default mic")
                        // Clear preferredInputDevice so setPreferredDevice() is skipped
                        // and MediaRecorder routes to the built-in mic cleanly.
                        preferredInputDevice = null
                        doStartMediaRecorder()
                    }
                }
            }
        }
        registerReceiver(
            scoReceiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: setCommunicationDevice replaces startBluetoothSco.
            // Returns false if the device isn't available; fall back immediately.
            val accepted = preferredInputDevice?.let { audioManager.setCommunicationDevice(it) } ?: false
            if (!accepted) {
                mainHandler.removeCallbacks(scoTimeoutRunnable)
                unregisterScoReceiver()
                preferredInputDevice = null
                doStartMediaRecorder()
                return
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
        }
        mainHandler.postDelayed(scoTimeoutRunnable, SCO_TIMEOUT_MS)
    }

    /** Safely unregisters the SCO state receiver, ignoring double-unregister. */
    private fun unregisterScoReceiver() {
        scoReceiver?.let {
            try { unregisterReceiver(it) } catch (_: IllegalArgumentException) {}
            scoReceiver = null
        }
    }

    /**
     * Configures and starts MediaRecorder. Called either directly from
     * startRecording() (non-BT path) or from the SCO broadcast receiver
     * once the SCO channel is confirmed connected (BT path).
     *
     * Preconditions: currentFilePath is set, startForeground() already called.
     */
    @SuppressLint("ObsoleteSdkInt")
    private fun doStartMediaRecorder() {
        mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(currentFilePath)
            prepare()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                preferredInputDevice?.let { setPreferredDevice(it) }
            }
            start()
        }

        startTimeMs   = System.currentTimeMillis()
        accumulatedMs = 0L
        _state.value  = State.RECORDING
        updateNotification(getString(R.string.notif_record_status_recording))
        mainHandler.post(amplitudeRunnable)
    }


    fun pauseRecording() {
        if (_state.value != State.RECORDING) return
        mediaRecorder?.pause()
        accumulatedMs += System.currentTimeMillis() - startTimeMs
        _state.value = State.PAUSED
        updateNotification(getString(R.string.notif_record_status_paused))
        mainHandler.removeCallbacks(amplitudeRunnable)
    }

    fun resumeRecording() {
        if (_state.value != State.PAUSED) return
        mediaRecorder?.resume()
        startTimeMs = System.currentTimeMillis()
        _state.value = State.RECORDING
        updateNotification(getString(R.string.notif_record_status_recording))
        mainHandler.post(amplitudeRunnable)
    }

    fun stopRecording(): StopResult {
        // Cancel any pending SCO timeout and clean up SCO resources.
        mainHandler.removeCallbacks(scoTimeoutRunnable)
        unregisterScoReceiver()
        if (scoActive) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
            }
            scoActive = false
        }

        val duration = if (_state.value == State.RECORDING) {
            accumulatedMs + (System.currentTimeMillis() - startTimeMs)
        } else {
            accumulatedMs
        }
        try {
            mediaRecorder?.apply { stop(); release() }
        } catch (e: Exception) {
            Log.e(TAG, "Stop error: ${e.message}")
        }
        mediaRecorder = null
        _state.value = State.IDLE
        _amplitude.tryEmit(0)
        _elapsedMs.value = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)

        // otherwise the service will linger as a started service forever after the first recording.
        stopSelf()

        val path = currentFilePath
        currentFilePath = null

        val uuid = outputVolumeUuid
        outputDir = null
        outputVolumeUuid = StorageVolumeHelper.UUID_PRIMARY

        // Snapshot and clear the pending marks list before returning.
        val marks = pendingMarks.toList()
        pendingMarks.clear()
        _pendingMarkCount.value = 0
        _pendingMarksFlow.value = emptyList()

        var result = StopResult(
            filePath            = path,
            durationMs          = duration,
            markTimestamps      = marks,
            storageVolumeUuid   = uuid,
            displayName         = pendingDisplayName,
            userHasRenamed      = pendingUserHasRenamed
        )

        pendingDisplayName    = ""
        pendingUserHasRenamed = false

        // Uncomment if you want the audio input device to be reset between recordings
        //preferredInputDevice = null

        return result
    }

    /**
     * Updates the topic that will be used if the user saves from the
     * notification. Called from [RecordFragment] via Binder whenever the
     * topic picker selection changes during an active recording.
     */
    fun setTopic(topicId: Long?) {
        pendingTopicId = topicId
    }

    /**
     * Captures the current recording position as a mark.
     *
     * Uses [_elapsedMs] as the timestamp — this is always accurate
     * whether the recording is RECORDING or PAUSED, because the
     * amplitude runnable keeps it updated while recording and it
     * freezes at the correct accumulated value when paused.
     *
     * Can be called either from the in-app button (via Binder) or
     * from the notification action button (via onStartCommand /
     * ACTION_DROP_MARK intent).
     */
    fun dropMark() {
        if (_state.value == State.IDLE) return
        val posMs = _elapsedMs.value
        pendingMarks.add(posMs)
        _pendingMarkCount.value = pendingMarks.size
        _pendingMarksFlow.value = pendingMarks.toList()
        // Briefly flash the notification text to give the user feedback
        // that the mark was registered, then restore the normal status.
        updateNotification("Mark dropped at ${formatMs(posMs)}")
        mainHandler.postDelayed({
            val statusText = if (_state.value == State.RECORDING) "Recording…" else "Paused"
            updateNotification(statusText)
        }, 1500)
    }

    fun removeMarkAt(index: Int) {
        if (index !in pendingMarks.indices) return
        pendingMarks.removeAt(index)
        _pendingMarkCount.value = pendingMarks.size
        _pendingMarksFlow.value = pendingMarks.toList()
    }

    /**
     * Inserts a mark at an arbitrary historical position.
     *
     * The timestamp is clamped to [0, elapsedMs] so it can never be placed
     * in the future. Marks are kept in ascending time order so the pendingMarks
     * list stays consistent with the service's existing sorted assumptions.
     *
     * Called from [RecordFragment] when the user confirms a candidate mark
     * they placed by tapping a historical position on the MLWV.
     */
    fun addMarkAt(positionMs: Long) {
        if (_state.value == State.IDLE) return
        val clamped = positionMs.coerceIn(0L, _elapsedMs.value)
        val insertIdx = pendingMarks.indexOfFirst { it > clamped }
            .let { if (it == -1) pendingMarks.size else it }
        pendingMarks.add(insertIdx, clamped)
        _pendingMarkCount.value = pendingMarks.size
        _pendingMarksFlow.value = pendingMarks.toList()
    }

    /**
     * Moves the mark at [markIndex] backwards by [secs] seconds.
     * Floors at 0. No-op if index is out of range.
     * Pass [markIndex] = -1 to target the last mark (convenience default).
     */
    fun nudgeMarkBack(secs: Float, markIndex: Int = -1) {
        if (pendingMarks.isEmpty()) return
        val idx = if (markIndex < 0) pendingMarks.lastIndex else markIndex
        if (idx !in pendingMarks.indices) return
        val nudgeMs = (secs * 1000L).toLong()
        pendingMarks[idx] = (pendingMarks[idx] - nudgeMs).coerceAtLeast(0L)
        _pendingMarksFlow.value = pendingMarks.toList()
    }

    /**
     * Moves the mark at [markIndex] forwards by [secs] seconds.
     * Caps at current elapsed recording time. No-op if index is out of range.
     * Pass [markIndex] = -1 to target the last mark.
     */
    fun nudgeMarkForward(secs: Float, markIndex: Int = -1) {
        if (pendingMarks.isEmpty()) return
        val idx = if (markIndex < 0) pendingMarks.lastIndex else markIndex
        if (idx !in pendingMarks.indices) return
        val nudgeMs = (secs * 1000L).toLong()
        val cap = _elapsedMs.value
        pendingMarks[idx] = (pendingMarks[idx] + nudgeMs).coerceAtMost(cap)
        _pendingMarksFlow.value = pendingMarks.toList()
    }

    fun getCurrentFilePath(): String? = currentFilePath

    /**
     * Called by [RecordFragment] (via Binder) immediately before [startRecording].
     * Binds this recording session to a specific storage volume.
     */
    fun setStorageDir(dir: File, volumeUuid: String) {
        outputDir = dir
        outputVolumeUuid = volumeUuid
    }

    /**
     * Returns true if there is enough free space on [outputDir]'s volume to
     * safely start a recording, false if the user should be warned.
     * Always returns true if the volume can't be stat'd (fail-open).
     */
    fun hasSufficientFreeSpace(): Boolean {
        val dir = outputDir ?: getExternalFilesDir(null) ?: return true
        return runCatching {
            StatFs(dir.path).availableBytes >= StorageVolumeHelper.WARN_FREE_BYTES
        }.getOrDefault(true)
    }

    /**
     * Returns the volume UUID for the storage directory that is currently
     * configured for recording output. Called by [RecordFragment.stopAndSave]
     * so the correct UUID is written into the DB row alongside the file path.
     *
     * Returns [StorageVolumeHelper.UUID_PRIMARY] if no volume has been
     * explicitly set (i.e. the service is using the default output directory).
     */
    fun getOutputVolumeUuid(): String = outputVolumeUuid


    // ── Notification save ─────────────────────────────────────────────

    /**
     * Handles the "Save" notification action button.
     *
     * Stops the recorder synchronously (so the audio file is finalised
     * immediately), then launches a coroutine to write the recording and
     * any pending marks to the database. Once the DB write completes:
     *
     *  1. [notificationSaveEvent] is emitted so that [RecordFragment] can
     *     perform post-save navigation if the app is currently in the
     *     foreground.
     *  2. A [FLAG_ACTIVITY_SINGLE_TOP] intent is fired at [MainActivity] so
     *     that Android brings the app back to the Library and selects the
     *     new recording — identical behaviour to saving from inside the app.
     *     If the screen is locked or the system refuses to bring the activity
     *     forward, this is silently ignored; the recording is already safe.
     *
     * The "jump to library on save" preference is respected: if the user has
     * turned it off, neither the intent nor the navigation event is fired.
     */
    private fun saveFromNotification() {
        val result = stopRecording()
        if (result.filePath == null || !File(result.filePath).exists()) return

        val topicId      = pendingTopicId
        val filePath     = result.filePath
        val durationMs   = result.durationMs
        val fileSizeBytes = File(filePath).length()
        val title = RecordingTitleHelper.resolve(pendingDisplayName, pendingUserHasRenamed)
        val marks        = result.markTimestamps

        val repo         = (applicationContext as app.treecast.TreeCastApp).repository
        val prefs        = getSharedPreferences("treecast_settings", Context.MODE_PRIVATE)
        val jumpToLibrary = prefs.getBoolean("jump_to_library_on_save", true)

        serviceScope.launch {
            // Guard against the user having deleted the selected topic while the
            // recording was in progress. Fall back to Inbox (null) rather than
            // producing a foreign key violation on save.
            val resolvedTopicId = if (topicId != null && repo.topicExists(topicId)) topicId else null

            val recordingId = repo.saveRecordingWithMarks(
                filePath          = filePath,
                durationMs        = durationMs,
                fileSizeBytes     = fileSizeBytes,
                title             = title,
                topicId           = resolvedTopicId,
                markTimestamps    = marks,
                storageVolumeUuid = result.storageVolumeUuid
            )

            // Enqueue background waveform generation, mirroring the in-app save path.
            WaveformWorker.enqueue(
                context     = applicationContext,
                recordingId = recordingId,
                filePath    = filePath
            )

            _notificationSaveEvent.emit(SavedFromNotification(recordingId, resolvedTopicId))


            if (jumpToLibrary) {
                val navIntent = Intent(applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(MainActivity.EXTRA_SAVED_RECORDING_ID, recordingId)
                    resolvedTopicId?.let { putExtra(MainActivity.EXTRA_SAVED_TOPIC_ID, it) }
                }
                startActivity(navIntent)
            }
        }
    }

    private fun createOutputFile(): File {
        val base = outputDir
            ?: File(getExternalFilesDir(null), "recordings")
        val now = Date()
        val year  = SimpleDateFormat("yyyy", Locale.US).format(now)
        val month = SimpleDateFormat("MM",   Locale.US).format(now)
        val dir   = File(base, "$year/$month").also { it.mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
        return File(dir, "TC_$stamp.m4a")
    }

    // ── Notification ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_record_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.notif_channel_record_desc) }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    /**
     * Builds the recording notification with three action buttons:
     *   [⏸ Pause | ▶ Resume] · [💾 Save] · [📍 Mark]
     *
     * Pause and Resume are mutually exclusive based on current state.
     * Save stops the recording and persists it to the database (including
     * any marks), then re-opens the app to the Library — equivalent to
     * tapping Stop & Save inside the app.
     * Mark fires ACTION_DROP_MARK back at this service so it works from
     * the lock screen and notification shade.
     */
    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPi = PendingIntent.getActivity(
            this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // ── Pause / Resume action ─────────────────────────────────────
        val toggleAction = if (_state.value == State.PAUSED) {
            val resumeIntent = Intent(this, RecordingService::class.java)
                .apply { action = ACTION_RESUME }
            val resumePi = PendingIntent.getService(
                this, REQUEST_RESUME, resumeIntent, PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action(R.drawable.ic_resume_circle, getString(R.string.notif_record_action_resume), resumePi)
        } else {
            val pauseIntent = Intent(this, RecordingService::class.java)
                .apply { action = ACTION_PAUSE }
            val pausePi = PendingIntent.getService(
                this, REQUEST_PAUSE, pauseIntent, PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action(R.drawable.ic_pause, getString(R.string.notif_record_action_pause), pausePi)
        }

        // ── Save action ───────────────────────────────────────────────
        val saveIntent = Intent(this, RecordingService::class.java)
            .apply { action = ACTION_SAVE }
        val savePi = PendingIntent.getService(
            this, REQUEST_SAVE, saveIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val saveAction = NotificationCompat.Action(R.drawable.ic_save_check_wave, getString(R.string.notif_record_action_save), savePi)

        // ── Drop Mark action ──────────────────────────────────────────
        val markIntent = Intent(this, RecordingService::class.java)
            .apply { action = ACTION_DROP_MARK }
        val markPi = PendingIntent.getService(
            this, REQUEST_MARK, markIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val markAction = NotificationCompat.Action(R.drawable.ic_mark, getString(R.string.mark_btn_add), markPi)

        val markCountText = if (pendingMarks.isNotEmpty())
            resources.getQuantityString(
                R.plurals.notif_record_status_with_marks,
                pendingMarks.size,
                statusText,
                pendingMarks.size
            )
        else
            statusText

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(markCountText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppPi)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSessionCompat!!.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(toggleAction)
            .addAction(saveAction)
            .addAction(markAction)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        private const val TAG            = "RecordingService"
        private const val CHANNEL_ID     = "treecast_recording"
        private const val NOTIFICATION_ID = 1001

        // Intent actions
        const val ACTION_START     = "app.treecast.START"
        const val ACTION_PAUSE     = "app.treecast.PAUSE"
        const val ACTION_RESUME    = "app.treecast.RESUME"
        const val ACTION_STOP      = "app.treecast.STOP"
        const val ACTION_SAVE      = "app.treecast.SAVE"
        const val ACTION_SET_TOPIC = "app.treecast.SET_TOPIC"
        const val ACTION_DROP_MARK = "app.treecast.DROP_MARK"

        private const val SCO_TIMEOUT_MS = 3_000L

        // Intent extras
        const val EXTRA_TOPIC_ID = "app.treecast.extra.TOPIC_ID"

        // PendingIntent request codes (must be unique per action)
        private const val REQUEST_PAUSE  = 10
        private const val REQUEST_RESUME = 11
        private const val REQUEST_STOP   = 12   // kept for completeness; not in notification
        private const val REQUEST_MARK   = 13
        private const val REQUEST_SAVE   = 14

        fun startIntent(ctx: Context, topicId: Long? = null) =
            Intent(ctx, RecordingService::class.java).apply {
                action = ACTION_START
                topicId?.let { putExtra(EXTRA_TOPIC_ID, it) }
            }

        fun stopIntent(ctx: Context) =
            Intent(ctx, RecordingService::class.java).apply { action = ACTION_STOP }

        fun setTopicIntent(ctx: Context, topicId: Long?) =
            Intent(ctx, RecordingService::class.java).apply {
                action = ACTION_SET_TOPIC
                topicId?.let { putExtra(EXTRA_TOPIC_ID, it) }
                // If topicId is null no extra is added; onStartCommand reads
                // absence of the extra as "clear the topic" via takeIf.
            }
    }
}