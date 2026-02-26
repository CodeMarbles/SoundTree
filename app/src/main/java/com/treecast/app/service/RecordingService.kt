package com.treecast.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.treecast.app.R
import com.treecast.app.ui.MainActivity
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
        val markTimestamps: List<Long>   // elapsed-ms values, not wall-clock
    )

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

    private var mediaRecorder: MediaRecorder? = null
    private var currentFilePath: String? = null
    private var startTimeMs: Long = 0L
    private var accumulatedMs: Long = 0L

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

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START     -> startRecording()
            ACTION_PAUSE     -> pauseRecording()
            ACTION_RESUME    -> resumeRecording()
            ACTION_STOP      -> stopRecording()
            ACTION_DROP_MARK -> dropMark()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    fun startRecording(): String? {
        if (_state.value != State.IDLE) return currentFilePath

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mediaRecorder?.pause()
        }
        accumulatedMs += System.currentTimeMillis() - startTimeMs
        _state.value = State.PAUSED
        updateNotification("Paused")
        mainHandler.removeCallbacks(amplitudeRunnable)
    }

    fun resumeRecording() {
        if (_state.value != State.PAUSED) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mediaRecorder?.resume()
        }
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

        val path = currentFilePath
        currentFilePath = null

        // Snapshot and clear the pending marks list before returning.
        val marks = pendingMarks.toList()
        pendingMarks.clear()
        _pendingMarkCount.value = 0

        return StopResult(
            filePath       = path,
            durationMs     = duration,
            markTimestamps = marks
        )
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

    private fun createOutputFile(): File {
        val dir = File(getExternalFilesDir(null), "recordings").also { it.mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "TC_$stamp.m4a")
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
     *   [⏸ Pause | ▶ Resume] · [⏹ Stop] · [📍 Mark]
     *
     * Pause and Resume are mutually exclusive based on current state.
     * The Mark button fires ACTION_DROP_MARK back at this service so it
     * works from the lock screen and notification shade without the app
     * being in the foreground.
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

        // ── Stop action ───────────────────────────────────────────────
        // Note: tapping Stop from the notification stops the recording
        // but the result (file + marks) is lost, because RecordFragment
        // is not in the foreground to call stopAndSave(). This is the
        // same behaviour as before — stop-from-notification has always
        // been a "discard" operation. A future improvement could save
        // automatically, but that requires more plumbing.
        val stopIntent = Intent(this, RecordingService::class.java)
            .apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, REQUEST_STOP, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_media_previous, "Stop", stopPi
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
            .addAction(toggleAction)
            .addAction(stopAction)
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
        private const val TAG           = "RecordingService"
        private const val CHANNEL_ID    = "treecast_recording"
        private const val NOTIFICATION_ID = 1001

        // Intent actions
        const val ACTION_START     = "com.treecast.START"
        const val ACTION_PAUSE     = "com.treecast.PAUSE"
        const val ACTION_RESUME    = "com.treecast.RESUME"
        const val ACTION_STOP      = "com.treecast.STOP"
        const val ACTION_DROP_MARK = "com.treecast.DROP_MARK"

        // PendingIntent request codes (must be unique per action)
        private const val REQUEST_PAUSE  = 10
        private const val REQUEST_RESUME = 11
        private const val REQUEST_STOP   = 12
        private const val REQUEST_MARK   = 13

        fun startIntent(ctx: Context) =
            Intent(ctx, RecordingService::class.java).apply { action = ACTION_START }
        fun stopIntent(ctx: Context) =
            Intent(ctx, RecordingService::class.java).apply { action = ACTION_STOP }
    }
}