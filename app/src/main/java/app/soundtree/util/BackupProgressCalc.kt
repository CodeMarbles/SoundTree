package app.soundtree.util

import app.soundtree.data.entities.BackupLogEntity

/**
 * Single source of truth for the four-phase backup progress bar formula.
 *
 * Both the in-settings progress card (SettingsFragment) and the notification
 * tray (BackupWorker) must show identical progress — this object ensures they
 * can never drift apart.
 *
 * ── Phase slice map ───────────────────────────────────────────────────────
 *
 *   null / unknown  →  indeterminate (phase not yet signalled)
 *   "DB"            →  0 – 10 %  hold at midpoint (5 %) — opaque duration
 *   "RECORDINGS"    →  10 – 75 %  byte-based within-slice progress
 *   "METADATA"      →  75 – 88 %  file-count-based
 *   "WAVEFORMS"     →  88 – 100 % file-count-based
 */
object BackupProgressCalc {

    /** Scale used for NotificationCompat.setProgress and ProgressBar.max. */
    const val PROGRESS_MAX = 10_000

    /**
     * Returns a progress fraction in [0.0, 1.0], or null when the phase is
     * unknown (caller should show an indeterminate bar).
     */
    fun fraction(
        currentPhase       : String?,
        bytesCopied        : Long,
        totalBytesOnSource : Long,
        metadataDone       : Int,
        totalMetadataFiles : Int,
        waveformsDone      : Int,
        totalWaveformFiles : Int,
    ): Float? = when (currentPhase) {

        "DB" -> {
            // Duration is short and opaque; hold at slice midpoint so the bar
            // isn't frozen at 0 while the WAL checkpoint runs.
            0.05f
        }

        "RECORDINGS" -> {
            if (totalBytesOnSource > 0L)
                0.10f + (bytesCopied.toFloat() / totalBytesOnSource).coerceIn(0f, 1f) * 0.65f
            else
                0.10f   // Phase signalled but denominator not yet written — show slice floor.
        }

        "METADATA" -> {
            if (totalMetadataFiles > 0)
                0.75f + (metadataDone.toFloat() / totalMetadataFiles).coerceIn(0f, 1f) * 0.13f
            else
                0.75f   // Export disabled or denominator not yet written — show slice floor.
        }

        "WAVEFORMS" -> {
            if (totalWaveformFiles > 0)
                0.88f + (waveformsDone.toFloat() / totalWaveformFiles).coerceIn(0f, 1f) * 0.12f
            else
                0.88f   // No waveform files, or denominator not yet written — show slice floor.
        }

        else -> null    // Pre-phase or unrecognised value → indeterminate.
    }

    /**
     * Convenience overload for [BackupLogEntity] — used by SettingsFragment's
     * progress card, which observes a DB-backed entity.
     */
    fun fraction(log: BackupLogEntity): Float? = fraction(
        currentPhase       = log.currentPhase,
        bytesCopied        = log.bytesCopied,
        totalBytesOnSource = log.totalBytesOnSource,
        metadataDone       = log.metadataGenerated + log.metadataSkipped + log.metadataFailed,
        totalMetadataFiles = log.totalMetadataFiles,
        waveformsDone      = log.waveformsCopied + log.waveformsSkipped + log.waveformsFailed,
        totalWaveformFiles = log.totalWaveformFiles,
    )

    /**
     * Converts a [fraction] to an integer progress value suitable for
     * [NotificationCompat.Builder.setProgress] or [ProgressBar.setProgress]
     * when max is [PROGRESS_MAX].
     */
    fun toProgress(fraction: Float): Int =
        (fraction * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX)
}