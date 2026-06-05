package app.soundtree.util

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.os.Build
import androidx.core.content.edit

/**
 * Persistence layer for audio passthrough configuration.
 *
 * Stored in the shared "soundtree_settings" preferences file alongside all
 * other app settings.
 *
 * ── What is persisted ────────────────────────────────────────────────────────
 *
 *   Global:
 *     PREF_ARMED   — whether passthrough is armed (user's long-press state).
 *                    Armed + at least one reachable selected device = monitoring
 *                    active. Armed + no reachable devices = "No output" warning.
 *
 *   Per-device (keyed by [deviceKey]):
 *     PREF_SELECTED_PREFIX + key   — device is in the user's output set
 *     PREF_AUTO_ENABLE_PREFIX + key — connect this device → auto-arm passthrough
 *
 * ── Device keying ────────────────────────────────────────────────────────────
 *
 *   Android provides no stable unique ID for audio output devices across
 *   sessions. We use type + productName as a best-effort key. This is robust
 *   for the common cases (built-in earpiece/speaker, named BT headsets) and
 *   degrades gracefully for edge cases (two identical headsets) — the user
 *   would simply see their preferences shared between the two devices.
 *
 * ── Earpiece default ─────────────────────────────────────────────────────────
 *
 *   On first run (no PREF_ARMED key present) [isArmed] returns false and
 *   [getSelectedKeys] returns an empty set — the earpiece is not pre-selected
 *   in prefs. PassthroughManager uses [isFirstRun] to detect this and applies
 *   the earpiece-as-default logic at runtime rather than baking a magic key
 *   into prefs, which would be fragile across device types.
 */
class PassthroughPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * True if the user has armed passthrough via long-press.
     * Does not imply monitoring is active — a reachable selected device is
     * also required.
     */
    var isArmed: Boolean
        get() = prefs.getBoolean(PREF_ARMED, false)
        set(value) = prefs.edit { putBoolean(PREF_ARMED, value) }

    /**
     * True if no per-device preferences have ever been saved, meaning this is
     * effectively the first time the passthrough UI has been configured.
     * PassthroughManager uses this to seed the earpiece as the default
     * selected output device.
     */
    val isFirstRun: Boolean
        get() = prefs.getStringSet(PREF_KNOWN_KEYS, null) == null

    /**
     * Returns all device keys the user has ever interacted with (selected or
     * configured auto-enable for). Used to reconstruct the full device list in
     * the dialog, including disconnected devices.
     */
    fun getKnownKeys(): Set<String> =
        prefs.getStringSet(PREF_KNOWN_KEYS, emptySet()) ?: emptySet()

    /**
     * Returns the set of device keys the user has checked as active outputs.
     */
    fun getSelectedKeys(): Set<String> =
        prefs.getStringSet(PREF_SELECTED_KEYS, emptySet()) ?: emptySet()

    /**
     * Returns the set of device keys with auto-enable turned on.
     */
    fun getAutoEnableKeys(): Set<String> =
        prefs.getStringSet(PREF_AUTO_ENABLE_KEYS, emptySet()) ?: emptySet()

    /**
     * Marks a device as selected (checked in the output list).
     * Also registers it in the known-keys set so it persists in the dialog
     * even when disconnected.
     */
    fun setSelected(key: String, selected: Boolean) {
        val currentSelected = getSelectedKeys().toMutableSet()
        if (selected) currentSelected.add(key) else currentSelected.remove(key)
        recordKnownKey(key)
        prefs.edit { putStringSet(PREF_SELECTED_KEYS, currentSelected) }
    }

    /**
     * Sets the auto-enable flag for a device. When true, PassthroughManager
     * will arm passthrough automatically when this device connects during a
     * recording session.
     */
    fun setAutoEnable(key: String, autoEnable: Boolean) {
        val current = getAutoEnableKeys().toMutableSet()
        if (autoEnable) current.add(key) else current.remove(key)
        recordKnownKey(key)
        prefs.edit { putStringSet(PREF_AUTO_ENABLE_KEYS, current) }
    }

    /**
     * Convenience: atomically update selected + auto-enable for a device in
     * a single SharedPreferences commit. Prefer this over calling [setSelected]
     * and [setAutoEnable] separately to avoid two disk writes.
     */
    fun setDevicePrefs(key: String, selected: Boolean, autoEnable: Boolean) {
        val currentSelected    = getSelectedKeys().toMutableSet()
        val currentAutoEnable  = getAutoEnableKeys().toMutableSet()
        val currentKnown       = getKnownKeys().toMutableSet()

        if (selected) currentSelected.add(key) else currentSelected.remove(key)
        if (autoEnable) currentAutoEnable.add(key) else currentAutoEnable.remove(key)
        currentKnown.add(key)

        prefs.edit {
            putStringSet(PREF_SELECTED_KEYS, currentSelected)
                .putStringSet(PREF_AUTO_ENABLE_KEYS, currentAutoEnable)
                .putStringSet(PREF_KNOWN_KEYS, currentKnown)
        }
    }

    /**
     * Seeds the earpiece as the sole selected device on first run.
     * Should be called by PassthroughManager when [isFirstRun] is true and
     * an earpiece device is present in the output list.
     *
     * Passing [earpieceKey] rather than constructing it here keeps the key
     * derivation logic in one place ([deviceKey]).
     */
    fun seedEarpieceDefault(earpieceKey: String) {
        prefs.edit {
            putStringSet(PREF_SELECTED_KEYS, setOf(earpieceKey))
                .putStringSet(PREF_KNOWN_KEYS, setOf(earpieceKey))
        }
    }

    // ── Key derivation ────────────────────────────────────────────────────────

    companion object {
        private const val PREFS_FILE            = "soundtree_settings"
        private const val PREF_ARMED            = "passthrough_armed"
        private const val PREF_KNOWN_KEYS       = "passthrough_known_device_keys"
        private const val PREF_SELECTED_KEYS    = "passthrough_selected_device_keys"
        private const val PREF_AUTO_ENABLE_KEYS = "passthrough_auto_enable_device_keys"

        /**
         * Derives a stable-enough string key for an [AudioDeviceInfo].
         *
         * Format: "{type}:{productName}"
         * Example: "8:Sony WH-1000XM5"  (TYPE_BLUETOOTH_A2DP = 8)
         *
         * Built-in devices (earpiece, speaker) have consistent type integers
         * across all Android devices, so their keys are fully stable.
         * Bluetooth device keys are stable as long as the headset product name
         * doesn't change — which is true in practice for paired devices.
         */
        fun deviceKey(device: AudioDeviceInfo): String =
            "${device.type}:${device.productName}"

        /**
         * Human-readable display name for an output device, suitable for the
         * dialog list and the header button label.
         */
        fun deviceDisplayName(device: AudioDeviceInfo): String =
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER  -> "Speaker"
                else -> device.productName.toString()
            }

        /**
         * True if this device type can appear as an output target for
         * passthrough. Filters out irrelevant types (USB accessories used
         * only for input, telephony interfaces, etc.).
         */
        fun isEligibleOutputDevice(device: AudioDeviceInfo): Boolean =
            device.type in ELIGIBLE_OUTPUT_TYPES

        private val ELIGIBLE_OUTPUT_TYPES = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setOf(
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_HEARING_AID,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
            )
        } else {
            setOf(
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_HEARING_AID,
            )
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun recordKnownKey(key: String) {
        val current = getKnownKeys().toMutableSet()
        if (current.add(key)) {
            prefs.edit { putStringSet(PREF_KNOWN_KEYS, current) }
        }
    }
}