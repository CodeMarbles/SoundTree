package app.soundtree.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.soundtree.data.db.AppDatabase
import app.soundtree.data.entities.RecordingEntity
import app.soundtree.export.RecordingExporter
import app.soundtree.storage.StorageVolumeHelper
import app.soundtree.ui.MainActivity
import app.soundtree.ui.restore.FileCategory
import app.soundtree.util.DatabaseRestoreManager.restore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Utilities for listing and restoring SoundTree database snapshots.
 *
 * ## Snapshot layout (inside the user-chosen backup root)
 *
 *   db/
 *     soundtree_YYYYMMDD_HHmmss.db  ← versioned snapshots (new format)
 *     treecast_YYYYMMDD_HHmmss.db   ← versioned snapshots (new format, legacy name)
 *     treecast.db                   ← legacy flat copy (old format, preserved forever)
 *
 *   recordings/
 *     YYYY/
 *       MM/
 *         TC_*.m4a ← (legacy names)
 *         ST_*.m4a
 *
 *   appdata/
 *     waveforms/
 *       YYYY/             ← subdirectory layout introduced alongside recordings/
 *         MM/
 *           {recordingId}.wfm
 *       {recordingId}.wfm ← flat files may be present in backups made by older builds
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
 * 8. Reopen Room; run the `file_path` namespace fixup (three-generation
 *    chain: `com.treecast.app` → `app.treecast` → `app.soundtree`);
 *    reset all `waveform_status` rows to PENDING so WaveformWorker
 *    re-validates the cache against the newly-copied audio files.
 * 9. Copy `.m4a` files from the backup's `recordings/` tree into
 *     the restoring device's default storage volume, preserving the
 *     `YYYY/MM/` hierarchy.
 * 10. Remap each `recordings` row's `file_path` (and `storage_volume_uuid`)
 *     to the newly-copied file locations.
 * 11. Copy `.wfm` waveform cache files from `appdata/waveforms/` in the
 *     backup, if that directory is present. No-op if absent (waveforms
 *     will be re-derived by WaveformWorker on next launch).
 * 12. Close Room, schedule restart, kill process. Does not return.
 *
 * ## Thread safety
 * [restore] must not be called concurrently. Callers should cancel any
 * running [app.soundtree.worker.BackupWorker] jobs before invoking this.
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

    /**
     * Classifies the outcome of a single file operation during restore.
     * Delivered via [restore]'s [onFileEvent] callback.
     *
     * COPIED  — file was read from backup and written to device storage.
     * SKIPPED — identical file (same name + size) already existed at the
     *            destination; no I/O was performed. In a recovery-over-existing-
     *            install scenario SKIPPED is reassuring — it means the file is
     *            still intact on the device.
     * FAILED  — an exception was thrown; destination may be absent or truncated.
     *            Non-fatal — restore continues, but the row will remain unmapped.
     */
    enum class FileEventType { COPIED, SKIPPED, FAILED }

    /**
     * Identifies each milestone in the restore sequence.
     * Emitted via [restore]'s [onMilestone] callback so the caller can update
     * persistent UI and logs without coupling to internal step numbering.
     */

    enum class Milestone {
        SAFETY_SNAPSHOT,   // pre_restore_*.db written to filesDir/restore-safety/
        METADATA_EXPORT,   // per-recording JSON exported (marks safety net)
        DATABASE_RESTORED, // backup snapshot copied over live treecast.db
        PATH_REMAP,        // file_path + storage_volume_uuid updated in restored DB
    }

    // ── Backup manifest ───────────────────────────────────────────────────────

    /**
     * Reads `soundtree-backup.json` from the root of the backup directory.
     * Returns null if the file is absent or malformed — callers should treat
     * a missing manifest as "older backup, no preview available" rather than
     * an error.
     */
    suspend fun readManifest(
        context: Context,
        backupDirUri: String,
    ): BackupManifest? = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(backupDirUri))
            ?: return@withContext null
        val file = root.findFile(BackupManifest.FILENAME)
            ?.takeIf { it.isFile }
            ?: return@withContext null
        runCatching {
            context.contentResolver.openInputStream(file.uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?.let { BackupManifest.fromJson(it) }
        }.getOrNull()
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
                name == "treecast.db" || name == "soundtree.db" -> {
                    legacy = DbSnapshot(file, "Legacy backup", isLegacy = true)
                }
                (name.startsWith("treecast_") || name.startsWith("soundtree_")) && name.endsWith(".db") -> {
                    val stamp = name
                        .removePrefix("soundtree_")
                        .removePrefix("treecast_")
                        .removeSuffix(".db")
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
     * Writes a structured plain-text log of the restore operation to disk.
     *
     * Each line is: `YYYY-MM-DDTHH:mm:ss  CATEGORY  OUTCOME  detail`
     *
     * The writer is opened at the start of [restore] and must be closed by the
     * caller when the restore completes or fails. Lines are flushed on every write
     * so that partial logs survive process death mid-restore.
     *
     * Log location: `filesDir/restore-logs/restore_YYYYMMDD_HHmmss.log`
     */
    private class RestoreLogger(context: Context) {
        private val logDir = File(context.filesDir, "restore-logs").also { it.mkdirs() }
        private val stamp  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        private val timeFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

        val logFile: File = File(logDir, "restore_$stamp.log")

        private val writer = logFile.bufferedWriter().also { w ->
            // Header line so the log is self-identifying even if read in isolation.
            w.write("# SoundTree restore log — $stamp\n")
            w.flush()
        }

        /** Appends a milestone outcome line. */
        fun milestone(milestone: Milestone, success: Boolean, detail: String? = null) {
            val outcome = if (success) "PASS" else "FAIL"
            val line    = detail?.let { "$outcome  $it" } ?: outcome
            append("MILESTONE", milestone.name, line)
        }

        /** Appends a file-event line. */
        fun fileEvent(category: String, type: FileEventType, filename: String) {
            append(category, type.name, filename)
        }

        /** Appends a free-form info line (phase start, summary counts, etc.). */
        fun info(message: String) {
            append("INFO", "", message)
        }

        /** Appends a free-form warning line (phase start, summary counts, etc.). */
        fun warning(message: String) {
            append("WARNING", "", message)
        }

        /** Appends a free-form error line (phase start, summary counts, etc.). */
        fun error(message: String) {
            append("ERROR", "", message)
        }


        fun close() {
            runCatching { writer.close() }
        }

        private fun append(category: String, outcome: String, detail: String) {
            val ts = timeFmt.format(Date())
            // Pad fields for easy column-aligned reading.
            val line = "$ts  %-12s  %-8s  $detail\n".format(category, outcome)
            writer.write(line)
            writer.flush()  // flush every line — partial logs must survive process death
        }
    }

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
     * @param onFileEvent   Optional per-file callback: (category, type, filename).
     *                      Fired immediately before the matching [onProgress] call
     *                      so counters accumulated here are current when the next
     *                      progress update fires. Invoked on [Dispatchers.IO].
     * @param onMilestone   Optional milestone callback: (milestone, success, detail).
     *                      Fired once per major phase boundary with a pass/fail
     *                      outcome and an optional human-readable detail string.
     *                      Invoked on [Dispatchers.IO].
     */
    suspend fun restore(
        context: Context,
        backupFile: DocumentFile,
        backupRootDir: DocumentFile,
        targetVolumeUuid: String? = null,
        onProgress: (label: String, current: Int, total: Int) -> Unit = { _, _, _ -> },
        onFileEvent: ((category: FileCategory, type: FileEventType, filename: String) -> Unit)? = null,
        onMilestone: ((milestone: Milestone, success: Boolean, detail: String?) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {

        val appContext = context.applicationContext
        val logger     = RestoreLogger(appContext)

        // Wrap the entire body in try/finally so the log file is always closed,
        // even if an unexpected exception escapes through a return@withContext.
        try {
            restoreInternal(
                appContext  = appContext,
                backupFile  = backupFile,
                backupRootDir = backupRootDir,
                targetVolumeUuid = targetVolumeUuid,
                onProgress  = onProgress,
                onFileEvent = onFileEvent,
                onMilestone = onMilestone,
                logger      = logger,
            )
        } finally {
            logger.close()
        }
    }

    private suspend fun restoreInternal(
        appContext: Context,
        backupFile: DocumentFile,
        backupRootDir: DocumentFile,
        targetVolumeUuid: String? = null,
        onProgress: (label: String, current: Int, total: Int) -> Unit = { _, _, _ -> },
        onFileEvent: ((category: FileCategory, type: FileEventType, filename: String) -> Unit)? = null,
        onMilestone: ((milestone: Milestone, success: Boolean, detail: String?) -> Unit)? = null,
        logger: RestoreLogger,
    ): Result = withContext(Dispatchers.IO) {

        // ── 1. Validate the chosen snapshot file ──────────────────────────────
        if (!backupFile.isFile || backupFile.length() == 0L) {
            logger.warning("Snapshot file is missing or empty: ${backupFile.uri}")
            return@withContext Result.NoDbFound
        }
        logger.info("Snapshot validated: ${backupFile.name} (${backupFile.length()} bytes)")

        // ── 2. Safety snapshot of the live database ───────────────────────────
        onProgress("Creating safety snapshot…", 0, 0)
        logger.info("Creating safety snapshot of the live database")

        val safetyDir    = File(appContext.filesDir, "restore-safety").also { it.mkdirs() }
        val stamp        = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safetyDbFile = File(safetyDir, "pre_restore_$stamp.db")

        try {
            val liveDbForSnapshot = AppDatabase.getInstance(appContext)
            liveDbForSnapshot.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(FULL)")
                .close()

            val liveDbPath = appContext.getDatabasePath("soundtree.db")
            liveDbPath.inputStream().use { inp ->
                safetyDbFile.outputStream().use { out -> inp.copyTo(out) }
            }
        } catch (e: Exception) {
            logger.milestone(Milestone.SAFETY_SNAPSHOT, success = false, detail = e.message)
            onMilestone?.invoke(Milestone.SAFETY_SNAPSHOT, false, e.message)
            return@withContext Result.FailedPreFlight("Could not create safety snapshot: ${e.message}")
        }
        logger.milestone(Milestone.SAFETY_SNAPSHOT, success = true,
            detail = "pre_restore_$stamp.db")
        onMilestone?.invoke(Milestone.SAFETY_SNAPSHOT, true, "pre_restore_$stamp.db")

        // ── 3. Safety metadata export (marks safety net) ──────────────────────
        //
        // Serialises every recording's metadata (including marks) to JSON in a
        // timestamped subdirectory before we touch the live database. If
        // anything goes wrong downstream, the user's mark data is preserved in
        // human-readable form at filesDir/restore-safety/{stamp}/.
        onProgress("Exporting safety metadata…", 0, 0)
        logger.info("Exporting safety metadata to restore-safety/$stamp/")

        val metadataSafetyDir = File(safetyDir, stamp).also { it.mkdirs() }

        var recordings: List<RecordingEntity> = emptyList()
        try {
            val db        = AppDatabase.getInstance(appContext)
            recordings    = db.recordingDao().getAllOnce()
            val allTopics = db.topicDao().getAllTopicsOnce()
            val total     = recordings.size

            recordings.forEachIndexed { index, recording ->
                onProgress("Exporting safety metadata…", index + 1, total)
                try {
                    val marks = db.markDao().getMarksForRecordingOnce(recording.id)
                    RecordingExporter.exportToDir(recording, marks, allTopics, metadataSafetyDir)
                } catch (_: Exception) {
                    // Per-recording export failure is non-fatal — we log it by
                    // leaving its JSON absent, but press on for the rest.
                    logger.error("Safety metadata export failed for ${recording.filePath}")
                }
            }
        } catch (e: Exception) {
            logger.milestone(Milestone.METADATA_EXPORT, success = false, detail = e.message)
            onMilestone?.invoke(Milestone.METADATA_EXPORT, false, e.message)
            return@withContext Result.FailedPreFlight("Could not export safety metadata: ${e.message}")
        }
        logger.milestone(Milestone.METADATA_EXPORT, success = true,
            detail = "${recordings.size} recordings exported")
        onMilestone?.invoke(Milestone.METADATA_EXPORT, true,
            "${recordings.size} recordings exported")

        // ── 4. Checkpoint the live WAL (best-effort) ──────────────────────────
        onProgress("Preparing database…", 0, 0)
        logger.info("Checkpointing live WAL before swap")

        try {
            val db = AppDatabase.getInstance(appContext)
            db.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(FULL)")
                .close()
            logger.info("WAL checkpoint complete")
        } catch (e: Exception) {
            // If the DB is already broken we press on regardless.
            logger.warning("WAL checkpoint failed (non-fatal): ${e.message}")
        }

        // ── 5. Close and null the Room singleton ──────────────────────────────
        logger.info("Closing Room singleton before destructive swap")
        AppDatabase.closeAndReset()

        // ── 6. Delete stale WAL / SHM sidecars ───────────────────────────────
        val liveDbFile = appContext.getDatabasePath("soundtree.db")
        val walDeleted = File(liveDbFile.path + "-wal").delete()
        val shmDeleted = File(liveDbFile.path + "-shm").delete()
        logger.info("WAL sidecar deleted: $walDeleted  SHM sidecar deleted: $shmDeleted")
        liveDbFile.parentFile?.mkdirs()

        // ── 7. Copy chosen snapshot over the live DB path ─────────────────────
        onProgress("Restoring database…", 0, 0)
        logger.info("Beginning destructive DB swap — copying ${backupFile.name} → ${liveDbFile.name}")

        try {
            appContext.contentResolver.openInputStream(backupFile.uri)?.use { input ->
                liveDbFile.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                val msg = "Could not open backup database for reading."
                logger.milestone(Milestone.DATABASE_RESTORED, success = false, detail = msg)
                onMilestone?.invoke(Milestone.DATABASE_RESTORED, false, msg)
                return@withContext Result.FailedPostSwap(msg)
            }
        } catch (e: Exception) {
            val msg = "Database copy failed: ${e.message}"
            logger.milestone(Milestone.DATABASE_RESTORED, success = false, detail = e.message)
            onMilestone?.invoke(Milestone.DATABASE_RESTORED, false, e.message)
            return@withContext Result.FailedPostSwap(msg)
        }
        logger.milestone(Milestone.DATABASE_RESTORED, success = true,
            detail = "${liveDbFile.length()} bytes written")
        onMilestone?.invoke(Milestone.DATABASE_RESTORED, true,
            "${liveDbFile.length()} bytes written")

        // ── 8. Reopen Room, run namespace fixup, reset waveform statuses ──────
        logger.info("Reopening Room and running namespace fixup on restored DB")
        try {
            val db  = AppDatabase.getInstance(appContext)
            val raw = db.openHelper.writableDatabase

            // Rewrite legacy package-name prefixes in restored file_path values.
            // Generation 1 (oldest):  com.treecast.app
            // Generation 2:           app.treecast
            // Generation 3 (current): app.soundtree  ← no rewrite needed
            // Two passes cover the full chain; each REPLACE is a no-op when the
            // pattern is absent, so order matters only for correctness not safety.
            raw.execSQL(
                "UPDATE recordings SET file_path = REPLACE(file_path, " +
                        "'com.treecast.app', 'app.treecast')"
            )
            raw.execSQL(
                "UPDATE recordings SET file_path = REPLACE(file_path, " +
                        "'app.treecast', 'app.soundtree')"
            )
            logger.info("Namespace fixup applied (gen1→gen2→gen3 path rewrite)")

            // Reset all waveform statuses to PENDING so WaveformWorker re-validates
            // cache files against the newly-copied audio after restart.
            raw.execSQL("UPDATE recordings SET waveform_status = 0")
            logger.info("Waveform statuses reset to PENDING")

        } catch (e: Exception) {
            // Fixup failures are non-fatal — Room will still open.
            logger.warning("Namespace fixup or waveform reset failed (non-fatal): ${e.message}")
        }

        // ── 9. Copy audio files into the user's preferred storage volume ───────────
        // Use the explicitly requested volume UUID if provided; fall back to
        // getDefaultVolume() (primary external) only if the UUID isn't found.
        // This matters when the user has set SD card as their default — getDefaultVolume()
        // always returns primary emulated storage and would silently write to the wrong place.
        val defaultVolume = if (targetVolumeUuid != null) {
            StorageVolumeHelper.getVolumeByUuid(appContext, targetVolumeUuid)
                ?: StorageVolumeHelper.getDefaultVolume(appContext).also {
                    logger.warning("Preferred volume UUID $targetVolumeUuid not mounted — " +
                            "falling back to primary storage")
                }
        } else {
            StorageVolumeHelper.getDefaultVolume(appContext)
        }
        logger.info("Target storage volume: ${defaultVolume.label} (${defaultVolume.uuid})")
        val destRecordingsRoot = defaultVolume.rootDir   // …/recordings/
        destRecordingsRoot.mkdirs()

        // Map of filename (TC_*.m4a) → newly copied File, built during copy pass.
        val copiedFileMap       = mutableMapOf<String, File>()
        val backupRecordingsDir = backupRootDir.findFile("recordings")

        if (backupRecordingsDir != null && backupRecordingsDir.isDirectory) {
            onProgress("Copying recordings…", 0, 0)

            val backupAudioFiles = mutableListOf<DocumentFile>()
            collectM4aFiles(backupRecordingsDir, backupAudioFiles)

            val totalFiles = backupAudioFiles.size
            var copiedCount = 0
            logger.info("Recordings copy pass: $totalFiles files found in backup")

            for (sourceFile in backupAudioFiles) {
                val filename = sourceFile.name ?: continue
                if (!filename.endsWith(".m4a")) continue

                val stem = RecordingFileHelper.stemWithoutPrefix(filename.removeSuffix(".m4a"))
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
                    logger.fileEvent("RECORDINGS", FileEventType.SKIPPED, filename)
                    onFileEvent?.invoke(FileCategory.RECORDINGS, FileEventType.SKIPPED, filename)
                    onProgress("Copying recordings…", copiedCount, totalFiles)
                    continue
                }

                try {
                    appContext.contentResolver.openInputStream(sourceFile.uri)?.use { inp ->
                        destFile.outputStream().use { out -> inp.copyTo(out) }
                    }
                    copiedFileMap[filename] = destFile
                    copiedCount++
                    logger.fileEvent("RECORDINGS", FileEventType.COPIED, filename)
                    onFileEvent?.invoke(FileCategory.RECORDINGS, FileEventType.COPIED, filename)
                    onProgress("Copying recordings…", copiedCount, totalFiles)
                } catch (_: Exception) {
                    // Per-file failure: leave the DB row pointing at the old path.
                    // Orphan recovery will surface unmatched rows after restart.
                    logger.fileEvent("RECORDINGS", FileEventType.FAILED, filename)
                    onFileEvent?.invoke(FileCategory.RECORDINGS, FileEventType.FAILED, filename)
                }
            }
            logger.info("Recordings copy pass complete — ${copiedFileMap.size} mapped, " +
                    "${totalFiles - copiedFileMap.size} failed")
        } else {
            logger.info("No recordings/ directory found in backup — skipping audio copy")
        }
        // If no recordings/ dir in backup, copiedFileMap stays empty and the
        // path-remap step below is a no-op. Perfectly safe.

        // ── 10. Remap file_path and storage_volume_uuid in restored DB ─────────
        var remappedCount = 0
        if (copiedFileMap.isNotEmpty()) {
            onProgress("Updating recording paths…", 0, 0)
            logger.info("Remapping file_path and storage_volume_uuid for ${copiedFileMap.size} recordings")
            try {
                val db         = AppDatabase.getInstance(appContext)
                val dbRecordings = db.recordingDao().getAllOnce()
                for (recording in dbRecordings) {
                    val filename = File(recording.filePath).name
                    val newFile  = copiedFileMap[filename] ?: continue
                    db.recordingDao().updateFilePathAndVolume(
                        id         = recording.id,
                        newPath    = newFile.absolutePath,
                        volumeUuid = defaultVolume.uuid,
                    )
                    remappedCount++
                }
            } catch (e: Exception) {
                // Non-fatal: the DB is still valid, files are on disk;
                // orphan recovery will surface any unmatched rows.
                logger.warning("Path remap encountered an error (non-fatal): ${e.message}")
            }
        }
        logger.milestone(Milestone.PATH_REMAP, success = true,
            detail = "$remappedCount recordings remapped")
        onMilestone?.invoke(Milestone.PATH_REMAP, true, "$remappedCount recordings remapped")

        // ── 11. Restore waveform cache files if present in backup ──────────────
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

            val waveformEntries = mutableListOf<Pair<DocumentFile, String>>()
            collectWfmFiles(backupWaveformDir, waveformEntries, relativeDir = "")

            val totalWfm = waveformEntries.size
            var copiedWfm = 0
            logger.info("Waveform restore pass: $totalWfm files found in backup")

            for ((wfmFile, relDir) in waveformEntries) {
                val name = wfmFile.name ?: continue

                val destFile = if (relDir.isEmpty()) {
                    // Flat legacy file → copy directly into waveforms/ root.
                    // WaveformCache's lazy-migration fallback will promote it
                    // to YYYY/MM on first load.
                    File(localWaveformDir, name)
                } else {
                    // YYYY/MM structured file → recreate the subdirectory.
                    File(localWaveformDir, relDir).also { it.mkdirs() }.let { File(it, name) }
                }

                try {
                    appContext.contentResolver.openInputStream(wfmFile.uri)?.use { inp ->
                        destFile.outputStream().use { out -> inp.copyTo(out) }
                    }
                    copiedWfm++
                    logger.fileEvent("WAVEFORMS", FileEventType.COPIED, name)
                    onFileEvent?.invoke(FileCategory.WAVEFORMS, FileEventType.COPIED, name)
                    onProgress("Restoring waveforms…", copiedWfm, totalWfm)
                } catch (_: Exception) {
                    // Per-file failure is non-fatal — WaveformWorker will re-derive.
                    logger.fileEvent("WAVEFORMS", FileEventType.FAILED, name)
                    onFileEvent?.invoke(FileCategory.WAVEFORMS, FileEventType.FAILED, name)
                }
            }
            logger.info("Waveform restore pass complete — $copiedWfm / $totalWfm copied")
        } else {
            logger.info("No appdata/waveforms/ directory found in backup — skipping waveform restore")
        }

        // ── 12. Close Room before restart ─────────────────────────────────────
        logger.info("Closing Room before scheduled restart")
        try { AppDatabase.closeAndReset() } catch (_: Exception) {}

        onProgress("Finishing…", 0, 0)
        logger.info("Restore sequence complete — scheduling restart")
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

    /**
     * Recursively collects all `.wfm` [DocumentFile]s under [dir] into [out],
     * pairing each file with its directory path relative to the initial [dir]
     * (e.g. `"2024/03"` for a file in `waveforms/2024/03/`, or `""` for a
     * file stored directly in `waveforms/`).
     *
     * This allows the restore step to reconstruct whichever layout is present
     * in the backup — the new YYYY/MM structure or the legacy flat layout from
     * older builds — without special-casing either format at the call site.
     */
    private fun collectWfmFiles(
        dir: DocumentFile,
        out: MutableList<Pair<DocumentFile, String>>,
        relativeDir: String,
    ) {
        for (child in dir.listFiles()) {
            when {
                child.isDirectory -> {
                    val childName  = child.name ?: continue
                    val childRelDir = if (relativeDir.isEmpty()) childName
                    else "$relativeDir/$childName"
                    collectWfmFiles(child, out, childRelDir)
                }
                child.isFile && child.name?.endsWith(".wfm") == true -> {
                    out.add(child to relativeDir)
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