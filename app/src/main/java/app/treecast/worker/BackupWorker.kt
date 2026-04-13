package app.treecast.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import app.treecast.R
import app.treecast.data.dao.BackupTargetDao
import app.treecast.data.db.AppDatabase
import app.treecast.data.entities.BackupLogEntity
import app.treecast.data.entities.BackupLogEntity.BackupStatus
import app.treecast.data.entities.BackupLogEntity.BackupTrigger
import app.treecast.data.entities.BackupLogEventEntity
import app.treecast.data.entities.BackupLogEventEntity.EventType
import app.treecast.data.entities.BackupTargetEntity
import app.treecast.export.RecordingExporter
import app.treecast.service.AppNotifications
import app.treecast.storage.StorageVolumeHelper
import app.treecast.ui.MainActivity
import app.treecast.util.BackupProgressCalc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class BackupWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        const val TAG               = "BackupWorker"
        const val TAG_VOLUME_PREFIX = "backup_volume_"
        const val KEY_VOLUME_UUID   = "volume_uuid"
        const val KEY_TRIGGER       = "trigger"

        /**
         * Returns a stable per-volume notification ID.
         *
         * Uses a bounded hash of [volumeUuid] offset from [AppNotifications.NOTIF_BACKUP].
         * Range 1003–5097 gives 4 096 slots — far more than any real deployment
         * will need. The old fixed NOTIFICATION_ID is intentionally vacated so
         * concurrent jobs on different volumes no longer overwrite each other.
         *
         * The same value is also used as the PendingIntent request code for that
         * volume's deep-link intent, keeping both namespaces consistent per volume.
         */
        fun notifIdForVolume(volumeUuid: String): Int =
            AppNotifications.NOTIF_BACKUP_BASE + (volumeUuid.hashCode() and 0x0FFF)

        const val PREF_VERBOSE_LOGGING = "verbose_logging"

        private fun oneTimeName(volumeUuid: String)  = "backup_once_$volumeUuid"
        private fun periodicName(volumeUuid: String) = "backup_periodic_$volumeUuid"

        /** Enqueues a one-time backup for [volumeUuid]. Uses KEEP — no-op if already running. */
        fun enqueueOneTime(context: Context, volumeUuid: String, trigger: String) {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setInputData(workDataOf(KEY_VOLUME_UUID to volumeUuid, KEY_TRIGGER to trigger))
                .addTag(TAG)
                .addTag("$TAG_VOLUME_PREFIX$volumeUuid")
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(oneTimeName(volumeUuid), ExistingWorkPolicy.KEEP, request)
        }

        /** Enqueues or replaces the periodic backup request for [volumeUuid]. */
        fun enqueueOrUpdatePeriodic(context: Context, volumeUuid: String, intervalHours: Long) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(intervalHours, TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_VOLUME_UUID to volumeUuid, KEY_TRIGGER to BackupTrigger.SCHEDULED))
                .addTag(TAG)
                .addTag("$TAG_VOLUME_PREFIX$volumeUuid")
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(periodicName(volumeUuid), ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Cancels the periodic backup request for [volumeUuid]. */
        fun cancelPeriodic(context: Context, volumeUuid: String) {
            WorkManager.getInstance(context).cancelUniqueWork(periodicName(volumeUuid))
        }
    }

    // ── Shared run state ──────────────────────────────────────────────────────

    /**
     * Encapsulates all mutable state and shared references for a single backup
     * run. Created once in [doWork] and passed to each step function, making
     * data flow explicit and eliminating hidden side-effects across steps.
     *
     * The logging helpers ([info], [warning], [error]) write events directly to
     * the database. [warning] and [error] also increment [problemCount], which
     * drives the PARTIAL/FAILED status decision at the end of the run.
     */
    private inner class BackupRun(
        val db          : AppDatabase,
        val logId       : Long,
        val target      : BackupTargetEntity,
        val destRoot    : DocumentFile,
        val volumeUuid  : String,
        val volumeLabel : String,
        val verbose     : Boolean,
    ) {
        val logDao = db.backupLogDao()

        // ── Aggregate counters (derived at flush time) ────────────────────────
        var filesExamined = 0
        var filesCopied   = 0
        var filesSkipped  = 0
        var filesFailed   = 0
        var bytesCopied   = 0L
        var dbBackedUp    = false

        // ── Per-category counters (mutated by each step function) ─────────────
        var recordingsCopied  = 0
        var recordingsSkipped = 0
        var recordingsFailed  = 0
        var metadataGenerated = 0
        var metadataSkipped   = 0
        var metadataFailed    = 0
        var waveformsCopied   = 0
        var waveformsSkipped  = 0
        var waveformsFailed   = 0  // Non-fatal; excluded from filesFailed aggregate.

        // ── Live-progress fields (mirrored from DB for notification use) ──────
        // Set by each step function before calling logDao.updatePhase so that
        // postNotification can read them without an extra DB round-trip.
        var currentPhase       : String? = null
        var totalBytesOnSource : Long    = 0L
        var totalMetadataFiles : Int     = 0
        var totalWaveformFiles : Int     = 0

        /**
         * Number of WARNING or ERROR events logged during this run.
         * Drives the PARTIAL vs. SUCCESS distinction in [stepFinaliseAndNotify].
         *
         * Note: the original code tracked this via an `events` list that was
         * cleared on every warning/error and never populated, making
         * `problemCount` always 0. This counter replaces that broken pattern.
         */
        var problemCount = 0

        // ── State set by stepCopyRecordings, consumed by stepExportMetadata ──
        var totalOnSource    = 0
        var totalOnDest      = 0
        var totalBytesOnDest = 0L

        /**
         * The `recordings/` DocumentFile on the backup destination.
         * Set by [stepCopyRecordings]; read by [stepExportMetadata].
         */
        var recordingDestDir: DocumentFile? = null

        /**
         * Maps "YYYY/MM" → resolved DocumentFile for O(1) SAF dir lookup.
         * Populated by [stepCopyRecordings]; extended by [stepExportMetadata]
         * for months not yet seen during the recording pass.
         */
        val dirCache = mutableMapOf<String, DocumentFile>()

        /**
         * Maps filename stem (e.g. "TC_20240115_143022") → existing .json
         * DocumentFile on the destination. Used by [stepExportMetadata] to
         * check metadata freshness without a SAF directory listing per file.
         * Populated by [stepCopyRecordings].
         */
        val jsonIndex = mutableMapOf<String, DocumentFile>()

        // ── Logging helpers ───────────────────────────────────────────────────

        suspend fun info(message: String, path: String? = null) {
            logDao.insertEvent(
                BackupLogEventEntity(
                    logId      = logId,
                    severity   = EventType.INFO,
                    sourcePath = path,
                    message    = message,
                )
            )
        }

        suspend fun warning(message: String, path: String? = null) {
            problemCount++
            logDao.insertEvent(
                BackupLogEventEntity(
                    logId      = logId,
                    severity   = EventType.WARNING,
                    sourcePath = path,
                    message    = message,
                )
            )
        }

        suspend fun error(message: String, path: String? = null) {
            problemCount++
            logDao.insertEvent(
                BackupLogEventEntity(
                    logId      = logId,
                    severity   = EventType.ERROR,
                    sourcePath = path,
                    message    = message,
                )
            )
        }
    }

    // ── Worker entry point ────────────────────────────────────────────────────

    /**
     * Orchestrates the backup run. Resolves all inputs, opens the log row,
     * calls each step function in sequence, then finalises the log and posts
     * the result notification.
     *
     * Steps are self-contained: each reads what it needs from [BackupRun],
     * mutates only the counters it owns, and logs events directly to the DB.
     * An unexpected exception escaping any step is caught here so the finalize
     * step always runs and the log row is always closed.
     */
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {

        // ── 1. Resolve inputs ─────────────────────────────────────────────────
        val volumeUuid = inputData.getString(KEY_VOLUME_UUID)
            ?: return@withContext Result.failure()
        val trigger = inputData.getString(KEY_TRIGGER)
            ?: BackupTrigger.MANUAL

        val db        = AppDatabase.getInstance(applicationContext)
        val targetDao = db.backupTargetDao()

        val verbose = applicationContext
            .getSharedPreferences("treecast_settings", Context.MODE_PRIVATE)
            .getBoolean(PREF_VERBOSE_LOGGING, false)

        val target = targetDao.getByUuid(volumeUuid)
            ?: return@withContext Result.failure()

        val dirUriString = target.backupDirUri
            ?: return@withContext Result.failure()   // no directory chosen yet

        val destRoot = DocumentFile.fromTreeUri(applicationContext, Uri.parse(dirUriString))
            ?: return@withContext Result.failure()

        if (!destRoot.exists() || !destRoot.isDirectory) {
            return@withContext Result.failure()
        }

        // Denormalised into the log row so the history UI can display it even
        // when the volume is disconnected.
        val volumeLabel = StorageVolumeHelper
            .getVolumeByUuid(applicationContext, volumeUuid)
            ?.label ?: volumeUuid

        // ── 2. Open log row + initial notification ────────────────────────────
        val logId = db.backupLogDao().insert(
            BackupLogEntity(
                backupTargetUuid = volumeUuid,
                volumeUuid       = volumeUuid,
                volumeLabel      = volumeLabel,
                backupDirUri     = dirUriString,
                trigger          = trigger,
            )
        )

        postNotification(
            volumeUuid  = volumeUuid,
            volumeLabel = volumeLabel,
            text        = "Backup in progress\u2026",
        )
        // run is null → progress bar will be indeterminate, which is correct
        // since no phase has been signalled yet.

        val run = BackupRun(
            db          = db,
            logId       = logId,
            target      = target,
            destRoot    = destRoot,
            volumeUuid  = volumeUuid,
            volumeLabel = volumeLabel,
            verbose     = verbose,
        )

        // ── 3–6. Execute backup steps ─────────────────────────────────────────
        try {
            run.info("Backup started — trigger: $trigger, destination: $dirUriString")
            stepCopyDb(run)
            stepCopyRecordings(run)
            if (run.target.exportMetadataEnabled) stepExportMetadata(run)
            stepSyncWaveforms(run)
            stepFlushStats(run)
        } catch (e: Exception) {
            run.error("Unexpected error during backup: ${e.message}")
        }

        // ── 7–9. Finalise log, stamp target, post result notification ─────────
        stepFinaliseAndNotify(run, targetDao)
    }

    // ── Step 3: WAL checkpoint + database snapshot ────────────────────────────

    /**
     * Runs a WAL checkpoint on the live database and writes a timestamped
     * snapshot (e.g. `treecast_20250412_143022.db`) into `db/` on the backup
     * destination, preserving previous snapshots.
     *
     * Failure is non-fatal: the run continues and the DB-backup flag stays
     * false, which can influence the FAILED vs. PARTIAL distinction at the end.
     */
    private suspend fun stepCopyDb(run: BackupRun) {
        run.currentPhase = "DB"
        run.logDao.updatePhase(run.logId, "DB")
        postNotification(run = run, text = "Backing up database\u2026")

        try {
            val sqliteDb = run.db.openHelper.writableDatabase
            sqliteDb.query("PRAGMA wal_checkpoint(FULL)").close()
            if (run.verbose) run.info("WAL checkpoint complete")

            val dbSourceFile = applicationContext.getDatabasePath("treecast.db")
            val dbDestDir    = run.destRoot.findOrCreateDir("db")

            if (dbDestDir == null) {
                run.warning("Could not create db/ on backup destination — database snapshot skipped")
                return
            }

            if (run.verbose) run.info("db/ directory resolved on backup destination")

            val stamp    = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val destName = "treecast_${stamp}.db"
            copyFileToDocumentDir(dbSourceFile, dbDestDir, destName)
            run.dbBackedUp = true
            run.info("Database snapshot written: $destName")

        } catch (e: Exception) {
            run.warning("Database backup failed: ${e.message}")
        }
    }

    // ── Step 4: Copy recording audio files ───────────────────────────────────

    /**
     * Syncs all `TC_*.m4a` recording files from every mounted source volume into
     * the `recordings/YYYY/MM/` tree on the backup destination.
     *
     * Three outcomes per file:
     *  - **Skip**: already present in the correct YYYY/MM dir with a matching size.
     *  - **Reorganise**: present on the destination but in the wrong location
     *    (e.g. flat root from an old backup run) — re-copied to the correct dir
     *    and the stale copy deleted after verification.
     *  - **Copy**: not present, or present with a size mismatch.
     *
     * Also populates [BackupRun.recordingDestDir], [BackupRun.dirCache], and
     * [BackupRun.jsonIndex] for use by [stepExportMetadata].
     */
    private suspend fun stepCopyRecordings(run: BackupRun) {
        val recordingDestDir = run.destRoot.findOrCreateDir("recordings")
        if (recordingDestDir == null) {
            run.warning("Could not create recordings/ on backup destination — recording sync skipped")
            return
        }
        run.recordingDestDir = recordingDestDir

        // ── Build destination index ───────────────────────────────────────────
        //
        // Keyed by filename → DestEntry, which carries the file's size and its
        // parent DocumentFile so we can detect misplacement and delete the stale
        // copy after a successful reorganisation.

        data class DestEntry(
            val size      : Long,
            val parentDir : DocumentFile,
            val file      : DocumentFile,
        )

        val destIndex = mutableMapOf<String, DestEntry>()

        // Returns a pair of (m4aCount, jsonCount) for the files indexed from [dir].
        fun indexDir(dir: DocumentFile): Pair<Int, Int> {
            var m4a = 0; var json = 0
            dir.listFiles().forEach { child ->
                val name = child.name.orEmpty()
                if (child.isFile && name.startsWith("TC_")) {
                    when {
                        name.endsWith(".m4a")  -> {
                            destIndex[name] = DestEntry(
                                size      = child.length(),
                                parentDir = dir,
                                file      = child,
                            )
                            m4a++
                        }
                        name.endsWith(".json") -> {
                            run.jsonIndex[name.removeSuffix(".json")] = child
                            json++
                        }
                    }
                }
            }
            return m4a to json
        }

        // Flat root — catches files written by old backup runs (legacy or errors).
        if (run.verbose) run.info("Destination index: scanning flat recordings/ root…")
        val (flatM4a, flatJson) = indexDir(recordingDestDir)
        if (run.verbose && (flatM4a > 0 || flatJson > 0)) {
            run.info(
                "Destination index: flat root has $flatM4a misplaced recording(s) " +
                        "and $flatJson metadata file(s) — these will be reorganised"
            )
        }

        // YYYY/MM subdirectories — the canonical layout.
        if (run.verbose) run.info("Destination index: scanning YYYY/MM subdirectories…")
        var nestedDirCount = 0
        recordingDestDir.listFiles().forEach { yearDir ->
            val yearName = yearDir.name.orEmpty()
            if (yearDir.isDirectory && yearName.matches(Regex("\\d{4}"))) {
                yearDir.listFiles().forEach { monthDir ->
                    val monthName = monthDir.name.orEmpty()
                    if (monthDir.isDirectory && monthName.matches(Regex("\\d{2}"))) {
                        run.dirCache["$yearName/$monthName"] = monthDir
                        indexDir(monthDir)
                        nestedDirCount++
                    }
                }
            }
        }
        if (run.verbose) {
            run.info(
                "Destination index complete — ${destIndex.size} recording(s) and " +
                        "${run.jsonIndex.size} metadata file(s) across $nestedDirCount YYYY/MM dir(s)" +
                        if (flatM4a > 0) ", plus $flatM4a flat-root file(s) pending reorganisation" else ""
            )
        }

        // ── Compute source totals ─────────────────────────────────────────────
        val sourceDirs = recordingSourceDirs()

        run.totalOnSource = sourceDirs.sumOf { dir ->
            dir.listFiles { f -> f.name.startsWith("TC_") && f.extension == "m4a" }?.size ?: 0
        }

        val walkFailures = mutableListOf<Pair<String, String>>()
        val allSourceFiles: List<File> = sourceDirs.flatMap { dir ->
            dir.walkTopDown()
                .onFail { file, ex ->
                    walkFailures += file.absolutePath to (ex.message ?: "Unreadable")
                }
                .filter { it.isFile && it.name.startsWith("TC_") }
                .toList()
        }
        walkFailures.forEach { (path, message) ->
            run.warning("Could not read $path: $message", path)
        }
        val totalBytesOnSource: Long = allSourceFiles.sumOf { it.length() }
        run.totalBytesOnSource = totalBytesOnSource

        // ── Signal phase start ────────────────────────────────────────────────
        run.currentPhase = "RECORDINGS"
        run.logDao.updatePhase(run.logId, "RECORDINGS")
        postNotification(run = run, text = "Copying files\u2026")
        // totalBytesOnSource is written on the first updateRecordingProgress()
        // call inside the loop — no separate write needed here.

        // ── Copy loop ─────────────────────────────────────────────────────────
        for (sourceDir in sourceDirs) {
            val sourceFiles = sourceDir
                .walkTopDown()
                .filter { it.isFile && it.name.startsWith("TC_") && it.extension == "m4a" }
                .toList()

            for (file in sourceFiles) {
                run.filesExamined++

                // Derive YYYY/MM from the TC_ filename stem.
                // TC_ filenames are TC_yyyyMMdd_HHmmss.m4a — year is chars 0–3, month 4–5.
                val stem    = file.nameWithoutExtension.removePrefix("TC_")
                val yyyy    = stem.take(4)
                val mm      = stem.drop(4).take(2)
                val validYM = yyyy.matches(Regex("\\d{4}")) && mm.matches(Regex("\\d{2}"))

                val targetDir: DocumentFile? = if (validYM) {
                    run.dirCache["$yyyy/$mm"]
                        ?: recordingDestDir.findOrCreateDir(yyyy)
                            ?.findOrCreateDir(mm)
                            ?.also { run.dirCache["$yyyy/$mm"] = it }
                } else null

                if (targetDir == null) {
                    run.recordingsFailed++
                    run.error(
                        message = "Could not resolve destination directory for ${file.name}",
                        path    = file.absolutePath,
                    )
                    continue
                }

                val existing = destIndex[file.name]

                when {
                    existing != null
                            && existing.parentDir.uri == targetDir.uri
                            && existing.size == file.length() -> {
                        // Already present in the correct place with matching size — skip.
                        run.recordingsSkipped++
                        if (run.verbose) {
                            run.info("Skipped ${file.name} — already up to date", path = file.absolutePath)
                        }
                    }

                    existing != null
                            && existing.parentDir.uri != targetDir.uri
                            && existing.size == file.length() -> {
                        // Present on destination but in the wrong location (e.g. flat root
                        // from an old backup run). Re-copy to the correct YYYY/MM dir.
                        // We copy from the source rather than dest-to-dest because SAF
                        // has no rename/move primitive.
                        try {
                            copyFileToDocumentDir(file, targetDir, file.name)
                            val newCopy = targetDir.findFile(file.name)
                            if (newCopy != null && newCopy.length() == file.length()) {
                                existing.file.delete()
                                run.info(
                                    message = "Reorganised ${file.name} → $yyyy/$mm/",
                                    path    = file.absolutePath,
                                )
                                run.recordingsCopied++
                                run.bytesCopied += file.length()
                            } else {
                                // Verification failed — leave the old copy in place.
                                newCopy?.delete()
                                run.recordingsFailed++
                                run.error(
                                    message = "Size mismatch after reorganising ${file.name}; original preserved",
                                    path    = file.absolutePath,
                                )
                            }
                        } catch (e: Exception) {
                            run.recordingsFailed++
                            run.error(
                                message = "Failed to reorganise ${file.name}: ${e.message}",
                                path    = file.absolutePath,
                            )
                        }
                    }

                    else -> {
                        // Not on destination, or present with a size mismatch — copy fresh.
                        try {
                            copyFileToDocumentDir(file, targetDir, file.name)
                            run.recordingsCopied++
                            run.bytesCopied += file.length()
                            val mb = "%.1f MB".format(file.length() / 1_048_576.0)
                            run.info("Copied ${file.name} ($mb)", path = file.absolutePath)
                        } catch (e: Exception) {
                            run.recordingsFailed++
                            run.error(
                                message = e.message ?: "Unknown error",
                                path    = file.absolutePath,
                            )
                        }
                    }
                }

                run.logDao.updateRecordingProgress(
                    id                 = run.logId,
                    bytesCopied        = run.bytesCopied,
                    filesExamined      = run.filesExamined,
                    copied             = run.recordingsCopied,
                    skipped            = run.recordingsSkipped,
                    failed             = run.recordingsFailed,
                    totalBytesOnSource = totalBytesOnSource,
                )
                postNotification(run = run, text = "Copying files\u2026")
            }

        }

        // ── Destination totals (post-run) ─────────────────────────────────────
        //
        // Re-walk YYYY/MM subdirs only — flat root files are legacy/errors and
        // should not count toward the "successfully backed-up" total.
        recordingDestDir.listFiles().forEach { yearDir ->
            val yearName = yearDir.name.orEmpty()
            if (yearDir.isDirectory && yearName.matches(Regex("\\d{4}"))) {
                yearDir.listFiles().forEach { monthDir ->
                    val monthName = monthDir.name.orEmpty()
                    if (monthDir.isDirectory && monthName.matches(Regex("\\d{2}"))) {
                        monthDir.listFiles().forEach { f ->
                            if (f.isFile && f.name.orEmpty().startsWith("TC_")) {
                                run.totalOnDest++
                                run.totalBytesOnDest += f.length()
                            }
                        }
                    }
                }
            }
        }

        // Always log the recording copy pass summary.
        val copiedMb = "%.1f MB".format(run.bytesCopied / 1_048_576.0)
        run.info(
            "Recordings pass complete — ${run.recordingsCopied} copied ($copiedMb), " +
                    "${run.recordingsSkipped} skipped, ${run.recordingsFailed} failed"
        )

        // Verbose: destination state after the run.
        if (run.verbose) {
            val destMb = "%.1f MB".format(run.totalBytesOnDest / 1_048_576.0)
            run.info(
                "Destination state: ${run.totalOnDest} recording(s) on backup ($destMb)"
            )
        }
    }

    // ── Step 5: Metadata JSON export ──────────────────────────────────────────

    /**
     * Generates or refreshes a companion `.json` metadata sidecar alongside
     * each `.m4a` on the backup destination. A sidecar is skipped when the
     * destination already has a copy whose `exportedAt` timestamp is at least as
     * recent as the recording's `metadataUpdatedAt` (and its topic's `updatedAt`).
     *
     * Only called when [BackupTargetEntity.exportMetadataEnabled] is true.
     * Requires [stepCopyRecordings] to have run first (provides [BackupRun.dirCache]
     * and [BackupRun.jsonIndex]).
     */
    private suspend fun stepExportMetadata(run: BackupRun) {
        val recordingDestDir = run.recordingDestDir
            ?: return   // recording step was skipped — nothing to annotate

        val recordingDao = run.db.recordingDao()
        val topicDao     = run.db.topicDao()
        val markDao      = run.db.markDao()

        val allRecordings = recordingDao.getAllOnce()
        val allTopics     = topicDao.getAllTopicsOnce()

        run.totalMetadataFiles = allRecordings.size
        run.currentPhase = "METADATA"
        run.logDao.updatePhase(run.logId, "METADATA")
        postNotification(run = run, text = "Exporting metadata\u2026")

        for (recording in allRecordings) {
            val audioFile = File(recording.filePath)
            val stem      = audioFile.nameWithoutExtension   // e.g. "TC_20240115_143022"

            val rawStem = stem.removePrefix("TC_")
            val yyyy    = rawStem.take(4)
            val mm      = rawStem.drop(4).take(2)
            if (!yyyy.matches(Regex("\\d{4}")) || !mm.matches(Regex("\\d{2}"))) continue

            val topic = recording.topicId?.let { id -> allTopics.find { it.id == id } }
            val freshnessThreshold = maxOf(
                recording.metadataUpdatedAt,
                topic?.updatedAt ?: 0L,
            )

            // O(1) map reads — no SAF calls on the fast skip path.
            val existingJson   = run.jsonIndex[stem]
            val destExportedAt = existingJson?.let {
                try { readExportedAt(it) } catch (_: Exception) { 0L }
            } ?: 0L

            if (destExportedAt >= freshnessThreshold) {
                run.metadataSkipped++
                if (run.verbose) {
                    run.info("Skipped metadata for $stem — destination is up to date", path = recording.filePath)
                }
                continue
            }

            // Resolve target dir from cache; create and cache if this YYYY/MM is
            // new (i.e. a recording month not yet present on the destination at all).
            val targetDir = run.dirCache["$yyyy/$mm"]
                ?: recordingDestDir.findOrCreateDir(yyyy)
                    ?.findOrCreateDir(mm)
                    ?.also { run.dirCache["$yyyy/$mm"] = it }
                ?: continue

            try {
                val marks         = markDao.getMarksForRecordingOnce(recording.id)
                val localJsonFile = RecordingExporter.export(recording, marks, allTopics)
                copyFileToDocumentDir(localJsonFile, targetDir, "$stem.json")
                run.metadataGenerated++
                run.info("Generated metadata for $stem", path = recording.filePath)
            } catch (e: Exception) {
                run.metadataFailed++
                run.warning(
                    message = "Metadata export failed for $stem: ${e.message}",
                    path    = recording.filePath,
                )
            }

            run.logDao.updateMetadataProgress(
                id         = run.logId,
                generated  = run.metadataGenerated,
                skipped    = run.metadataSkipped,
                failed     = run.metadataFailed,
                totalFiles = allRecordings.size,
            )
            postNotification(run = run, text = "Exporting metadata\u2026")
        }

        // Always log the metadata pass summary.
        run.info(
            "Metadata pass complete — ${run.metadataGenerated} generated, " +
                    "${run.metadataSkipped} skipped, ${run.metadataFailed} failed"
        )
    }

    // ── Step 5a: Sync waveform cache files ───────────────────────────────────

    /**
     * Copies `.wfm` waveform cache files from every mounted volume's
     * `appdata/waveforms/` directory into `appdata/waveforms/` on the backup
     * destination, preserving the `YYYY/MM/` subdirectory hierarchy.
     *
     * Failures are non-fatal — waveforms are re-computable by [WaveformWorker]
     * and must not degrade the run status from SUCCESS to PARTIAL.
     * [waveformsFailed] is therefore excluded from the [filesFailed] aggregate
     * in [stepFlushStats].
     *
     * **Multi-volume scanning**: iterates all mounted volumes via
     * `getExternalFilesDirs(null)`, matching the same multi-volume logic used by
     * [recordingSourceDirs], so recordings on secondary volumes (e.g. SD card)
     * are not silently skipped.
     *
     * **Directory structure**: cache files are stored under `YYYY/MM/` subdirs
     * derived from the recording's creation timestamp, mirroring the recordings/
     * layout and bounding per-directory entry counts for heavy users. During the
     * transition from the old flat layout, flat files may coexist with YYYY/MM
     * files; both are collected and backed up, preserving their relative paths so
     * the restore step can reconstruct whichever structure is present.
     */
    private suspend fun stepSyncWaveforms(run: BackupRun) {
        val waveformDestDir = run.destRoot
            .findOrCreateDir("appdata")
            ?.findOrCreateDir("waveforms")

        if (waveformDestDir == null) {
            run.warning("Could not create appdata/waveforms/ on backup destination — waveform sync skipped")
            return
        }

        // Collect waveform source directories from ALL mounted volumes.
        //
        // getExternalFilesDirs(null) returns the files/ root for each mounted
        // volume — the direct parent of appdata/waveforms/, exactly matching
        // WaveformCache's path resolution.
        val waveformSourceDirs = applicationContext
            .getExternalFilesDirs(null)
            .filterNotNull()
            .map { File(it, "appdata/waveforms") }
            .filter { it.exists() && it.isDirectory }

        // Walk each source volume's waveform dir, collecting .wfm files together
        // with their relative directory path ("YYYY/MM" or "" for flat legacy files).
        // Flat files produced by older builds before the YYYY/MM migration coexist
        // with structured files during the transition; both are backed up so the
        // restore step can reconstruct whichever layout is present.
        val wfmFiles: List<Pair<File, String>> = waveformSourceDirs
            .flatMap { dir ->
                dir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".wfm") }
                    .map { file ->
                        val mm   = file.parentFile?.name
                        val yyyy = file.parentFile?.parentFile?.name
                        val relDir = if (
                            yyyy != null && mm != null &&
                            yyyy.matches(Regex("\\d{4}")) && mm.matches(Regex("\\d{2}"))
                        ) "$yyyy/$mm" else ""
                        file to relDir
                    }
                    .toList()
            }
            // Recording IDs are unique per device, so the same filename cannot
            // legitimately appear on two volumes. Deduplicate as a safety guard.
            .distinctBy { it.first.name }

        run.info(
            "Waveform sync: ${wfmFiles.size} cache file(s) found across " +
                    "${waveformSourceDirs.size} volume(s)"
        )

        // Signal phase start here, immediately after wfmFiles is known so
        // totalFiles is available to the first progress write.
        run.totalWaveformFiles = wfmFiles.size
        run.currentPhase = "WAVEFORMS"
        run.logDao.updatePhase(run.logId, "WAVEFORMS")
        postNotification(run = run, text = "Syncing waveforms\u2026")

        // Per-subdirectory cache so we issue at most one findOrCreateDir() pair
        // per YYYY/MM combination rather than one per file.
        val wfmDirCache = mutableMapOf<String, DocumentFile>()

        for ((wfmFile, relDir) in wfmFiles) {

            // Resolve the destination subdirectory, creating it if necessary.
            val targetDir: DocumentFile? = if (relDir.isNotEmpty()) {
                val (yyyy, mm) = relDir.split("/")
                wfmDirCache[relDir]
                    ?: waveformDestDir.findOrCreateDir(yyyy)
                        ?.findOrCreateDir(mm)
                        ?.also { wfmDirCache[relDir] = it }
            } else {
                // Flat legacy file — copy directly into appdata/waveforms/ root.
                waveformDestDir
            }

            if (targetDir == null) {
                run.waveformsFailed++
                run.warning(
                    message = "Could not create waveform destination dir '$relDir' — skipping ${wfmFile.name}",
                    path    = wfmFile.absolutePath,
                )
                continue
            }

            // Skip if destination already has an identically-sized copy.
            // This is the normal steady-state outcome on most runs.
            val existingOnDest = targetDir.findFile(wfmFile.name)
            if (existingOnDest != null && existingOnDest.length() == wfmFile.length()) {
                run.waveformsSkipped++
                if (run.verbose) {
                    run.info("Skipped waveform ${wfmFile.name} — already up to date", path = wfmFile.absolutePath)
                }
                continue
            }

            // copyFileToDocumentDir deletes any stale destination copy before
            // writing, so a size-mismatch is handled cleanly.
            try {
                copyFileToDocumentDir(wfmFile, targetDir, wfmFile.name)
                run.waveformsCopied++
                if (run.verbose) {
                    run.info("Copied waveform ${wfmFile.name}", path = wfmFile.absolutePath)
                }
            } catch (e: Exception) {
                run.waveformsFailed++
                run.warning(
                    message = "Waveform copy failed for ${wfmFile.name}: ${e.message}",
                    path    = wfmFile.absolutePath,
                )
            }

            run.logDao.updateWaveformProgress(
                id         = run.logId,
                copied     = run.waveformsCopied,
                skipped    = run.waveformsSkipped,
                failed     = run.waveformsFailed,
                totalFiles = wfmFiles.size,
            )
            postNotification(run = run, text = "Syncing waveforms\u2026")
        }

        // Always log the waveform pass summary.
        run.info(
            "Waveforms pass complete — ${run.waveformsCopied} copied, " +
                    "${run.waveformsSkipped} skipped, ${run.waveformsFailed} failed"
        )
    }

    // ── Step 6: Flush final statistics ───────────────────────────────────────

    /**
     * Derives aggregate file counters from per-category counters and writes
     * the complete stats to the log row.
     *
     * [waveformsFailed] is intentionally excluded from [filesFailed] — waveform
     * failures are non-fatal and must not influence the run status.
     */
    private suspend fun stepFlushStats(run: BackupRun) {
        run.filesCopied  = run.recordingsCopied  + run.metadataGenerated + run.waveformsCopied
        run.filesSkipped = run.recordingsSkipped + run.metadataSkipped   + run.waveformsSkipped
        run.filesFailed  = run.recordingsFailed  + run.metadataFailed

        run.logDao.updateStats(
            id                = run.logId,
            filesExamined     = run.filesExamined,
            filesCopied       = run.filesCopied,
            filesSkipped      = run.filesSkipped,
            filesFailed       = run.filesFailed,
            bytesCopied       = run.bytesCopied,
            totalOnSource     = run.totalOnSource,
            totalOnDest       = run.totalOnDest,
            totalBytesOnDest  = run.totalBytesOnDest,
            dbBackedUp        = run.dbBackedUp,
            recordingsCopied  = run.recordingsCopied,
            recordingsSkipped = run.recordingsSkipped,
            recordingsFailed  = run.recordingsFailed,
            metadataGenerated = run.metadataGenerated,
            metadataSkipped   = run.metadataSkipped,
            metadataFailed    = run.metadataFailed,
            waveformsCopied   = run.waveformsCopied,
            waveformsSkipped  = run.waveformsSkipped,
            waveformsFailed   = run.waveformsFailed,
        )
    }

    // ── Steps 7–9: Finalise log, stamp target, post notification ─────────────

    /**
     * Determines the run status, writes the terminal fields to the log row,
     * stamps the backup target's `lastBackupAt`, and posts the final notification.
     * Returns the [Result] to propagate to WorkManager.
     *
     * Status rules:
     *  - **FAILED**: files were attempted but nothing succeeded and the DB
     *    wasn't backed up either. Hard failure.
     *  - **PARTIAL**: some files failed, or warnings/errors were logged.
     *  - **SUCCESS**: everything went cleanly.
     */
    private suspend fun stepFinaliseAndNotify(
        run       : BackupRun,
        targetDao : BackupTargetDao,
    ): Result {
        val status = when {
            run.filesFailed > 0 && run.filesCopied == 0 && !run.dbBackedUp -> BackupStatus.FAILED
            run.filesFailed > 0 || run.problemCount > 0                     -> BackupStatus.PARTIAL
            else                                                             -> BackupStatus.SUCCESS
        }

        run.logDao.finalise(
            id           = run.logId,
            endedAt      = System.currentTimeMillis(),
            status       = status,
            errorMessage = if (status == BackupStatus.FAILED)
                "Backup failed — ${run.problemCount} error(s). See backup log for details."
            else null,
        )

        // Stamp the target even on PARTIAL so the UI shows "last attempted" time.
        if (status != BackupStatus.FAILED) {
            targetDao.setLastBackupAt(run.volumeUuid, System.currentTimeMillis())
        }
        // Keep the cached label fresh so Settings can show a name when disconnected.
        // If the volume wasn't in the system list and fell back to the UUID, we still
        // write it — it's no worse than what was stored before.
        targetDao.setVolumeLabel(run.volumeUuid, run.volumeLabel)

        val notifText = when (status) {
            BackupStatus.SUCCESS -> "Backup complete — ${run.filesCopied} file(s) copied"
            BackupStatus.PARTIAL -> "Backup finished with ${run.filesFailed} error(s)"
            else                 -> "Backup failed"
        }
        postNotification(run = run, text = notifText, ongoing = false)

        return if (status == BackupStatus.FAILED) Result.failure() else Result.success()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Returns all directories on all currently mounted volumes that may
     * contain TC_*.m4a recording files.
     */
    private fun recordingSourceDirs(): List<File> =
        applicationContext
            .getExternalFilesDirs(null)
            .filterNotNull()
            .map { File(it, "recordings") }
            .filter { it.exists() && it.isDirectory }

    /**
     * Finds a sub-directory with [name] inside [parent], creating it if it does
     * not exist. Returns null if creation fails.
     */
    private fun DocumentFile.findOrCreateDir(name: String): DocumentFile? =
        findFile(name)?.takeIf { it.isDirectory }
            ?: createDirectory(name)

    /**
     * Copies [source] into [destDir] using the SAF ContentResolver stream.
     * If a file with the same name already exists in [destDir] it is deleted
     * first to ensure a clean overwrite (covers the "same name, different size"
     * case where the destination copy is incomplete or corrupted).
     *
     * @throws IOException if the destination file cannot be created or the
     * copy stream fails.
     */
    private fun copyFileToDocumentDir(
        source  : File,
        destDir : DocumentFile,
        destName: String,
    ) {
        // Remove stale/partial destination copy if present.
        destDir.findFile(destName)?.delete()

        val destFile = destDir.createFile("application/octet-stream", destName)
            ?: throw IOException("Could not create destination file: $destName")

        applicationContext.contentResolver.openOutputStream(destFile.uri)?.use { out ->
            source.inputStream().use { inp -> inp.copyTo(out) }
        } ?: throw IOException("Could not open output stream for: $destName")
    }

    /**
     * Reads the `exportedAt` ISO-8601 string from a JSON metadata sidecar on
     * the SAF destination and converts it to epoch milliseconds.
     *
     * Returns 0L on any failure (missing field, malformed instant, I/O error)
     * so the caller treats the file as stale and triggers a re-export.
     */
    private fun readExportedAt(jsonFile: DocumentFile): Long {
        val text = applicationContext.contentResolver
            .openInputStream(jsonFile.uri)
            ?.use { it.bufferedReader().readText() }
            ?: return 0L
        val isoStr = JSONObject(text).optString("exportedAt", "")
        if (isoStr.isEmpty()) return 0L
        return Instant.parse(isoStr).toEpochMilli()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun postNotification(
        run     : BackupRun? = null,
        text    : String,
        ongoing : Boolean = true,
        // For the terminal (non-ongoing) notification, run is null and these
        // carry the display strings.  When run is non-null they are ignored.
        volumeUuid  : String = run?.volumeUuid  ?: "",
        volumeLabel : String = run?.volumeLabel ?: "",
    ) {
        val context = applicationContext
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(
                AppNotifications.CHANNEL_BACKUP,
                "Backup",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "TreeCast automatic backup progress" }
        )

        val resolvedUuid  = run?.volumeUuid  ?: volumeUuid
        val resolvedLabel = run?.volumeLabel ?: volumeLabel

        // Deep-link PendingIntent: opens MainActivity and navigates to Settings → Storage tab.
        val deepLinkIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NAVIGATE_TO_SETTINGS, true)
            putExtra(MainActivity.EXTRA_NAVIGATE_TO_STORAGE_TAB, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifIdForVolume(resolvedUuid),
            deepLinkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, AppNotifications.CHANNEL_BACKUP)
            .setContentTitle(if (ongoing) "Backing up to $resolvedLabel" else "TreeCast Backup")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_save_check_wave)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)

        // Attach progress bar when running.
        if (ongoing) {
            val fraction = run?.let {
                BackupProgressCalc.fraction(
                    currentPhase       = it.currentPhase,
                    bytesCopied        = it.bytesCopied,
                    totalBytesOnSource = it.totalBytesOnSource,
                    metadataDone       = it.metadataGenerated + it.metadataSkipped + it.metadataFailed,
                    totalMetadataFiles = it.totalMetadataFiles,
                    waveformsDone      = it.waveformsCopied + it.waveformsSkipped + it.waveformsFailed,
                    totalWaveformFiles = it.totalWaveformFiles,
                )
            }
            if (fraction != null) {
                builder.setProgress(
                    BackupProgressCalc.PROGRESS_MAX,
                    BackupProgressCalc.toProgress(fraction),
                    false,
                )
            } else {
                builder.setProgress(0, 0, true)
            }
        }

        nm.notify(notifIdForVolume(resolvedUuid), builder.build())
    }
}