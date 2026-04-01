package com.treecast.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.treecast.app.R
import com.treecast.app.data.db.AppDatabase
import com.treecast.app.data.entities.BackupLogEntity
import com.treecast.app.data.entities.BackupLogEntity.BackupStatus
import com.treecast.app.data.entities.BackupLogEntity.BackupTrigger
import com.treecast.app.data.entities.BackupLogEventEntity
import com.treecast.app.data.entities.BackupLogEventEntity.EventType
import com.treecast.app.ui.MainActivity
import com.treecast.app.util.StorageVolumeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that performs a full backup to a user-designated
 * SAF directory on a removable storage volume.
 *
 * ## What it does
 * 1. Resolves the [BackupTargetEntity] for the given [KEY_VOLUME_UUID].
 * 2. Opens the user-chosen SAF tree URI ([BackupTargetEntity.backupDirUri]).
 * 3. Checkpoints the Room WAL so the main .db file is fully up to date.
 * 4. Copies `treecast.db` into a `db/` sub-directory on the destination.
 * 5. Syncs all TC_*.m4a recording files from every source volume into a
 *    `recordings/` sub-directory, skipping files already present with a
 *    matching byte size.
 * 6. Writes a [BackupLogEntity] row (created at start, finalised at end)
 *    and [BackupLogEventEntity] child rows for any events (INFO milestones
 *    when verbose logging is on; WARNING and ERROR for problems always).
 * 7. Updates [BackupTargetEntity.lastBackupAt] on success.
 * 8. Posts a notification with the outcome.
 *
 * ## Verbose logging
 * When the user enables "Detailed backup log" in Settings, [PREF_VERBOSE_LOGGING]
 * is true and INFO milestone events are emitted alongside the usual WARNING/ERROR
 * events. Milestones include: WAL checkpoint result, db/ directory resolution,
 * database copy, recordings/ directory resolution, and pre-scan statistics.
 * Individual file copies are never logged regardless of verbosity level.
 *
 * ## Enqueuing
 * Use the [enqueueOneTime] helper for ON_CONNECT and MANUAL triggers.
 * Use [enqueueOrUpdatePeriodic] for SCHEDULED triggers — it creates or
 * replaces the periodic request for a given volume.
 * Use [cancelPeriodic] when [BackupTargetEntity.scheduledEnabled] is toggled off.
 *
 * Both one-time and periodic requests are tagged with [TAG] and a
 * per-volume tag ([TAG_VOLUME_PREFIX] + uuid) for easy observation and
 * cancellation.
 */
class BackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {

        /** WorkManager tag applied to every backup job — use for group observation. */
        const val TAG = "backup"

        /** Per-volume tag prefix. Format: "bvol:<uuid>" */
        const val TAG_VOLUME_PREFIX = "bvol:"

        const val KEY_VOLUME_UUID = "volume_uuid"
        const val KEY_TRIGGER     = "trigger"

        /**
         * SharedPreferences key for the verbose backup logging toggle.
         * When true, [EventType.INFO] milestone events are written to
         * [BackupLogEventEntity] during each run.
         * Default: false.
         */
        const val PREF_VERBOSE_LOGGING = "backup_verbose_logging"

        // ── Notification ──────────────────────────────────────────────
        const val CHANNEL_ID      = "treecast_backup"
        /**
         * Returns a stable per-volume notification ID.
         *
         * Uses a bounded hash of [volumeUuid] offset above the legacy ID (2001).
         * Range 2002–6097 gives 4 096 slots — far more than any real deployment
         * will need. The old NOTIFICATION_ID = 2001 is intentionally vacated so
         * concurrent jobs on different volumes no longer overwrite each other.
         */
        fun notifIdForVolume(volumeUuid: String): Int =
            2002 + (volumeUuid.hashCode() and 0x0FFF)

        // ── Unique work name helpers ──────────────────────────────────

        /** Stable name for the one-time work request for a volume. */
        private fun oneTimeName(volumeUuid: String) = "backup_once_$volumeUuid"

        /** Stable name for the periodic work request for a volume. */
        private fun periodicName(volumeUuid: String) = "backup_periodic_$volumeUuid"

        // ── Enqueue helpers ───────────────────────────────────────────

        /**
         * Enqueues a one-time backup for [volumeUuid].
         * Uses [ExistingWorkPolicy.KEEP] — if a job for this volume is
         * already ENQUEUED or RUNNING, this call is a no-op.
         */
        fun enqueueOneTime(
            context: Context,
            volumeUuid: String,
            trigger: String,
        ) {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setInputData(
                    workDataOf(
                        KEY_VOLUME_UUID to volumeUuid,
                        KEY_TRIGGER     to trigger,
                    )
                )
                .addTag(TAG)
                .addTag("$TAG_VOLUME_PREFIX$volumeUuid")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(oneTimeName(volumeUuid), ExistingWorkPolicy.KEEP, request)
        }

        /**
         * Enqueues or replaces the periodic backup request for [volumeUuid].
         * Replaces any existing periodic request so interval changes take
         * effect immediately.
         */
        fun enqueueOrUpdatePeriodic(
            context: Context,
            volumeUuid: String,
            intervalHours: Long,
        ) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(intervalHours, TimeUnit.HOURS)
                .setInputData(
                    workDataOf(
                        KEY_VOLUME_UUID to volumeUuid,
                        KEY_TRIGGER     to BackupTrigger.SCHEDULED,
                    )
                )
                .addTag(TAG)
                .addTag("$TAG_VOLUME_PREFIX$volumeUuid")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    periodicName(volumeUuid),
                    ExistingPeriodicWorkPolicy.REPLACE,
                    request,
                )
        }

        /**
         * Cancels the periodic backup request for [volumeUuid].
         * Called when [BackupTargetEntity.scheduledEnabled] is toggled off.
         * One-time (on-connect / manual) jobs are unaffected.
         */
        fun cancelPeriodic(context: Context, volumeUuid: String) {
            WorkManager.getInstance(context).cancelUniqueWork(periodicName(volumeUuid))
        }
    }

    // ── Worker entry point ────────────────────────────────────────────────────

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val volumeUuid = inputData.getString(KEY_VOLUME_UUID)
            ?: return@withContext Result.failure()
        val trigger = inputData.getString(KEY_TRIGGER)
            ?: BackupTrigger.MANUAL

        val db        = AppDatabase.getInstance(applicationContext)
        val targetDao = db.backupTargetDao()
        val logDao    = db.backupLogDao()

        val verbose = applicationContext
            .getSharedPreferences("treecast_settings", Context.MODE_PRIVATE)
            .getBoolean(PREF_VERBOSE_LOGGING, false)

        // ── 1. Resolve target ─────────────────────────────────────────
        val target = targetDao.getByUuid(volumeUuid)
            ?: return@withContext Result.failure()

        val dirUriString = target.backupDirUri
            ?: return@withContext Result.failure()   // no directory chosen yet

        val destRoot = DocumentFile.fromTreeUri(
            applicationContext,
            Uri.parse(dirUriString),
        ) ?: return@withContext Result.failure()

        if (!destRoot.exists() || !destRoot.isDirectory) {
            return@withContext Result.failure()
        }

        // Resolve a label for the volume for denormalization into the log.
        val volumeLabel = StorageVolumeHelper
            .getVolumeByUuid(applicationContext, volumeUuid)
            ?.label
            ?: volumeUuid

        // ── 2. Open log row ───────────────────────────────────────────
        val logId = logDao.insert(
            BackupLogEntity(
                backupTargetUuid = volumeUuid,
                volumeUuid       = volumeUuid,
                volumeLabel      = volumeLabel,
                backupDirUri     = dirUriString,
                trigger          = trigger,
            )
        )

        postNotification(
            context      = applicationContext,
            volumeUuid   = volumeUuid,
            volumeLabel  = volumeLabel,
            text         = "Backup in progress\u2026",
        )

        // Accumulated events — flushed to DB in a single batch at the end.
        val events = mutableListOf<BackupLogEventEntity>()

        // Convenience helpers — keep call sites concise.
        suspend fun info(message: String, path: String? = null) {
            val event = BackupLogEventEntity(
                logId      = logId,
                severity   = EventType.INFO,
                sourcePath = path,
                message    = message,
            )
            logDao.insertEvent(event)
        }
        suspend fun warning(message: String, path: String? = null) {
            val event = BackupLogEventEntity(
                logId      = logId,
                severity   = EventType.WARNING,
                sourcePath = path,
                message    = message,
            )
            logDao.insertEvent(event)
            events.clear()
        }
        suspend fun error(message: String, path: String? = null) {
            val event = BackupLogEventEntity(
                logId      = logId,
                severity   = EventType.ERROR,
                sourcePath = path,
                message    = message,
            )
            logDao.insertEvent(event)
            events.clear()
        }


        // Running stats — updated incrementally and flushed to the DB at the end.
        var filesExamined = 0
        var filesCopied   = 0
        var filesSkipped  = 0
        var filesFailed   = 0
        var bytesCopied   = 0L
        var dbBackedUp    = false

        // ── 3. Checkpoint WAL then copy DB ────────────────────────────
        try {
            val sqliteDb = db.openHelper.writableDatabase
            sqliteDb.query("PRAGMA wal_checkpoint(FULL)").close()
            info("WAL checkpoint complete")

            val dbSourceFile = applicationContext.getDatabasePath("treecast.db")
            val dbDestDir    = destRoot.findOrCreateDir("db")

            if (dbDestDir != null) {
                info("db/ directory resolved on backup destination")
                copyFileToDocumentDir(dbSourceFile, dbDestDir, "treecast.db")
                dbBackedUp = true
                info("Database copied — ${dbSourceFile.length()} bytes")
            } else {
                error("Could not create db/ directory on backup destination")
            }
        } catch (e: Exception) {
            error("Database checkpoint/copy failed: ${e.message}")
        }

        // ── 4. Sync recording files ───────────────────────────────────
        val recordingDestDir = destRoot.findOrCreateDir("recordings")

        if (recordingDestDir == null) {
            error("Could not create recordings/ directory on backup destination")
        } else {
            info("recordings/ directory resolved on backup destination")

            // Build an index of files already on the destination keyed by name,
            // so existence checks are O(1) rather than O(n) per source file.
            val destIndex: Map<String, Long> = recordingDestDir
                .listFiles()
                .associate { it.name.orEmpty() to it.length() }

            val sourceDirs = recordingSourceDirs()
            val totalOnSource = sourceDirs.sumOf { dir ->
                dir.listFiles { f -> f.name.startsWith("TC_") && f.extension == "m4a" }
                    ?.size ?: 0
            }
            val alreadyOnDest = destIndex.count { (name, _) -> name.startsWith("TC_") }
            info(
                "Pre-scan complete — $totalOnSource recording(s) on source, " +
                        "$alreadyOnDest already on destination"
            )

            // ── Pre-scan: walk all source dirs recursively to compute total bytes ──────
            // Done before the copy loop so the notification and progress bar have a
            // denominator from the first file. Uses File.walkTopDown() so nested
            // subdirectories are automatically included if the directory structure
            // ever becomes more complex than a flat recordings/ folder.
            val walkFailures = mutableListOf<Pair<String, String>>()  // path → message
            val allSourceFiles: List<File> = recordingSourceDirs()
                .flatMap { dir ->
                    dir.walkTopDown()
                        .onFail { file, ex ->
                            walkFailures += file.absolutePath to (ex.message ?: "Unreadable")
                        }
                        .filter { it.isFile && it.name.startsWith("TC_") }
                        .toList()
                }
            // Flush any walk errors now that we're back in a suspending context.
            walkFailures.forEach { (path, message) ->
                warning("Could not read $path: $message", path)
            }
            val totalBytesOnSource: Long = allSourceFiles.sumOf { it.length() }

            for (sourceDir in sourceDirs) {
                val allSourceFiles = sourceDir
                    .walkTopDown()
                    .filter { it.isFile && it.name.startsWith("TC_") && it.extension == "m4a" }
                    .toList()

                for (file in allSourceFiles) {
                    filesExamined++
                    val destSize = destIndex[file.name]

                    when {
                        destSize == file.length() -> {
                            // Already present and same size — skip.
                            filesSkipped++
                        }
                        else -> {
                            // New or changed — copy.
                            try {
                                copyFileToDocumentDir(file, recordingDestDir, file.name)
                                filesCopied++
                                bytesCopied += file.length()
                            } catch (e: Exception) {
                                filesFailed++
                                error(
                                    message = e.message ?: "Unknown error",
                                    path    = file.absolutePath,
                                )
                            }
                        }
                    }

                    // Flush stats to DB every 5 files so the UI can show live progress.
                    //if (filesExamined % 5 == 0) {
                    logDao.updateStats(
                        id               = logId,
                        filesExamined    = filesExamined,
                        filesCopied      = filesCopied,
                        filesSkipped     = filesSkipped,
                        filesFailed      = filesFailed,
                        bytesCopied      = bytesCopied,
                        totalOnSource    = totalOnSource,
                        totalOnDest      = 0,
                        totalBytesOnDest = 0L,
                        dbBackedUp       = dbBackedUp,
                    )
                    //}
                    // Update the notification progress bar every 10 files.
                    //if (filesExamined % 10 == 0) {
                        postNotification(
                            context          = applicationContext,
                            volumeUuid       = volumeUuid,
                            volumeLabel      = volumeLabel,
                            text             = "Copying files…",
                            ongoing          = true,
                            bytesCopied      = bytesCopied,
                            totalBytes       = totalBytesOnSource,
                        )
                    //}
                }
            }

            // Total state of destination after this run.
            val destFiles        = recordingDestDir.listFiles()
            val totalOnDest      = destFiles.count { it.name.orEmpty().startsWith("TC_") }
            val totalBytesOnDest = destFiles
                .filter { it.name.orEmpty().startsWith("TC_") }
                .sumOf { it.length() }

            // ── 5. Flush stats to log row ─────────────────────────────
            logDao.updateStats(
                id               = logId,
                filesExamined    = filesExamined,
                filesCopied      = filesCopied,
                filesSkipped     = filesSkipped,
                filesFailed      = filesFailed,
                bytesCopied      = bytesCopied,
                totalOnSource    = totalOnSource,
                totalOnDest      = totalOnDest,
                totalBytesOnDest = totalBytesOnDest,
                dbBackedUp       = dbBackedUp,
            )
        }

        // ── 6. Write event child rows ─────────────────────────────────
//        if (events.isNotEmpty()) {
//            logDao.insertEvents(events)
//        }

        // ── 7. Finalise log row ───────────────────────────────────────
        //
        // Problem count is WARNING + ERROR only — INFO milestones do not
        // count toward PARTIAL or FAILED status.
        val problemCount = events.count { it.severity != EventType.INFO }

        val status = when {
            filesFailed > 0 && filesCopied == 0 && !dbBackedUp -> BackupStatus.FAILED
            filesFailed > 0 || problemCount > 0                 -> BackupStatus.PARTIAL
            else                                                 -> BackupStatus.SUCCESS
        }

        logDao.finalise(
            id           = logId,
            endedAt      = System.currentTimeMillis(),
            status       = status,
            errorMessage = if (status == BackupStatus.FAILED)
                "Backup failed — $problemCount error(s). See backup log for details."
            else null,
        )

        // ── 8. Stamp target with last backup time ─────────────────────
        if (status != BackupStatus.FAILED) {
            targetDao.setLastBackupAt(volumeUuid, System.currentTimeMillis())
        }

        // ── 9. Resolve notification and WorkManager result ────────────
        val notifText = when (status) {
            BackupStatus.SUCCESS -> "Backup complete — $filesCopied file(s) copied"
            BackupStatus.PARTIAL -> "Backup finished with $filesFailed error(s)"
            else                 -> "Backup failed"
        }
        postNotification(
            context     = applicationContext,
            volumeUuid  = volumeUuid,
            volumeLabel = volumeLabel,
            text        = notifText,
            ongoing     = false,
        )

        if (status == BackupStatus.FAILED) Result.failure() else Result.success()
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
     * Finds a sub-directory with [name] inside [parent], creating it if
     * it does not exist. Returns null if creation fails.
     */
    private fun DocumentFile.findOrCreateDir(name: String): DocumentFile? =
        findFile(name)?.takeIf { it.isDirectory }
            ?: createDirectory(name)

    /**
     * Copies [source] into [destDir] using the SAF ContentResolver stream.
     * If a file with the same name already exists in [destDir] it is deleted
     * first to ensure a clean overwrite (covers the "same name, different
     * size" case where the destination copy is incomplete or corrupted).
     *
     * @throws IOException if the copy stream fails.
     */
    private fun copyFileToDocumentDir(
        source: File,
        destDir: DocumentFile,
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

    // ── Notification ──────────────────────────────────────────────────────────

    private fun postNotification(
        context: Context,
        volumeUuid: String,
        volumeLabel: String,
        text: String,
        ongoing: Boolean = true,
        bytesCopied: Long = 0L,
        totalBytes: Long = 0L,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Ensure the channel exists — safe to call repeatedly.
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Backup",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "TreeCast automatic backup progress" }
        )

        // Deep-link PendingIntent: opens MainActivity and navigates to Settings → Storage tab.
        val deepLinkIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NAVIGATE_TO_SETTINGS, true)
            putExtra(MainActivity.EXTRA_NAVIGATE_TO_STORAGE_TAB, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifIdForVolume(volumeUuid),   // unique request code per volume
            deepLinkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(if (ongoing) "Backing up to $volumeLabel" else "TreeCast Backup")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_save_check_wave)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)

        // Attach progress bar when running.
        if (ongoing) {
            if (totalBytes > 0 && bytesCopied >= 0) {
                val max      = 10_000
                val progress = ((bytesCopied.toFloat() / totalBytes) * max)
                    .toInt().coerceIn(0, max)
                builder.setProgress(max, progress, /* indeterminate= */ false)
            } else {
                builder.setProgress(0, 0, /* indeterminate= */ true)
            }
        }

        nm.notify(notifIdForVolume(volumeUuid), builder.build())
    }
}