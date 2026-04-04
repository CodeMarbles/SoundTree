package app.treecast.ui

// ─────────────────────────────────────────────────────────────────────────────
// MainViewModel_Waveform.kt
//
// Extension functions on MainViewModel covering waveform concerns
// ─────────────────────────────────────────────────────────────────────────────

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import app.treecast.ui.MainViewModel.Companion.PREF_BG_ALPHA
import app.treecast.ui.MainViewModel.Companion.PREF_BG_EXTENDS_UNDER_RULER
import app.treecast.ui.MainViewModel.Companion.PREF_BG_UNPLAYED_ONLY
import app.treecast.ui.MainViewModel.Companion.PREF_INVERT_WAVEFORM_THEME
import app.treecast.ui.MainViewModel.Companion.PREF_WAVEFORM_STYLE
import app.treecast.util.WaveformExtractor
import app.treecast.worker.WaveformWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Waveform loading ──────────────────────────────────────────────────────────

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
internal fun MainViewModel.loadWaveform(recordingId: Long, filePath: String) {
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

// ── Processing queue ──────────────────────────────────────────────────────────

/**
 * Safety-net tick called by SettingsFragment every 3 s while it is visible.
 * Forces the [processingStatus] combine chain to re-evaluate even if WorkManager
 * misses emitting a terminal-state change for the last job.
 */
fun MainViewModel.tickProcessingRefresh() {
    _processingRefreshTick.value = System.currentTimeMillis()
}

/**
 * Cancels all queued waveform jobs, deletes all cached .wfm files, resets
 * every recording's status to PENDING in the DB, clears the in-memory
 * completed-jobs list, then re-enqueues a fresh job for every recording.
 *
 * Safe to call from the UI thread — all heavy work runs on IO dispatcher
 * inside the viewModelScope coroutine.
 */
fun MainViewModel.reprocessAllWaveforms() {
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

/**
 * Returns a display label for [job] in the form "$topicIcon $title".
 * Falls back to "📥 $title" for unsorted recordings and "Recording" if the
 * recording can no longer be found (e.g. deleted mid-job).
 */
fun MainViewModel.labelForJob(job: ProcessingJobInfo): String {
    val recording = allRecordings.value.firstOrNull { it.id == job.recordingId }
        ?: return "Recording"
    val icon = recording.topicId
        ?.let { allTopics.value.firstOrNull { t -> t.id == it }?.icon }
        ?: "📥"
    return "$icon ${recording.title}"
}

// ── Waveform style setters ────────────────────────────────────────────────────

fun MainViewModel.setWaveformStyleKey(key: String) {
    _waveformStyleKey.value = key
    prefs.edit().putString(PREF_WAVEFORM_STYLE, key).apply()
}

fun MainViewModel.setInvertWaveformTheme(enabled: Boolean) {
    _invertWaveformTheme.value = enabled
    prefs.edit().putBoolean(PREF_INVERT_WAVEFORM_THEME, enabled).apply()
}

fun MainViewModel.setBgAlpha(alpha: Float) {
    _bgAlpha.value = alpha
    prefs.edit().putFloat(PREF_BG_ALPHA, alpha).apply()
}

fun MainViewModel.setBgExtendsUnderRuler(extends: Boolean) {
    _bgExtendsUnderRuler.value = extends
    prefs.edit().putBoolean(PREF_BG_EXTENDS_UNDER_RULER, extends).apply()
}

fun MainViewModel.setBgUnplayedOnly(unplayedOnly: Boolean) {
    _bgUnplayedOnly.value = unplayedOnly
    prefs.edit().putBoolean(PREF_BG_UNPLAYED_ONLY, unplayedOnly).apply()
}
