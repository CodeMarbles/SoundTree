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
 */
class RecordingService : Service() {

    inner class RecordingBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = RecordingBinder()

    enum class State { IDLE, RECORDING, PAUSED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    // SharedFlow: replay=1 so a new collector gets the latest value immediately,
    // but every emit() call always delivers to active collectors regardless of
    // whether the value changed.
    private val _amplitude = MutableSharedFlow<Int>(replay = 1)
    val amplitude: SharedFlow<Int> = _amplitude.asSharedFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs

    private var mediaRecorder: MediaRecorder? = null
    private var currentFilePath: String? = null
    private var startTimeMs: Long = 0L
    private var accumulatedMs: Long = 0L

    private val amplitudeRunnable = object : Runnable {
        override fun run() {
            if (_state.value == State.RECORDING) {
                val amp = mediaRecorder?.maxAmplitude ?: 0
                _amplitude.tryEmit(amp)          // always emits, never deduplicates
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
            ACTION_START  -> startRecording()
            ACTION_PAUSE  -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP   -> stopRecording()
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

    fun stopRecording(): Pair<String?, Long> {
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
        return Pair(path, duration)
    }

    fun getCurrentFilePath(): String? = currentFilePath

    private fun createOutputFile(): File {
        val dir = File(getExternalFilesDir(null), "recordings").also { it.mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "TC_$stamp.m4a")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "TreeCast recording status" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TreeCast")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "treecast_recording"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START  = "com.treecast.START"
        const val ACTION_PAUSE  = "com.treecast.PAUSE"
        const val ACTION_RESUME = "com.treecast.RESUME"
        const val ACTION_STOP   = "com.treecast.STOP"

        fun startIntent(ctx: Context) =
            Intent(ctx, RecordingService::class.java).apply { action = ACTION_START }
        fun stopIntent(ctx: Context) =
            Intent(ctx, RecordingService::class.java).apply { action = ACTION_STOP }
    }
}
