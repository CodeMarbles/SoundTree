package app.treecast.ui

// ─────────────────────────────────────────────────────────────────────────────
// MainViewModel_Waveform.kt
//
// Extension functions on MainViewModel covering waveform concerns
// ─────────────────────────────────────────────────────────────────────────────

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import app.treecast.storage.StorageVolumeHelper
import app.treecast.ui.MainViewModel.Companion.PREF_BG_ALPHA
import app.treecast.ui.MainViewModel.Companion.PREF_BG_EXTENDS_UNDER_RULER
import app.treecast.ui.MainViewModel.Companion.PREF_BG_UNPLAYED_ONLY
import app.treecast.ui.MainViewModel.Companion.PREF_INVERT_WAVEFORM_THEME
import app.treecast.ui.MainViewModel.Companion.PREF_WAVEFORM_STYLE
import app.treecast.util.WaveformCache
import app.treecast.util.WaveformExtractor
import app.treecast.worker.WaveformWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Volume resolution helper ──────────────────────────────────────────────────

/**
 * Returns a [WaveformCache] scoped to the volume identified by [volumeUuid],
 * or null if that volume is not currently mounted.
 *
 * Callers should treat a null return as "cache temporarily unavailable" rather
 * than an error — it simply means the volume is not present right now.
 */
internal fun MainViewModel.waveformCacheFor(volumeUuid: String): WaveformCache? {
    val volume = StorageVolumeHelper.getVolumeByUuid(getApplication(), volumeUuid)
        ?: return null
    return WaveformCache(volume.rootDir)
}

// ── Waveform loading ──────────────────────────────────────────────────────────

/**
 * Kicks off waveform loading for [recordingId] / [filePath] on [storageVolumeUuid].
 *
 * Execution order:
 *   1. Cancel any in-flight extraction for a previous recording.
 *   2. Resolve the recording's storage volume. If unmounted, bail early —
 *      the cache and the audio file are both inaccessible until the volume
 *      returns, so there is nothing to do.
 *   3. If a cached array exists on disk, emit it immediately (< 5 ms).
 *      [WaveformCache.load] also handles lazy promotion of any flat legacy
 *      file to the canonical YYYY/MM location as a side effect.
 *   4. Otherwise, extract from the audio file on an IO thread
 *      (typically 300–800 ms even for a 1-hour M4A), cache the result,
 *      then emit.
 *
 * The fragment keeps displaying the seed-based fake waveform until
 * this emits, so there is no blank period during extraction.
 */
internal fun MainViewModel.loadWaveform(
    recordingId: Long,
    filePath: String,
    storageVolumeUuid: String,
    createdAt: Long,
) {
    waveformJob?.cancel()
    waveformJob = viewModelScope.launch(Dispatchers.IO) {
        val cache = waveformCacheFor(storageVolumeUuid) ?: return@launch

        // Fast path: already cached (also promotes flat legacy files on hit).
        val cached = cache.load(recordingId, createdAt)
        if (cached != null) {
            _waveformState.value = recordingId to cached
            return@launch
        }

        // Slow path: extract from file then persist.
        val amps = WaveformExtractor.extract(filePath)
        cache.save(recordingId, amps, createdAt)
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
 * Cancels all queued waveform jobs, deletes all cached .wfm files across every
 * currently mounted volume plus the legacy filesDir location, resets every
 * recording's waveform status to PENDING in the DB, clears the in-memory
 * completed-jobs list, then re-enqueues a fresh job for every recording.
 *
 * Guarded by [isReprocessingWaveforms] so that rapid double-taps and
 * confirmation-dialog races cannot launch two simultaneous passes.
 *
 * Recordings on currently unmounted volumes cannot have their cache files
 * deleted here. Their statuses are still reset to PENDING, so WaveformWorker
 * will regenerate them the next time the volume is mounted and the app starts.
 *
 * Safe to call from the UI thread — all heavy work runs on the IO dispatcher
 * inside the viewModelScope coroutine.
 */
fun MainViewModel.reprocessAllWaveforms() {
    if (!isReprocessingWaveforms.compareAndSet(false, true)) return
    viewModelScope.launch {
        try {
            // 1. Cancel whatever WorkManager currently has queued.
            WorkManager.getInstance(getApplication<Application>())
                .cancelAllWorkByTag(WaveformWorker.TAG)

            withContext(Dispatchers.IO) {
                // 2a. Delete waveform cache on every currently-mounted volume.
                //     deleteAll() now recursively removes all YYYY/MM subdirs.
                StorageVolumeHelper.getVolumes(getApplication()).forEach { volume ->
                    WaveformCache(volume.rootDir).deleteAll()
                }

                // 2b. Purge the legacy internal-storage cache (filesDir/waveforms_v2/).
                WaveformCache.legacyDir(getApplication()).deleteRecursively()

                // 3. Reset waveform statuses to PENDING in DB.
                repo.resetAllWaveformStatuses()
            }

            // 4. Clear in-memory completed jobs list.
            completedWaveformJobCount = 0
            startupTerminalIds.clear()

            // 5. Re-enqueue a fresh job for every recording.
            val allRecordings = repo.getAllRecordingsOnce()
            totalWaveformJobsEnqueued = allRecordings.size
            allRecordings.forEach { recording ->
                WaveformWorker.enqueue(
                    context           = getApplication(),
                    recordingId       = recording.id,
                    filePath          = recording.filePath,
                    storageVolumeUuid = recording.storageVolumeUuid,
                    createdAt         = recording.createdAt,
                )
            }
        } finally {
            isReprocessingWaveforms.set(false)
        }
    }
}

/**
 * Cancels all queued and running waveform jobs without resetting the DB or
 * deleting any cache files. Recordings whose waveforms were already processed
 * retain their cache; only the remaining queue is discarded.
 *
 * The DB statuses of unprocessed recordings are left as PENDING, so normal
 * on-demand loading will regenerate them lazily as each recording is opened.
 */
fun MainViewModel.cancelWaveformProcessing() {
    WorkManager.getInstance(getApplication<Application>())
        .cancelAllWorkByTag(WaveformWorker.TAG)
}

/**
 * Clears the in-memory list of recently completed jobs so the output section
 * of the Settings card goes blank. Has no effect on the processing queue itself.
 */
fun MainViewModel.clearCompletedWaveformJobs() {
    // Register all currently-visible IDs as excluded before clearing, so the
    // combine chain doesn't re-ingest them from WorkManager on its next emission.
    recentlyCompletedJobs.forEach { clearedJobIds.add(it.id) }
    recentlyCompletedJobs.clear()
    // completedWaveformJobCount is intentionally NOT reset here — the progress
    // bar should remain accurate even after the user clears the visible log.
    _processingRefreshTick.value = System.currentTimeMillis()
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