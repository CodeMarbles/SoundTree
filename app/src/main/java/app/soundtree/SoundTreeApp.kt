package app.soundtree

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import app.soundtree.data.db.AppDatabase
import app.soundtree.data.repository.SoundTreeRepository
import app.soundtree.worker.WaveformWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class SoundTreeApp : Application() {

    val repository: SoundTreeRepository by lazy {
        SoundTreeRepository.getInstance(this)
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        migrateDatabaseFileIfNeeded()
        applyThemeFromPrefs()
        fixLegacyFilePathNamespace()
        enqueuePendingWaveformJobs()
        reconcileScheduledBackups()
        reconcileStaleBackupLogs()
    }

    /**
     * One-time file rename: moves `treecast.db` (and its WAL sidecars) to
     * `soundtree.db` so Room finds the existing database under its new name.
     *
     * Runs synchronously on the main thread before Room is opened for the
     * first time. Safe to call on every launch — if `soundtree.db` already
     * exists (or `treecast.db` is absent) this is a no-op.
     */
    private fun migrateDatabaseFileIfNeeded() {
        val oldDb = getDatabasePath("treecast.db")
        val newDb = getDatabasePath("soundtree.db")
        if (newDb.exists() || !oldDb.exists()) return   // nothing to do

        // Rename the main file and any WAL sidecars.
        oldDb.renameTo(newDb)
        File("${oldDb.path}-wal").takeIf { it.exists() }
            ?.renameTo(File("${newDb.path}-wal"))
        File("${oldDb.path}-shm").takeIf { it.exists() }
            ?.renameTo(File("${newDb.path}-shm"))
    }

    private fun applyThemeFromPrefs() {
        val prefs = getSharedPreferences("soundtree_settings", Context.MODE_PRIVATE)
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
     * One-time migration: rewrites legacy package-name prefixes in every
     * `recordings.file_path` DB row so that paths resolve correctly after
     * the TreeCast → SoundTree rename.
     *
     * Generation chain handled:
     *   com.treecast.app  →  app.treecast  →  app.soundtree
     *
     * Gated by the SharedPreferences flag [PREF_FILE_PATH_NS_FIXED] so it
     * runs exactly once per install, then never again.  Non-fatal: any
     * exception is swallowed so the app always starts.
     */
    private fun fixLegacyFilePathNamespace() {
        val prefs = getSharedPreferences("soundtree_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_FILE_PATH_NS_FIXED, false)) return

        appScope.launch {
            runCatching {
                val db  = AppDatabase.getInstance(this@SoundTreeApp)
                val raw = db.openHelper.writableDatabase

                // Step 1: oldest generation  com.treecast.app → app.treecast
                raw.execSQL(
                    "UPDATE recordings SET file_path = REPLACE(file_path, " +
                            "'com.treecast.app', 'app.treecast')"
                )
                // Step 2: previous generation  app.treecast → app.soundtree
                raw.execSQL(
                    "UPDATE recordings SET file_path = REPLACE(file_path, " +
                            "'app.treecast', 'app.soundtree')"
                )
            }
            // Mark done whether or not the SQL succeeded. If the DB already has
            // correct paths the UPDATEs are no-ops; if it fails we don't want an
            // infinite retry loop on every launch.
            prefs.edit().putBoolean(PREF_FILE_PATH_NS_FIXED, true).apply()
        }
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
                    WaveformWorker.enqueue(
                        context           = this@SoundTreeApp,
                        recordingId       = recording.id,
                        filePath          = recording.filePath,
                        storageVolumeUuid = recording.storageVolumeUuid,
                        createdAt         = recording.createdAt,
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

    /**
     * At startup, marks any backup log rows that are still status=NULL but
     * have no corresponding RUNNING or ENQUEUED WorkManager job as INTERRUPTED.
     * These are runs that were killed mid-flight without getting to finalise().
     */
    private fun reconcileStaleBackupLogs() {
        appScope.launch {
            runCatching {
                repository.reconcileStaleBackupLogs()
            }.onFailure { e ->
                android.util.Log.e("BackupReconcile", "Uncaught exception in reconcileStaleBackupLogs", e)
            }
        }
    }

    companion object {
        /** SharedPreferences flag: set to true once the file_path namespace fixup has run. */
        const val PREF_FILE_PATH_NS_FIXED = "file_path_ns_fix_done"
    }
}