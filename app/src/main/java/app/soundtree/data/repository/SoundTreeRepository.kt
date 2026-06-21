package app.soundtree.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import app.soundtree.data.dao.VolumeUsage
import app.soundtree.data.db.AppDatabase
import app.soundtree.data.entities.BackupLogEntity
import app.soundtree.data.entities.BackupLogEventEntity
import app.soundtree.data.entities.BackupTargetEntity
import app.soundtree.data.entities.MarkEntity
import app.soundtree.data.entities.RecordingEntity
import app.soundtree.data.entities.TopicEntity
import app.soundtree.util.Icons
import app.soundtree.util.RecordingStructureMigrator
import app.soundtree.storage.StorageVolumeHelper
import app.soundtree.topics.TopicScoringManager
import app.soundtree.worker.BackupWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SoundTreeRepository(context: Context) {

    private val db            = AppDatabase.getInstance(context)
    private val topicDao      = db.topicDao()
    private val recordingDao  = db.recordingDao()
    private val markDao       = db.markDao()
    private val backupTargetDao = db.backupTargetDao()
    private val backupLogDao    = db.backupLogDao()

    // Keep a context reference for WorkManager calls in backup target mutations.
    private val appContext = context.applicationContext

    suspend fun getTotalRecordingTime(): Long = recordingDao.getTotalDurationMs()

    // ── Topics ────────────────────────────────────────────────────────────────

    suspend fun createTopic(
        name: String,
        parentId: Long? = null,
        icon: String = Icons.DEFAULT_TOPIC,
        color: String = "#6C63FF"
    ): Long = topicDao.insert(TopicEntity(name = name, parentId = parentId, icon = icon, color = color))

    suspend fun updateTopic(topic: TopicEntity) =
        topicDao.update(topic.copy(updatedAt = System.currentTimeMillis()))

    suspend fun deleteTopic(topic: TopicEntity) = topicDao.delete(topic)

    fun getAllTopics(): Flow<List<TopicEntity>> = topicDao.getAllTopics()

    fun getTopScoringTopics(limit: Int): Flow<List<TopicEntity>> =
        topicDao.getTopScoring(limit)

    suspend fun topicExists(id: Long): Boolean = topicDao.getById(id) != null

    /**
     * Records a topic picker selection for scoring purposes.
     *
     * Computes score deltas for [topicId] and its ancestors via
     * [TopicScoringManager], then fires all DB updates concurrently.
     * Each update is independent — a failure on one ancestor doesn't
     * block the others. This is intentionally fire-and-forget from
     * the caller's perspective; no result is returned.
     *
     * Only call this for [Mode.PICK] selections — not REPARENT or PICK_PARENT.
     */
    suspend fun recordTopicUse(topicId: Long, allTopics: List<TopicEntity>) {
        val deltas = TopicScoringManager.computeDeltas(topicId, allTopics)
        deltas.forEach { (id, delta) ->
            topicDao.addScore(id, delta)
        }
    }

    // ── Recordings ────────────────────────────────────────────────────────────

    suspend fun getRecordingById(id: Long): RecordingEntity? =
        recordingDao.getById(id)

    suspend fun saveRecording(
        filePath: String,
        durationMs: Long,
        fileSizeBytes: Long,
        title: String,
        topicId: Long? = null
    ): Long = recordingDao.insert(
        RecordingEntity(
            filePath      = filePath,
            durationMs    = durationMs,
            fileSizeBytes = fileSizeBytes,
            title         = title,
            topicId       = topicId
        )
    )

    /**
     * Per-volume storage usage, live from the DB.
     * Delegated directly to [RecordingDao.getStorageUsageByVolume].
     * The ViewModel turns this into a [StateFlow<Map<String, Long>>].
     */
    fun getStorageUsageByVolume(): Flow<List<VolumeUsage>> =
        recordingDao.getStorageUsageByVolume()

    /**
     * All recordings on a given storage volume.
     * Used to identify orphaned recordings when a volume is unmounted.
     */
    fun getRecordingsByVolume(uuid: String): Flow<List<RecordingEntity>> =
        recordingDao.getByVolume(uuid)

    /**
     * Saves a recording and atomically flushes any marks dropped during
     * that recording session. The recording row is inserted first to
     * obtain its ID, then all mark timestamps are inserted in bulk.
     * If [markTimestamps] is empty, no mark rows are written.
     */
    suspend fun saveRecordingWithMarks(
        filePath: String,
        durationMs: Long,
        fileSizeBytes: Long,
        title: String,
        topicId: Long? = null,
        markTimestamps: List<Long>,
        storageVolumeUuid: String = StorageVolumeHelper.UUID_PRIMARY,
        createdAt: Long = System.currentTimeMillis()
    ): Long {
        val recordingId = recordingDao.insert(
            RecordingEntity(
                filePath          = filePath,
                durationMs        = durationMs,
                fileSizeBytes     = fileSizeBytes,
                title             = title,
                topicId           = topicId,
                storageVolumeUuid = storageVolumeUuid,
                createdAt         = createdAt
            )
        )
        if (markTimestamps.isNotEmpty()) {
            saveMarks(recordingId, markTimestamps)
        }
        return recordingId
    }

    /**
     * Returns recordings whose waveform is PENDING or stuck IN_PROGRESS.
     * Called by [SoundTreeApp] on startup to enqueue jobs.
     */
    suspend fun getPendingWaveformRecordings(): List<RecordingEntity> =
        recordingDao.getPendingWaveformRecordings()

    /**
     * Resets every recording's waveform status to PENDING.
     * Called by the "Regenerate all waveforms" action after cache files
     * have been deleted.
     */
    suspend fun resetAllWaveformStatuses() =
        recordingDao.resetAllWaveformStatuses()

    /**
     * Returns every recording in the database (oldest first).
     * Used for bulk re-enqueue after a full waveform reset.
     */
    suspend fun getAllRecordingsOnce(): List<RecordingEntity> =
        recordingDao.getAllOnce()

    suspend fun updateRecording(recording: RecordingEntity) = recordingDao.update(recording)
    suspend fun deleteRecording(recording: RecordingEntity) = recordingDao.delete(recording)

    suspend fun moveRecording(id: Long, topicId: Long?) =
        recordingDao.moveToTopic(id, topicId, System.currentTimeMillis())

    suspend fun renameRecording(id: Long, title: String) =
        recordingDao.rename(id, title, System.currentTimeMillis())

    suspend fun setFavourite(id: Long, fav: Boolean) =
        recordingDao.setFavourite(id, fav, System.currentTimeMillis())
    suspend fun updatePlayback(id: Long, posMs: Long, listened: Boolean) =
        recordingDao.updatePlaybackState(id, posMs, listened)

    fun getAllRecordings(): Flow<List<RecordingEntity>>          = recordingDao.getAll()
    fun getUnsorted(): Flow<List<RecordingEntity>>               = recordingDao.getUnsorted()
    fun getFavourites(): Flow<List<RecordingEntity>>             = recordingDao.getFavourites()
    fun searchRecordings(q: String): Flow<List<RecordingEntity>> = recordingDao.search(q)

    /**
     * Returns the set of all file paths currently registered in the database.
     * Used by [app.soundtree.util.OrphanRecordingScanner] at startup to
     * distinguish known recordings from orphaned files on disk.
     */
    suspend fun getKnownFilePaths(): Set<String> =
        recordingDao.getAllFilePaths().toHashSet()

    // ── Combined tree flow ────────────────────────────────────────────────────

    fun getTreeFlow(): Flow<List<TreeNode>> = combine(
        topicDao.getAllTopics(),
        recordingDao.getAll()
    ) { topics, recs ->
        TreeBuilder.build(topics, recs)
    }

    // ── Marks ─────────────────────────────────────────────────────────────────

    /**
     * Bulk-inserts mark timestamps for a given recording.
     * Called by [saveRecordingWithMarks]; can also be called independently
     * if marks need to be added to an existing recording in bulk.
     */
    suspend fun saveMarks(recordingId: Long, timestamps: List<Long>) {
        val entities = timestamps.map { positionMs ->
            MarkEntity(recordingId = recordingId, positionMs = positionMs)
        }
        markDao.insertAll(entities)
    }

    // ── Marks ─────────────────────────────────────────────────────────────────────

    fun getMarksForRecording(recordingId: Long) = markDao.getMarksForRecording(recordingId)

    /**
     * Inserts a new mark and atomically bumps [RecordingEntity.metadataUpdatedAt]
     * so that the next backup export pass knows to regenerate the JSON.
     */
    suspend fun addMark(recordingId: Long, positionMs: Long): Long = db.withTransaction {
        val markId = markDao.insert(MarkEntity(recordingId = recordingId, positionMs = positionMs))
        recordingDao.touchMetadata(recordingId, System.currentTimeMillis())
        markId
    }

    /**
     * Deletes a mark by ID and atomically bumps [RecordingEntity.metadataUpdatedAt].
     *
     * **Callers must pass [recordingId]** — it is not stored on the call site
     * but is available from the [MarkEntity] being deleted (mark.recordingId).
     */
    suspend fun deleteMark(markId: Long, recordingId: Long) = db.withTransaction {
        markDao.deleteById(markId)
        recordingDao.touchMetadata(recordingId, System.currentTimeMillis())
    }

    /**
     * Nudges a mark's position and atomically bumps [RecordingEntity.metadataUpdatedAt].
     *
     * **Callers must pass [recordingId]** — available from the [MarkEntity] being nudged.
     */
    suspend fun nudgeMark(markId: Long, deltaMs: Long, recordingId: Long) = db.withTransaction {
        markDao.nudgeMark(markId, deltaMs)
        recordingDao.touchMetadata(recordingId, System.currentTimeMillis())
    }

    // ── Backup targets ────────────────────────────────────────────────────────────

    /**
     * All configured backup targets, observed reactively.
     * Consumed by the Storage tab to build the backup target list UI.
     */
    fun getBackupTargets(): Flow<List<BackupTargetEntity>> =
        backupTargetDao.getAll()

    suspend fun getBackupTarget(id: Long): BackupTargetEntity? =
        backupTargetDao.getById(id)

    /**
     * Adds a new recurring backup target with default trigger settings
     * (on-connect + 24h schedule). The [dirUri] is set on the entity at
     * insert time so the UNIQUE constraint on [BackupTargetEntity.backupDirUri]
     * fires cleanly on insert (returning -1L) rather than on a later UPDATE.
     *
     * Returns the new target's surrogate [BackupTargetEntity.id], or -1L if the
     * insert was ignored due to a duplicate [dirUri].
     *
     * The periodic WorkManager job is enqueued immediately if the insert succeeds.
     */
    suspend fun addBackupTarget(volumeUuid: String?, dirUri: String): Long {
        val id = backupTargetDao.insert(
            BackupTargetEntity(
                volumeUuid   = volumeUuid,
                backupDirUri = dirUri,
            )
        )
        if (id > 0L) {
            BackupWorker.enqueueOrUpdatePeriodic(
                context       = appContext,
                targetId      = id,
                intervalHours = 24L,
            )
        }
        return id
    }

    /**
     * Finds or creates a manual-only backup target for [dirUri].
     *
     * - If a target already exists for [dirUri], returns its id immediately.
     *   No new entity is created; the existing target's trigger settings are
     *   left untouched (the user may have already promoted it to a recurring
     *   target via the gear dialog).
     * - If no target exists, inserts a new one with both automatic triggers
     *   disabled ([BackupTargetEntity.onConnectEnabled] = false,
     *   [BackupTargetEntity.scheduledEnabled] = false) and [dirUri] set upfront
     *   so the UNIQUE constraint fires on insert rather than on a subsequent UPDATE.
     *   If the insert is still somehow ignored (race condition), falls back to
     *   a second lookup.
     *
     * [exportMetadata] is applied only when a **new** target is created; it is
     * not retroactively applied to an existing target.
     *
     * Always returns a valid target id ≥ 1. Callers may immediately enqueue a
     * one-time [BackupWorker] job without additional null-checking.
     */
    suspend fun getOrCreateManualBackupTarget(
        dirUri: String,
        volumeUuid: String? = null,
        exportMetadata: Boolean = true,
    ): Long {
        // Fast path — target already exists for this directory.
        backupTargetDao.getByDirUri(dirUri)?.let { return it.id }

        // Slow path — insert a new manual-only target.
        val id = backupTargetDao.insert(
            BackupTargetEntity(
                volumeUuid            = volumeUuid,
                backupDirUri          = dirUri,
                onConnectEnabled      = false,
                scheduledEnabled      = false,
                exportMetadataEnabled = exportMetadata,
            )
        )

        // If insert was IGNORED (duplicate — extremely unlikely but possible in a
        // race), fall back to a second lookup to guarantee a valid id.
        if (id <= 0L) {
            return backupTargetDao.getByDirUri(dirUri)?.id
                ?: error("getOrCreateManualBackupTarget: insert failed and fallback lookup returned null for dirUri=$dirUri")
        }

        backupTargetDao.setVolumeLabel(id, dirUri)   // use the SAF URI as the display label

        return id
    }

    /**
     * Removes a backup target and cancels its periodic WorkManager job.
     * One-time (on-connect) jobs already enqueued will run to completion —
     * interrupting an in-progress backup is worse than letting it finish.
     */
    suspend fun removeBackupTarget(id: Long) {
        backupTargetDao.deleteById(id)
        BackupWorker.cancelPeriodic(appContext, id)
    }

    /**
     * Backup log entries for a specific target by surrogate PK, newest first.
     * Used for SAF-only targets where volume_uuid is null.
     */
    fun getBackupLogsForTarget(targetId: Long): Flow<List<BackupLogEntity>> =
        backupLogDao.getByTargetId(targetId)

    /**
     * Caches the OS-provided display label for a backup target.
     * Called when a target is first added and after each backup run.
     */
    suspend fun setBackupTargetLabel(id: Long, label: String) =
        backupTargetDao.setVolumeLabel(id, label)

    /**
     * Toggles the on-connect backup trigger for a target.
     * No WorkManager changes needed — [StorageMountReceiver] reads this flag
     * live from the DB at mount time.
     */
    suspend fun setBackupOnConnectEnabled(id: Long, enabled: Boolean) =
        backupTargetDao.setOnConnectEnabled(id, enabled)

    /**
     * Toggles the scheduled backup trigger for a target.
     * Enqueues or cancels the periodic WorkManager job accordingly.
     */
    suspend fun setBackupScheduledEnabled(id: Long, enabled: Boolean) {
        backupTargetDao.setScheduledEnabled(id, enabled)
        if (enabled) {
            val intervalHours = backupTargetDao.getById(id)?.intervalHours?.toLong() ?: return
            BackupWorker.enqueueOrUpdatePeriodic(appContext, id, intervalHours)
        } else {
            BackupWorker.cancelPeriodic(appContext, id)
        }
    }

    /**
     * Updates the scheduled backup interval for a target. If scheduling is
     * currently enabled, the periodic WorkManager job is replaced immediately
     * so the new interval takes effect without waiting for the next fire.
     */
    suspend fun setBackupIntervalHours(id: Long, hours: Int) {
        backupTargetDao.setIntervalHours(id, hours)
        val target = backupTargetDao.getById(id) ?: return
        if (target.scheduledEnabled) {
            BackupWorker.enqueueOrUpdatePeriodic(appContext, id, hours.toLong())
        }
    }

    suspend fun setBackupPreferencesEnabled(id: Long, enabled: Boolean) =
        backupTargetDao.setBackupPreferences(id, enabled)

    /**
     * Toggles writing companion .json metadata files during backup for a target.
     * No WorkManager changes needed — BackupWorker reads this flag at run time.
     */
    suspend fun setExportMetadataEnabled(id: Long, enabled: Boolean) =
        backupTargetDao.setExportMetadataEnabled(id, enabled)

    /**
     * Re-enqueues a periodic WorkManager job for every backup target that has
     * scheduled backups enabled.
     *
     * WorkManager persists its job queue across normal app restarts, but that
     * queue can be silently lost after a force-stop, an OS-level job pruning,
     * or certain app updates. Calling this on every launch is cheap (it is a
     * no-op for jobs that are already live, because [BackupWorker.enqueueOrUpdatePeriodic]
     * uses [ExistingPeriodicWorkPolicy.UPDATE]) and ensures the schedule is
     * always consistent with the DB, even after those edge-case losses.
     */
    suspend fun reconcileScheduledBackups() {
        backupTargetDao.getScheduledTargets().forEach { target ->
            BackupWorker.enqueueOrUpdatePeriodic(
                context       = appContext,
                targetId      = target.id,
                intervalHours = target.intervalHours.toLong(),
            )
        }
    }


    // ── Backup logs ───────────────────────────────────────────────────────────

    fun getBackupLog(logId: Long): Flow<BackupLogEntity?> {
        return backupLogDao.observeById(logId)
    }

    /**
     * All backup log entries, newest first.
     * Consumed by the Tools tab backup history UI.
     */
    fun getBackupLogs(): Flow<List<BackupLogEntity>> =
        backupLogDao.getAll()

    /**
     * Backup log entries for a specific volume, newest first.
     * Used to populate per-target history in the Storage tab.
     */
    fun getBackupLogsForVolume(volumeUuid: String): Flow<List<BackupLogEntity>> =
        backupLogDao.getByVolume(volumeUuid)

    /**
     * The most recent completed log entry for a volume.
     * Used for the "Last backed up: …" label on target rows without
     * observing the full history flow.
     */
    suspend fun getLastBackupForVolume(volumeUuid: String): BackupLogEntity? =
        backupLogDao.getLastCompletedForVolume(volumeUuid)

    /**
     * All event rows (INFO milestones + WARNING/ERROR problems) for a specific
     * backup run. Consumed by the backup log detail view.
     *
     * To show only user-visible problems (WARNING + ERROR), use
     * [getBackupLogProblems] instead, which filters out INFO rows.
     */
    fun getBackupLogEvents(logId: Long): Flow<List<BackupLogEventEntity>> =
        backupLogDao.getEventsForLog(logId)

    /**
     * WARNING + ERROR rows only for a specific backup run.
     * Use this when computing user-visible error counts or rendering
     * summaries that should not include INFO milestone rows.
     */
    fun getBackupLogProblems(logId: Long): Flow<List<BackupLogEventEntity>> =
        backupLogDao.getProblemsForLog(logId)


    /** Clears all backup log entries (and their child error rows via CASCADE). */
    suspend fun clearAllBackupLogs() = backupLogDao.clearAll()

    /**
     * Clears backup log entries for a specific volume.
     * Offered when the user removes a backup target.
     */
    suspend fun clearBackupLogsForVolume(volumeUuid: String) =
        backupLogDao.clearForVolume(volumeUuid)

    /**
     * All backup log entries whose run is still in progress (status IS NULL).
     * Used by [MainViewModel.backupUiState] to drive live-progress UI.
     */
    fun getInProgressBackupLogs(): Flow<List<BackupLogEntity>> =
        backupLogDao.getInProgressBackupLogs()

    /**
     * The most recent INFO event message for an in-progress log entry, or null
     * when verbose logging is off or no INFO events have been written yet.
     */
    fun getLatestInfoMessageForLog(logId: Long): Flow<String?> =
        backupLogDao.getLatestInfoMessagesForLog(logId).map { it.firstOrNull() }

    /**
     * Delegates to [RecordingStructureMigrator] to move any flat-placed
     * recording files into their correct YYYY/MM subdirectories and update
     * the DB to match.
     *
     * [onProgress] receives the running moved count and the filename that
     * was just moved, for use by the UI progress display.
     */
    suspend fun migrateRecordingStructure(
        onProgress: suspend (movedSoFar: Int, filename: String) -> Unit = { _, _ -> },
    ): RecordingStructureMigrator.Result =
        RecordingStructureMigrator.migrate(recordingDao, onProgress)

    /**
     * Marks any dangling in-progress log rows for [volumeUuid] as INTERRUPTED.
     * Called by [BackupWorker] at the very start of a new run, before inserting
     * its own log row. Since WorkManager won't schedule a second unique job while
     * the first is genuinely running, the presence of a new job is proof that any
     * existing status=NULL row for this volume is stale.
     */
    suspend fun markStaleBackupLogInterrupted(volumeUuid: String) {
        backupLogDao.markInterruptedForVolume(
            volumeUuid = volumeUuid,
            endedAt    = System.currentTimeMillis(),
            message    = "Backup was interrupted — the worker process was terminated before the run could complete.",
        )
    }


    /**
     * Cross-references in-progress DB rows against WorkManager's live job state
     * and marks any rows with no corresponding RUNNING/ENQUEUED job as INTERRUPTED.
     *
     * Called once at app startup from [SoundTreeApp]. Handles the population of
     * dangling rows left from prior crashes or force-stops where no subsequent
     * backup for that target has been triggered to self-heal via
     * [markStaleBackupLogInterrupted].
     *
     * ## v16 changes
     * Job tags now use [BackupWorker.TAG_TARGET_PREFIX] + targetId (Long) rather
     * than the old TAG_VOLUME_PREFIX + volumeUuid. Step 3 extracts target IDs from
     * the live job tags and step 4 cross-references them against the stale log rows
     * via [BackupLogEntity.backupTargetId] (the new FK column).
     *
     * Logs whose [BackupLogEntity.backupTargetId] is null (target was deleted) or
     * whose [BackupLogEntity.volumeUuid] is null (SAF-only target with no UUID
     * identity) are always considered stale — there is no active job that could
     * legitimately own them.
     */
    suspend fun reconcileStaleBackupLogs() {
        val tag = "BackupReconcile"
        Log.i(tag, "▶ reconcileStaleBackupLogs() started")

        // ── 1. Check DB for in-progress rows ──────────────────────────────────
        val staleLogs = backupLogDao.getInProgressOnce()
        if (staleLogs.isEmpty()) {
            Log.i(tag, "✔ No in-progress log rows found — nothing to reconcile")
            return
        }
        Log.i(tag, "⚠ Found ${staleLogs.size} in-progress log row(s):")
        staleLogs.forEach { log ->
            val ageMs  = System.currentTimeMillis() - log.startedAt
            val ageSec = ageMs / 1000
            Log.i(tag, "    • logId=${log.id}  targetId=${log.backupTargetId}  " +
                    "volume=${log.volumeUuid}  startedAt=${log.startedAt}  age=${ageSec}s")
        }

        // ── 2. Query WorkManager for all BackupWorker jobs ────────────────────
        Log.i(tag, "Querying WorkManager for tag \"${BackupWorker.TAG}\"…")
        val workInfos = try {
            WorkManager.getInstance(appContext)
                .getWorkInfosByTagFlow(BackupWorker.TAG)
                .first()
        } catch (e: Exception) {
            Log.e(tag, "✘ WorkManager query failed — aborting reconcile: ${e.message}", e)
            return
        }

        if (workInfos.isEmpty()) {
            Log.i(tag, "WorkManager returned 0 jobs for tag \"${BackupWorker.TAG}\"")
        } else {
            Log.i(tag, "WorkManager returned ${workInfos.size} job(s):")
            workInfos.forEach { wi ->
                val targetTag = wi.tags.firstOrNull { it.startsWith(BackupWorker.TAG_TARGET_PREFIX) }
                Log.i(tag, "    • id=${wi.id}  state=${wi.state}  targetTag=$targetTag")
            }
        }

        // ── 3. Build the set of target IDs that are genuinely active ──────────
        // Extract Long IDs from tags shaped "backup_target_<id>".
        val activeTargetIds = workInfos
            .filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            .flatMap { it.tags }
            .filter { it.startsWith(BackupWorker.TAG_TARGET_PREFIX) }
            .mapNotNullTo(mutableSetOf()) {
                it.removePrefix(BackupWorker.TAG_TARGET_PREFIX).toLongOrNull()
            }

        Log.i(tag, "Active target IDs (RUNNING or ENQUEUED): " +
                if (activeTargetIds.isEmpty()) "(none)" else activeTargetIds.joinToString())

        // ── 4. Mark stale rows as INTERRUPTED ────────────────────────────────
        // A log row is stale if its backupTargetId is not in the active set.
        // Rows with null backupTargetId (target deleted) are always stale.
        // Rows with null volumeUuid skip the markInterruptedForVolume call (no
        // volume identity to anchor the query) and are stamped directly by id.
        val now = System.currentTimeMillis()
        var markedCount = 0
        staleLogs.forEach { log ->
            val targetId = log.backupTargetId
            if (targetId != null && targetId in activeTargetIds) {
                Log.i(tag, "    SKIP  logId=${log.id}  targetId=$targetId — job is genuinely running")
                return@forEach
            }

            Log.w(tag, "    MARK  logId=${log.id}  targetId=$targetId  volume=${log.volumeUuid} " +
                    "— no active job found, marking INTERRUPTED")

            val volumeUuid = log.volumeUuid
            if (volumeUuid != null) {
                // Fast path: mark all stale rows for this volume in one query.
                backupLogDao.markInterruptedForVolume(
                    volumeUuid = volumeUuid,
                    endedAt    = now,
                    message    = "Backup was interrupted — the app or worker process was terminated before the run could complete.",
                )
            } else {
                // SAF-only target with no volume UUID — stamp by log id directly.
                backupLogDao.markInterruptedById(
                    id      = log.id,
                    endedAt = now,
                    message = "Backup was interrupted — the app or worker process was terminated before the run could complete.",
                )
            }
            markedCount++
        }

        Log.i(tag, "✔ reconcileStaleBackupLogs() complete — marked $markedCount row(s) as INTERRUPTED")
    }

    // ── Singleton ─────────────────────────────────────────────────────────────

    companion object {
        @Volatile private var INSTANCE: SoundTreeRepository? = null
        fun getInstance(context: Context): SoundTreeRepository =
            INSTANCE ?: synchronized(this) {
                SoundTreeRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}