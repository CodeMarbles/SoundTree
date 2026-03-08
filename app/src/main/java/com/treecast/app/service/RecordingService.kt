package com.treecast.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.StatFs
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import com.treecast.app.TreeCastApp
import com.treecast.app.service.RecordingService.StopResult
import com.treecast.app.ui.MainActivity
import com.treecast.app.util.StorageVolumeHelper
import com.treecast.app.worker.WaveformWorker
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
        val storageVolumeUuid: String
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

    // Expose the count so the UI can show a badge / indicator if desired.
    private val _pendingMarkCount = MutableStateFlow(0)
    val pendingMarkCount: StateFlow<Int> = _pendingMarkCount

    // ── Notification save event ───────────────────────────────────────
    // replay=1 so RecordFragment catches the event even if it subscribes
    // slightly after the DB write completes (e.g. during a brief rebind).
    private val _notificationSaveEvent = MutableSharedFlow<SavedFromNotification>(replay = 1)
    val notificationSaveEvent: SharedFlow<SavedFromNotification> =
        _notificationSaveEvent.asSharedFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var currentFilePath: String? = null
    private var startTimeMs: Long = 0L
    private var accumulatedMs: Long = 0L

    // ── Topic tracking ────────────────────────────────────────────────
    // Kept in sync with RecordFragment's selectedTopicId via startRecording()
    // and ACTION_SET_TOPIC. Used when saving from the notification so the
    // recording lands in the correct topic even if the UI is not alive.
    private var pendingTopicId: Long? = null

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

    private val mainHandler by lazy { android.os.Handler(mainLooper) }

    override fun onBind(intent: Intent?): IBinder {
        Log.d("TC_DEBUG", "RecordingService.onBind()")
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
        Log.d("TC_DEBUG", "RecordingService.onStartCommand() action=${intent?.action}")
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
        Log.w("TC_DEBUG", "RecordingService.onDestroy() called — state was ${_state.value}")
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
    fun startRecording(topicId: Long? = null): String? {
        if (_state.value != State.IDLE) return currentFilePath
        if (topicId != null) pendingTopicId = topicId

        val file = createOutputFile()
        currentFilePath = file.absolutePath
        pendingMarks.clear()
        _pendingMarkCount.value = 0

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
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        startTimeMs = System.currentTimeMillis()
        accumulatedMs = 0L
        _state.value = State.RECORDING
        startForeground(NOTIFICATION_ID, buildNotification("Recording…"))
        mainHandler.post(amplitudeRunnable)
        return currentFilePath
    }

    fun pauseRecording() {
        if (_state.value != State.RECORDING) return
        mediaRecorder?.pause()
        accumulatedMs += System.currentTimeMillis() - startTimeMs
        _state.value = State.PAUSED
        updateNotification("Paused")
        mainHandler.removeCallbacks(amplitudeRunnable)
    }

    fun resumeRecording() {
        if (_state.value != State.PAUSED) return
        mediaRecorder?.resume()
        startTimeMs = System.currentTimeMillis()
        _state.value = State.RECORDING
        updateNotification("Recording…")
        mainHandler.post(amplitudeRunnable)
    }

    fun stopRecording(): StopResult {
        mainHandler.removeCallbacks(amplitudeRunnable)
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

        return StopResult(
            filePath       = path,
            durationMs     = duration,
            markTimestamps = marks,
            storageVolumeUuid = uuid
        )
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
        // Briefly flash the notification text to give the user feedback
        // that the mark was registered, then restore the normal status.
        updateNotification("Mark dropped at ${formatMs(posMs)}")
        mainHandler.postDelayed({
            val statusText = if (_state.value == State.RECORDING) "Recording…" else "Paused"
            updateNotification(statusText)
        }, 1500)
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
        val title        = "Recording – ${
            SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())
        }"
        val marks        = result.markTimestamps

        val repo         = (applicationContext as TreeCastApp).repository
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
        base.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(base, "TC_$stamp.m4a")
    }

    // ── Notification ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "TreeCast recording status" }
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
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, "Resume", resumePi
            )
        } else {
            val pauseIntent = Intent(this, RecordingService::class.java)
                .apply { action = ACTION_PAUSE }
            val pausePi = PendingIntent.getService(
                this, REQUEST_PAUSE, pauseIntent, PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, "Pause", pausePi
            )
        }

        // ── Save action ───────────────────────────────────────────────
        val saveIntent = Intent(this, RecordingService::class.java)
            .apply { action = ACTION_SAVE }
        val savePi = PendingIntent.getService(
            this, REQUEST_SAVE, saveIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val saveAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_save, "Save", savePi
        )

        // ── Drop Mark action ──────────────────────────────────────────
        val markIntent = Intent(this, RecordingService::class.java)
            .apply { action = ACTION_DROP_MARK }
        val markPi = PendingIntent.getService(
            this, REQUEST_MARK, markIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val markAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_add, "Mark", markPi
        )

        val markCountText = if (pendingMarks.isNotEmpty())
            "$statusText  ·  ${pendingMarks.size} mark${if (pendingMarks.size == 1) "" else "s"}"
        else
            statusText

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TreeCast")
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
        const val ACTION_START     = "com.treecast.START"
        const val ACTION_PAUSE     = "com.treecast.PAUSE"
        const val ACTION_RESUME    = "com.treecast.RESUME"
        const val ACTION_STOP      = "com.treecast.STOP"
        const val ACTION_SAVE      = "com.treecast.SAVE"
        const val ACTION_SET_TOPIC = "com.treecast.SET_TOPIC"
        const val ACTION_DROP_MARK = "com.treecast.DROP_MARK"

        // Intent extras
        const val EXTRA_TOPIC_ID = "com.treecast.extra.TOPIC_ID"

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