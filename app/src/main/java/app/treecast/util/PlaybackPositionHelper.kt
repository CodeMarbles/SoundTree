package app.treecast.util

import android.content.SharedPreferences
import app.treecast.data.entities.RecordingEntity

/**
 * Central source of truth for playback-position memory rules.
 *
 * Two concerns are handled here so that playback initiation, position saving,
 * and list-item visualisation all agree on the same logic:
 *
 * 1. **Should position be persisted?**
 *    Controlled by [PREF_REMEMBER_POSITION_MODE]:
 *      - [MODE_ALWAYS]    — always persist (previous behaviour).
 *      - [MODE_NEVER]     — never persist; zero out any stored value on save.
 *      - [MODE_LONG_ONLY] — only persist when the recording is at least
 *                           [PREF_REMEMBER_LONG_THRESHOLD_SECS] seconds long.
 *
 * 2. **What position should playback actually start at?**
 *    Even when a position is stored, it is treated as 0 ("start from beginning")
 *    if it falls inside the *near-end dead-zone* — i.e. if the recording is
 *    essentially finished. This prevents the frustrating experience of tapping
 *    play on a short clip and having it immediately terminate.
 *
 *    The dead-zone width depends on recording length:
 *      - Short recordings (< [PREF_NEAR_END_DURATION_THRESHOLD_SECS] seconds):
 *            reset if within [PREF_NEAR_END_SHORT_SECS] seconds of the end.
 *      - Long recordings (≥ threshold):
 *            reset if within [PREF_NEAR_END_LONG_PCT] percent of the end.
 */
object PlaybackPositionHelper {

    // ── Pref keys ─────────────────────────────────────────────────────────────

    /** One of [MODE_ALWAYS], [MODE_NEVER], [MODE_LONG_ONLY]. */
    const val PREF_REMEMBER_POSITION_MODE = "remember_position_mode"

    /**
     * Minimum recording duration (seconds) for "Long Recordings Only" mode.
     * Default: 60s (1 minute).
     */
    const val PREF_REMEMBER_LONG_THRESHOLD_SECS = "remember_position_long_threshold_secs"

    /**
     * Near-end reset window for short recordings: if the stored position is
     * within this many seconds of the end, treat it as 0. Default: 30s.
     */
    const val PREF_NEAR_END_SHORT_SECS = "near_end_reset_short_secs"

    /**
     * Near-end reset window for long recordings: if the stored position is
     * within this percentage of the duration from the end, treat it as 0.
     * Default: 5 (= 5%).
     */
    const val PREF_NEAR_END_LONG_PCT = "near_end_reset_long_pct"

    /**
     * Duration threshold (seconds) that separates "short" from "long"
     * recordings for near-end-reset purposes. Default: 300s (5 minutes).
     */
    const val PREF_NEAR_END_DURATION_THRESHOLD_SECS = "near_end_duration_threshold_secs"

    // ── Mode constants ────────────────────────────────────────────────────────

    const val MODE_ALWAYS    = "always"
    const val MODE_NEVER     = "never"
    const val MODE_LONG_ONLY = "long_only"

    // ── Defaults ──────────────────────────────────────────────────────────────

    const val DEFAULT_REMEMBER_MODE                 = MODE_ALWAYS
    const val DEFAULT_REMEMBER_LONG_THRESHOLD_SECS  = 60
    const val DEFAULT_NEAR_END_SHORT_SECS           = 30
    const val DEFAULT_NEAR_END_LONG_PCT             = 5
    const val DEFAULT_NEAR_END_DURATION_THRESHOLD_SECS = 300
    /**
     * Master switch for near-end reset behaviour.
     * When false, [isNearEnd] always returns false and stored positions are
     * never clamped to zero on account of being close to the end.
     * Default: true (feature on, preserving previous behaviour).
     */
    const val PREF_NEAR_END_ENABLED = "near_end_reset_enabled"

    // In the defaults block:
    const val DEFAULT_NEAR_END_ENABLED = true

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns true if the position for [recording] should be written to the
     * database under the current settings.
     *
     * When this returns false the caller should write 0 to the database so
     * that stale positions from a previous mode don't linger and resurface
     * if the setting is later changed back.
     */
    fun shouldPersistPosition(recording: RecordingEntity, prefs: SharedPreferences): Boolean {
        return when (prefs.getString(PREF_REMEMBER_POSITION_MODE, DEFAULT_REMEMBER_MODE)) {
            MODE_NEVER    -> false
            MODE_LONG_ONLY -> {
                val thresholdMs = prefs.getInt(
                    PREF_REMEMBER_LONG_THRESHOLD_SECS,
                    DEFAULT_REMEMBER_LONG_THRESHOLD_SECS
                ) * 1_000L
                recording.durationMs >= thresholdMs
            }
            else           -> true  // MODE_ALWAYS
        }
    }

    /**
     * Returns the position (ms) that playback should actually start at for
     * [recording], taking both the remember-mode setting and the near-end
     * dead-zone into account.
     *
     * - Returns 0 if the setting says not to restore position for this recording.
     * - Returns 0 if the stored position is within the near-end dead-zone
     *   (i.e. the recording is essentially finished).
     * - Otherwise returns [RecordingEntity.playbackPositionMs].
     *
     * Use this everywhere a stored position is read for display or for an
     * initial seek — never read [RecordingEntity.playbackPositionMs] directly.
     */
    fun effectivePositionMs(recording: RecordingEntity, prefs: SharedPreferences): Long {
        if (!shouldPersistPosition(recording, prefs)) return 0L
        val stored = recording.playbackPositionMs
        if (stored <= 0L) return 0L
        return if (isNearEnd(stored, recording.durationMs, prefs)) 0L else stored
    }

    /**
     * Returns the playback progress fraction in [0f, 1f] for display in the
     * recording list, respecting the same near-end reset rules.
     *
     * Returns 0f for unstarted or near-end-reset recordings.
     */
    fun displayFraction(recording: RecordingEntity, prefs: SharedPreferences): Float {
        if (recording.durationMs <= 0L) return 0f
        val pos = effectivePositionMs(recording, prefs)
        if (pos <= 0L) return 0f
        return (pos.toFloat() / recording.durationMs.toFloat()).coerceIn(0f, 1f)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun isNearEnd(posMs: Long, durationMs: Long, prefs: SharedPreferences): Boolean {
        if (durationMs <= 0L) return false

        // Master toggle — when off, never treat any position as near-end.
        if (!prefs.getBoolean(PREF_NEAR_END_ENABLED, DEFAULT_NEAR_END_ENABLED)) return false

        val thresholdDurationMs = prefs.getInt(
            PREF_NEAR_END_DURATION_THRESHOLD_SECS,
            DEFAULT_NEAR_END_DURATION_THRESHOLD_SECS
        ) * 1_000L

        val remaining = durationMs - posMs

        return if (durationMs < thresholdDurationMs) {
            val windowMs = prefs.getInt(
                PREF_NEAR_END_SHORT_SECS,
                DEFAULT_NEAR_END_SHORT_SECS
            ) * 1_000L
            remaining <= windowMs
        } else {
            val pct = prefs.getInt(PREF_NEAR_END_LONG_PCT, DEFAULT_NEAR_END_LONG_PCT)
            val windowMs = (durationMs * pct / 100L)
            remaining <= windowMs
        }
    }
}