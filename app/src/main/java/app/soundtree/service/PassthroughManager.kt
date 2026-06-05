package app.soundtree.service

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import app.soundtree.util.PassthroughPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages real-time audio passthrough during a recording session.
 *
 * Passthrough captures mic input via [AudioRecord] and simultaneously writes
 * PCM buffers to one or more [AudioTrack] output devices, allowing the user
 * to hear what is being recorded in real time.
 *
 * ── Ownership ─────────────────────────────────────────────────────────────────
 *
 * Created by [RecordingService] in onCreate() and torn down in onDestroy().
 * The service passes its [CoroutineScope] so the monitoring loop is cancelled
 * automatically when the service dies, without needing a separate job tracker
 * outside this class.
 *
 * ── State machine ─────────────────────────────────────────────────────────────
 *
 *   IDLE      — no monitoring, AudioRecord/AudioTrack not allocated.
 *   ARMED     — user has long-pressed to arm; monitoring will start when
 *               onRecordingStarted() is called (if selected devices exist).
 *   ACTIVE    — monitoring loop running, PCM flowing to output tracks.
 *   NO_OUTPUT — armed + recording started, but no reachable selected devices.
 *               AudioRecord is NOT running; displayed as a warning in the UI.
 *
 * ── Threading ─────────────────────────────────────────────────────────────────
 *
 * The monitoring loop runs on [Dispatchers.IO] inside a coroutine. All public
 * methods are safe to call from the main thread — they post state updates to
 * [MutableStateFlow]s and manipulate coroutine jobs, which are thread-safe.
 *
 * ── Audio format ──────────────────────────────────────────────────────────────
 *
 * Mono, 44100 Hz, PCM 16-bit. Matches [RecordingService]'s MediaRecorder
 * configuration so the monitoring mix sounds identical to the recording.
 *
 * ── Device callback ───────────────────────────────────────────────────────────
 *
 * Registers an [AudioManager.AudioDeviceCallback] for the duration of the
 * service lifetime (not just while recording) so that:
 *   1. The UI's device list stays current as BT devices connect/disconnect.
 *   2. Auto-enable fires when a whitelisted device connects during recording.
 *   3. Active output tracks are torn down when their device disconnects.
 */
class PassthroughManager(
    private val audioManager: AudioManager,
    private val prefs: PassthroughPreferences,
    private val serviceScope: CoroutineScope,
) {

    // ── Public state ──────────────────────────────────────────────────────────

    sealed class State {
        /** Not armed; monitoring not running. */
        object Idle : State()

        /** Armed; waiting for a recording session to start. */
        object Armed : State()

        /**
         * Armed + recording active + ≥1 reachable selected device.
         * [activeDeviceNames] is the set of display names currently receiving
         * audio — used by the header button label.
         */
        data class Active(val activeDeviceNames: Set<String>) : State()

        /**
         * Armed + recording active + zero reachable selected devices.
         * AudioRecord is not running. UI should display a warning.
         */
        object NoOutput : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Snapshot of all known output devices merged with persisted preferences.
     * Updated whenever devices connect/disconnect or prefs change.
     * Observed by the dialog to populate its list.
     */
    data class OutputDevice(
        val info: AudioDeviceInfo?,      // null for disconnected-but-known devices
        val key: String,
        val displayName: String,
        val isConnected: Boolean,
        val isSelected: Boolean,
        val autoEnable: Boolean,
    )

    private val _outputDevices = MutableStateFlow<List<OutputDevice>>(emptyList())
    val outputDevices: StateFlow<List<OutputDevice>> = _outputDevices.asStateFlow()

    // ── Private state ─────────────────────────────────────────────────────────

    private var isRecordingActive = false

    private var audioRecord: AudioRecord? = null
    private val audioTracks = mutableMapOf<String, AudioTrack>() // key → track
    private var monitorJob: Job? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshOutputDeviceList()
            if (isRecordingActive && _state.value != State.Idle) {
                handleDevicesAdded(addedDevices)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshOutputDeviceList()
            handleDevicesRemoved(removedDevices)
        }
    }

    private val sampleRate   = 44_100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val encoding      = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize    = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        .coerceAtLeast(4096)

    // ── Lifecycle — called by RecordingService ────────────────────────────────

    /**
     * Registers the device callback and seeds initial state.
     * Call from [RecordingService.onCreate].
     */
    fun onCreate() {
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        restoreArmedState()
        refreshOutputDeviceList()
    }

    /**
     * Tears down monitoring, releases all audio resources, and unregisters
     * the device callback.
     * Call from [RecordingService.onDestroy].
     */
    fun onDestroy() {
        stopMonitoring()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    /**
     * Notifies the manager that a recording session has started.
     * If passthrough is armed and selected devices are reachable, starts the
     * monitoring loop immediately.
     */
    fun onRecordingStarted(inputDevice: AudioDeviceInfo?) {
        isRecordingActive = true
        if (_state.value is State.Armed || _state.value is State.NoOutput) {
            startMonitoringIfPossible(inputDevice)
        }
    }

    /**
     * Notifies the manager that the recording session has ended.
     * Stops the monitoring loop but preserves armed state so the next
     * session picks up where the user left off.
     */
    fun onRecordingStopped() {
        isRecordingActive = false
        stopMonitoring()
        // Transition back to Armed (not Idle) so the button stays highlighted.
        if (_state.value is State.Active || _state.value is State.NoOutput) {
            _state.value = State.Armed
        }
    }

    // ── Public controls — called by RecordFragment / dialog ──────────────────

    /**
     * Toggles the armed state (long-press on the header button).
     * - Idle → Armed (and starts monitoring if recording is active)
     * - Armed / Active / NoOutput → Idle (stops monitoring)
     */
    fun toggleArmed(inputDevice: AudioDeviceInfo?) {
        when (_state.value) {
            is State.Idle -> {
                prefs.isArmed = true
                _state.value  = State.Armed
                if (isRecordingActive) startMonitoringIfPossible(inputDevice)
            }
            else -> {
                prefs.isArmed = false
                stopMonitoring()
                _state.value = State.Idle
            }
        }
    }

    /**
     * Updates the selected + auto-enable flags for a device from the dialog.
     * If monitoring is currently active, immediately adds or removes the
     * corresponding [AudioTrack] without restarting the loop.
     */
    fun setDevicePrefs(
        key: String,
        selected: Boolean,
        autoEnable: Boolean,
        inputDevice: AudioDeviceInfo?,
    ) {
        prefs.setDevicePrefs(key, selected, autoEnable)
        refreshOutputDeviceList()

        when (_state.value) {
            // Currently monitoring — add/remove the track live.
            is State.Active -> {
                if (selected) {
                    val device = connectedDeviceForKey(key)
                    if (device != null) addOutputTrack(key, device)
                } else {
                    removeOutputTrack(key)
                }
                updateActiveState()
            }
            // Armed + recording but no output yet — a newly selected device
            // might unblock us.
            is State.NoOutput if selected -> {
                startMonitoringIfPossible(inputDevice)
            }
            // Armed but not recording — nothing to do yet; will apply on next
            // onRecordingStarted().
            else -> Unit
        }
    }

    // ── Private — monitoring lifecycle ────────────────────────────────────────

    /**
     * Starts the monitoring loop if at least one selected device is reachable.
     * Transitions to [State.Active] or [State.NoOutput] accordingly.
     */
    private fun startMonitoringIfPossible(inputDevice: AudioDeviceInfo?) {
        val selectedKeys    = prefs.getSelectedKeys()
        val connectedOutput = reachableSelectedDevices(selectedKeys)

        if (connectedOutput.isEmpty()) {
            _state.value = State.NoOutput
            return
        }

        // Seed earpiece default on first run if it's available and nothing is selected.
        if (prefs.isFirstRun) {
            seedEarpieceIfAvailable()
            val seededOutputs = reachableSelectedDevices(prefs.getSelectedKeys())
            if (seededOutputs.isEmpty()) {
                _state.value = State.NoOutput
                return
            }
        }

        startAudioRecord(inputDevice)
        connectedOutput.forEach { (key, device) -> addOutputTrack(key, device) }
        startMonitorLoop()
        updateActiveState()
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null

        audioTracks.values.forEach { it.stop(); it.release() }
        audioTracks.clear()

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    @SuppressLint("MissingPermission")
    private fun startAudioRecord(inputDevice: AudioDeviceInfo?) {
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            encoding,
            bufferSize,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            inputDevice?.let { record.setPreferredDevice(it) }
        }
        record.startRecording()
        audioRecord = record
    }

    private fun addOutputTrack(key: String, device: AudioDeviceInfo) {
        if (audioTracks.containsKey(key)) return // already active

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            encoding,
        ).coerceAtLeast(bufferSize)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            track.setPreferredDevice(device)
        }
        track.play()
        audioTracks[key] = track
    }

    private fun removeOutputTrack(key: String) {
        audioTracks.remove(key)?.also { it.stop(); it.release() }
    }

    private fun startMonitorLoop() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch(Dispatchers.IO) {
            val buffer = ShortArray(bufferSize / 2) // bufferSize is in bytes; Short = 2 bytes
            while (isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    // Snapshot the track map to avoid ConcurrentModificationException
                    // if a track is added/removed from the main thread mid-loop.
                    val snapshot = synchronized(audioTracks) { audioTracks.values.toList() }
                    snapshot.forEach { track ->
                        if (track.state == AudioTrack.STATE_INITIALIZED &&
                            track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            track.write(buffer, 0, read)
                        }
                    }
                }
            }
        }
    }

    // ── Private — device helpers ──────────────────────────────────────────────

    private fun refreshOutputDeviceList() {
        val connected = audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { PassthroughPreferences.isEligibleOutputDevice(it) }
            .associateBy { PassthroughPreferences.deviceKey(it) }

        val selectedKeys    = prefs.getSelectedKeys()
        val autoEnableKeys  = prefs.getAutoEnableKeys()
        val knownKeys       = prefs.getKnownKeys()

        // All connected devices — whether or not they're known from a prior session.
        val connectedDevices = connected.map { (key, device) ->
            OutputDevice(
                info        = device,
                key         = key,
                displayName = PassthroughPreferences.deviceDisplayName(device),
                isConnected = true,
                isSelected  = key in selectedKeys,
                autoEnable  = key in autoEnableKeys,
            )
        }

        // Previously-seen but currently disconnected devices (show in dialog
        // so user can still configure auto-enable).
        val disconnectedDevices = (knownKeys - connected.keys).map { key ->
            OutputDevice(
                info        = null,
                key         = key,
                displayName = labelForKnownKey(key),
                isConnected = false,
                isSelected  = key in selectedKeys,
                autoEnable  = key in autoEnableKeys,
            )
        }

        // Built-ins always appear first; Bluetooth sorted by name; disconnected last.
        val sorted = (connectedDevices + disconnectedDevices).sortedWith(
            compareBy(
                { !it.isConnected },
                { it.info?.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE },
                { it.info?.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER },
                { it.displayName },
            )
        )
        _outputDevices.value = sorted
    }

    private fun handleDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
        val autoEnableKeys = prefs.getAutoEnableKeys()
        var didAutoArm = false

        for (device in addedDevices) {
            if (!PassthroughPreferences.isEligibleOutputDevice(device)) continue
            val key = PassthroughPreferences.deviceKey(device)

            // Auto-arm: device is whitelisted and passthrough is currently armed.
            if (key in autoEnableKeys && _state.value !is State.Idle) {
                prefs.setSelected(key, true)
                didAutoArm = true
            }
        }

        // If we auto-armed one or more devices and monitoring isn't already active,
        // try to start. We don't know the current inputDevice here — passing null
        // means AudioRecord will use system default routing, which is acceptable
        // since the recording is already in progress via MediaRecorder.
        if (didAutoArm && _state.value is State.NoOutput) {
            startMonitoringIfPossible(inputDevice = null)
        } else if (didAutoArm && _state.value is State.Active) {
            // Add new tracks live without restarting the loop.
            for (device in addedDevices) {
                val key = PassthroughPreferences.deviceKey(device)
                if (key in prefs.getSelectedKeys()) addOutputTrack(key, device)
            }
            updateActiveState()
        }
    }

    private fun handleDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
        for (device in removedDevices) {
            val key = PassthroughPreferences.deviceKey(device)
            removeOutputTrack(key)
        }
        if (_state.value is State.Active) {
            updateActiveState()
        }
    }

    /**
     * Updates [_state] to reflect the current set of active tracks.
     * Transitions Active → NoOutput if all tracks have been removed, or
     * NoOutput → Active if tracks were just added.
     */
    private fun updateActiveState() {
        if (!isRecordingActive) return
        if (_state.value is State.Idle) return

        val names = audioTracks.keys.mapNotNull { key ->
            _outputDevices.value.find { it.key == key }?.displayName
        }.toSet()

        _state.value = if (names.isNotEmpty()) State.Active(names) else State.NoOutput
    }

    private fun reachableSelectedDevices(selectedKeys: Set<String>): Map<String, AudioDeviceInfo> =
        audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter {
                PassthroughPreferences.isEligibleOutputDevice(it) &&
                        PassthroughPreferences.deviceKey(it) in selectedKeys
            }
            .associateBy { PassthroughPreferences.deviceKey(it) }

    private fun connectedDeviceForKey(key: String): AudioDeviceInfo? =
        audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { PassthroughPreferences.deviceKey(it) == key }

    private fun seedEarpieceIfAvailable() {
        val earpiece = audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            ?: return
        val key = PassthroughPreferences.deviceKey(earpiece)
        prefs.seedEarpieceDefault(key)
        refreshOutputDeviceList()
    }

    private fun restoreArmedState() {
        if (prefs.isArmed) {
            _state.value = State.Armed
        }
    }

    /**
     * Produces a human-readable name for a device key that is no longer
     * connected. Falls back to extracting the product name from the key string
     * (format: "{type}:{productName}") rather than showing a raw key.
     */
    private fun labelForKnownKey(key: String): String =
        key.substringAfter(':', missingDelimiterValue = key)

    companion object {
        private const val TAG = "PassthroughManager"
    }
}