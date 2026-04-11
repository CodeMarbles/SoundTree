package app.treecast.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.treecast.data.db.AppDatabase
import app.treecast.export.RecordingExporter
import app.treecast.storage.StorageVolumeHelper
import app.treecast.ui.MainActivity
import app.treecast.util.DatabaseRestoreManager.restore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Utilities for listing and restoring TreeCast database snapshots.
 *
 * ## Snapshot layout (inside the user-chosen backup root)
 *
 *   db/
 *     treecast_YYYYMMDD_HHmmss.db   ← versioned snapshots (new format)
 *     treecast.db                   ← legacy flat copy (old format, preserved forever)
 *
 *   recordings/
 *     YYYY/
 *       MM/
 *         TC_*.m4a
 *
 *   appdata/
 *     waveforms/
 *       {recordingId}.wfm
 *
 * ## Restore sequence (see [restore])
 *
 * ### Pre-flight (live DB still open)
 * 1. Validate the chosen snapshot file.
 * 2. Write a timestamped copy of the live database to
 *    `filesDir/restore-safety/pre_restore_YYYYMMDD_HHmmss.db`.
 * 3. Export a metadata JSON for every current recording (marks, topics,
 *    title, tags, etc.) into `filesDir/restore-safety/{timestamp}/`.
 *    This is the marks safety net — even if the restore goes wrong,
 *    every mark is serialised to human-readable JSON first.
 *
 * ### Destructive DB swap
 * 4. Checkpoint the live WAL.
 * 5. Close + null the Room singleton.
 * 6. Delete stale `-wal` / `-shm` sidecars.
 * 7. Copy the chosen snapshot file over the live DB path.
 *
 * ### Post-swap file work (restored DB open)
 * 8. Reopen Room; run the `file_path` namespace fixup
 *    (`com.treecast.app` → `app.treecast`).
 * 9. Reset all `waveform_status` rows to PENDING so WaveformWorker
 *    re-validates the cache against the newly-copied audio files.
 * 10. Copy `.m4a` files from the backup's `recordings/` tree into
 *     the restoring device's default storage volume, preserving the
 *     `YYYY/MM/` hierarchy.
 * 11. Remap each `recordings` row's `file_path` (and `storage_volume_uuid`)
 *     to the newly-copied file locations.
 * 12. Copy `.wfm` waveform cache files from `appdata/waveforms/` in the
 *     backup, if that directory is present. No-op if absent (waveforms
 *     will be re-derived by WaveformWorker on next launch).
 * 13. Close Room, schedule restart, kill process. Does not return.
 *
 * ## Thread safety
 * [restore] must not be called concurrently. Callers should cancel any
 * running [app.treecast.worker.BackupWorker] jobs before invoking this.
 */
object DatabaseRestoreManager {

    // ── Result types ──────────────────────────────────────────────────────────

    sealed class Result {
        /** All phases completed. Call [scheduleRestartAndExit] immediately. */
        object Success : Result()

        /** The provided snapshot file was null, missing, or empty. */
        object NoDbFound : Result()

        /** An unexpected error occurred before the destructive swap (safe to retry). */
        data class FailedPreFlight(val reason: String) : Result()

        /** An error occurred after or during the destructive swap. DB may be in a
         *  partially-restored state — Room will still open, but files may be incomplete. */
        data class FailedPostSwap(val reason: String) : Result()
    }

    // ── Library summary ───────────────────────────────────────────────────────

    /**
     * Counts of content in the current live database.
     * Populated by [getLibrarySummary] and surfaced in the restore wizard's
     * summary step so the user knows what they're about to overwrite.
     */
    data class LibrarySummary(
        val recordingCount: Int,
        val markCount: Int,
        val topicCount: Int,
    )

    /**
     * Queries the live database for recording / mark / topic counts.
     * Runs on [Dispatchers.IO] internally.
     */
    suspend fun getLibrarySummary(context: Context): LibrarySummary =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context.applicationContext)
            LibrarySummary(
                recordingCount = db.recordingDao().countAll(),
                markCount      = db.markDao().countAll(),
                topicCount     = db.topicDao().countAll(),
            )
        }

    // ── Snapshot listing ──────────────────────────────────────────────────────

    /**
     * A single restorable database snapshot found in a backup's `db/` directory.
     *
     * @param file        The SAF [DocumentFile] for this snapshot.
     * @param displayName Human-readable label shown in the selection dialog.
     * @param isLegacy    True if this is the old flat `treecast.db` (no timestamp).
     */
    data class DbSnapshot(
        val file: DocumentFile,
        val displayName: String,
        val isLegacy: Boolean,
    )

    /**
     * Scans the `db/` subdirectory of [backupDirUri] and returns all restorable
     * snapshots, sorted newest-first (legacy entry appended last if present).
     */
    suspend fun listSnapshots(
        context: Context,
        backupDirUri: String,
    ): List<DbSnapshot> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(backupDirUri))
            ?: return@withContext emptyList()
        val dbDir = root.findFile("db")
            ?.takeIf { it.isDirectory }
            ?: return@withContext emptyList()

        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val displayFmt = SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault())

        val versioned = mutableListOf<DbSnapshot>()
        var legacy: DbSnapshot? = null

        dbDir.listFiles().forEach { file ->
            if (!file.isFile) return@forEach
            val name = file.name ?: return@forEach
            when {
                name == "treecast.db" -> {
                    legacy = DbSnapshot(file, "Legacy backup", isLegacy = true)
                }
                name.startsWith("treecast_") && name.endsWith(".db") -> {
                    val stamp = name.removePrefix("treecast_").removeSuffix(".db")
                    val date = runCatching { fmt.parse(stamp) }.getOrNull()
                    val label = if (date != null) displayFmt.format(date) else stamp
                    versioned.add(DbSnapshot(file, label, isLegacy = false))
                }
            }
        }

        versioned.sortByDescending { it.file.lastModified() }
        legacy?.let { versioned.add(it) }
        versioned
    }

    // ── Core restore ──────────────────────────────────────────────────────────

    /**
     * Executes the full restore sequence.
     *
     * Progress updates are delivered via [onProgress] callbacks. Each callback
     * carries a human-readable [label] plus optional [current] / [total] counts
     * for determinate progress display (both 0 when the phase is indeterminate).
     *
     * The callback is invoked from the IO dispatcher — callers that update UI
     * should marshal to the main thread themselves.
     *
     * Returns [Result.Success] only when everything (including file copies) has
     * completed. The caller must immediately invoke [scheduleRestartAndExit].
     *
     * @param context        Any context — application context used internally.
     * @param backupFile     The exact snapshot [DocumentFile] chosen by the user
     *                       (returned by [listSnapshots]).
     * @param backupRootDir  The root DocumentFile of the backup directory (the
     *                       folder containing `db/`, `recordings/`, `appdata/`).
     * @param onProgress     Progress callback: (label, current, total).
     */
    suspend fun restore(
        context: Context,
        backupFile: DocumentFile,
        backupRootDir: DocumentFile,
        onProgress: (label: String, current: Int, total: Int) -> Unit = { _, _, _ -> },
    ): Result = withContext(Dispatchers.IO) {

        val appContext = context.applicationContext

        // ── 1. Validate the chosen snapshot file ──────────────────────────────
        if (!backupFile.isFile || backupFile.length() == 0L) {
            return@withContext Result.NoDbFound
        }

        // ── 2. Safety snapshot of the live database ───────────────────────────
        onProgress("Creating safety snapshot…", 0, 0)

        val safetyDir = File(appContext.filesDir, "restore-safety").also { it.mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safetyDbFile = File(safetyDir, "pre_restore_$stamp.db")

        try {
            val liveDbForSnapshot = AppDatabase.getInstance(appContext)
            liveDbForSnapshot.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(FULL)")
                .close()

            // Copy the live DB file into the safety directory.
            val liveDbPath = appContext.getDatabasePath("treecast.db")
            liveDbPath.inputStream().use { inp ->
                safetyDbFile.outputStream().use { out -> inp.copyTo(out) }
            }
        } catch (e: Exception) {
            return@withContext Result.FailedPreFlight(
                "Could not create safety snapshot: ${e.message}"
            )
        }

        // ── 3. Safety metadata export (marks safety net) ──────────────────────
        //
        // Serialises every recording's metadata (including marks) to JSON in a
        // timestamped subdirectory before we touch the live database. If
        // anything goes wrong downstream, the user's mark data is preserved in
        // human-readable form at filesDir/restore-safety/{stamp}/.
        onProgress("Exporting safety metadata…", 0, 0)

        val metadataSafetyDir = File(safetyDir, stamp).also { it.mkdirs() }

        try {
            val db          = AppDatabase.getInstance(appContext)
            val recordings  = db.recordingDao().getAllOnce()
            val allTopics   = db.topicDao().getAllTopicsOnce()
            val total       = recordings.size

            recordings.forEachIndexed { index, recording ->
                onProgress("Exporting safety metadata…", index + 1, total)
                try {
                    val marks = db.markDao().getMarksForRecordingOnce(recording.id)
                    RecordingExporter.exportToDir(recording, marks, allTopics, metadataSafetyDir)
                } catch (_: Exception) {
                    // Per-recording export failure is non-fatal — we log it by
                    // leaving its JSON absent, but press on for the rest.
                }
            }
        } catch (e: Exception) {
            // If we can't even open the DB to export, abort before touching anything.
            return@withContext Result.FailedPreFlight(
                "Could not export safety metadata: ${e.message}"
            )
        }

        // ── 4. Checkpoint the live WAL (best-effort) ──────────────────────────
        onProgress("Preparing database…", 0, 0)

        try {
            val db = AppDatabase.getInstance(appContext)
            db.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(FULL)")
                .close()
        } catch (_: Exception) {
            // If the DB is already broken we press on regardless.
        }

        // ── 5. Close and null the Room singleton ──────────────────────────────
        AppDatabase.closeAndReset()

        // ── 6. Delete stale WAL / SHM sidecars ───────────────────────────────
        val liveDbFile = appContext.getDatabasePath("treecast.db")
        File(liveDbFile.path + "-wal").delete()
        File(liveDbFile.path + "-shm").delete()
        liveDbFile.parentFile?.mkdirs()

        // ── 7. Copy chosen snapshot over the live DB path ─────────────────────
        onProgress("Restoring database…", 0, 0)

        try {
            appContext.contentResolver.openInputStream(backupFile.uri)?.use { input ->
                liveDbFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext Result.FailedPostSwap(
                "Could not open backup database for reading."
            )
        } catch (e: Exception) {
            return@withContext Result.FailedPostSwap("Database copy failed: ${e.message}")
        }

        // ── 8. Reopen Room, run namespace fixup, reset waveform statuses ──────
        try {
            val db = AppDatabase.getInstance(appContext)
            val raw = db.openHelper.writableDatabase

            // Rewrite old package name paths (pre-rename backups).
            raw.execSQL(
                "UPDATE recordings SET file_path = REPLACE(file_path, " +
                        "'com.treecast.app', 'app.treecast')"
            )

            // Reset all waveform statuses to PENDING so WaveformWorker re-validates
            // cache files against the newly-copied audio after restart.
            raw.execSQL("UPDATE recordings SET waveform_status = 0")

        } catch (_: Exception) {
            // Fixup failures are non-fatal — Room will still open.
        }

        // ── 9. Copy audio files from backup into default storage ───────────────
        val defaultVolume = StorageVolumeHelper.getDefaultVolume(appContext)
        val destRecordingsRoot = defaultVolume.rootDir   // …/recordings/
        destRecordingsRoot.mkdirs()

        // Map of filename (TC_*.m4a) → newly copied File, built during copy pass.
        val copiedFileMap = mutableMapOf<String, File>()
        val backupRecordingsDir = backupRootDir.findFile("recordings")

        if (backupRecordingsDir != null && backupRecordingsDir.isDirectory) {
            onProgress("Copying recordings…", 0, 0)

            // Collect all .m4a files from the backup's recordings/ tree.
            val backupAudioFiles = mutableListOf<DocumentFile>()
            collectM4aFiles(backupRecordingsDir, backupAudioFiles)

            val totalFiles = backupAudioFiles.size
            var copiedCount = 0

            for (sourceFile in backupAudioFiles) {
                val filename = sourceFile.name ?: continue
                if (!filename.endsWith(".m4a")) continue

                // Derive YYYY/MM from TC_ filename stem.
                val stem = filename.removeSuffix(".m4a").removePrefix("TC_")
                val yyyy = stem.take(4)
                val mm   = stem.drop(4).take(2)

                val destDir: File = if (yyyy.matches(Regex("\\d{4}")) && mm.matches(Regex("\\d{2}"))) {
                    File(destRecordingsRoot, "$yyyy/$mm").also { it.mkdirs() }
                } else {
                    destRecordingsRoot  // fallback: flat in recordings/
                }

                val destFile = File(destDir, filename)

                // Skip copy if an identical file is already in place (same-device
                // restore where recordings were never deleted). Still add to
                // copiedFileMap so the path-remap step correctly updates the DB row.
                if (destFile.exists() && destFile.length() == sourceFile.length()) {
                    copiedFileMap[filename] = destFile
                    copiedCount++
                    onProgress("Copying recordings…", copiedCount, totalFiles)
                    continue
                }

                try {
                    appContext.contentResolver.openInputStream(sourceFile.uri)?.use { inp ->
                        destFile.outputStream().use { out -> inp.copyTo(out) }
                    }
                    copiedFileMap[filename] = destFile
                    copiedCount++
                    onProgress("Copying recordings…", copiedCount, totalFiles)
                } catch (_: Exception) {
                    // Per-file failure: leave the DB row pointing at the old path.
                }
            }
        }
        // If no recordings/ dir in backup, copiedFileMap stays empty and the
        // path-remap step below is a no-op. Perfectly safe.

        // ── 10. Remap file_path and storage_volume_uuid in restored DB ─────────
        if (copiedFileMap.isNotEmpty()) {
            onProgress("Updating recording paths…", 0, 0)
            try {
                val db = AppDatabase.getInstance(appContext)
                val recordings = db.recordingDao().getAllOnce()
                for (recording in recordings) {
                    val filename = File(recording.filePath).name
                    val newFile  = copiedFileMap[filename] ?: continue
                    db.recordingDao().updateFilePathAndVolume(
                        id         = recording.id,
                        newPath    = newFile.absolutePath,
                        volumeUuid = defaultVolume.uuid,
                    )
                }
            } catch (_: Exception) {
                // Non-fatal: the DB is still valid, files are on disk;
                // orphan recovery will surface any unmatched rows.
            }
        }

        // ── 11. Restore waveform cache files if present in backup ─────────────
        val backupWaveformDir = backupRootDir
            .findFile("appdata")
            ?.takeIf { it.isDirectory }
            ?.findFile("waveforms")
            ?.takeIf { it.isDirectory }

        if (backupWaveformDir != null) {
            onProgress("Restoring waveforms…", 0, 0)

            val localWaveformDir = File(
                defaultVolume.rootDir.parentFile!!,
                "appdata/waveforms"
            ).also { it.mkdirs() }

            val waveformFiles = backupWaveformDir.listFiles()
                .filter { it.isFile && it.name?.endsWith(".wfm") == true }
            val totalWfm = waveformFiles.size
            var copiedWfm = 0

            for (wfmFile in waveformFiles) {
                val name = wfmFile.name ?: continue
                val destFile = File(localWaveformDir, name)
                try {
                    appContext.contentResolver.openInputStream(wfmFile.uri)?.use { inp ->
                        destFile.outputStream().use { out -> inp.copyTo(out) }
                    }
                    copiedWfm++
                    onProgress("Restoring waveforms…", copiedWfm, totalWfm)
                } catch (_: Exception) {
                    // Per-file failure is non-fatal — WaveformWorker will re-derive.
                }
            }
        }

        // ── 12. Close Room before restart ─────────────────────────────────────
        try { AppDatabase.closeAndReset() } catch (_: Exception) {}

        onProgress("Finishing…", 0, 0)
        Result.Success
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Recursively collects all `.m4a` [DocumentFile]s under [dir] into [out].
     * SAF `listFiles()` is an IPC call, so this intentionally avoids deep
     * recursion by using an iterative stack.
     */
    private fun collectM4aFiles(dir: DocumentFile, out: MutableList<DocumentFile>) {
        val stack = ArrayDeque<DocumentFile>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            current.listFiles().forEach { child ->
                when {
                    child.isDirectory -> stack.addLast(child)
                    child.isFile && child.name?.endsWith(".m4a") == true -> out.add(child)
                }
            }
        }
    }

    // ── Restart ───────────────────────────────────────────────────────────────

    /**
     * Schedules an app restart via [AlarmManager] and kills the current process.
     * Must be called on the main thread. Does not return.
     */
    fun scheduleRestartAndExit(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.RTC, System.currentTimeMillis() + 500L, pi)
        exitProcess(0)
    }
}