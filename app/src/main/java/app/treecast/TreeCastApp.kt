package app.treecast

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import app.treecast.data.repository.TreeCastRepository
import app.treecast.worker.WaveformWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TreeCastApp : Application() {

    val repository: TreeCastRepository by lazy {
        TreeCastRepository.getInstance(this)
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        applyThemeFromPrefs()
        enqueuePendingWaveformJobs()
        reconcileScheduledBackups()
    }

    private fun applyThemeFromPrefs() {
        val prefs = getSharedPreferences("treecast_settings", Context.MODE_PRIVATE)
        val mode = prefs.getString("theme_mode", "system") ?: "system"
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark"  -> AppCompatDelegate.MODE_NIGHT_YES
                else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /**
     * On every launch, find recordings whose waveform is PENDING or stuck
     * IN_PROGRESS and enqueue a WaveformWorker job for each.
     * WaveformWorker.enqueue uses ExistingWorkPolicy.KEEP so already-queued
     * jobs are left untouched.
     */
    private fun enqueuePendingWaveformJobs() {
        appScope.launch {
            runCatching {
                repository.getPendingWaveformRecordings().forEach { recording ->
                    WaveformWorker.Companion.enqueue(
                        context           = this@TreeCastApp,
                        recordingId       = recording.id,
                        filePath          = recording.filePath,
                        storageVolumeUuid = recording.storageVolumeUuid,
                    )
                }
            }
        }
    }

    /**
     * On every launch, re-enqueues a periodic WorkManager job for each backup
     * target that has scheduled backups enabled.
     *
     * WorkManager's job queue can be lost after a force-stop or OS pruning.
     * This ensures the schedule always reflects the DB configuration.
     * [BackupWorker.enqueueOrUpdatePeriodic] uses REPLACE policy, so already-live
     * jobs are simply refreshed rather than doubled up.
     */
    private fun reconcileScheduledBackups() {
        appScope.launch {
            runCatching {
                repository.reconcileScheduledBackups()
            }
        }
    }
}