package app.soundtree.ui

// ─────────────────────────────────────────────────────────────────────────────
// MainViewModel_Backup.kt
//
// Extension functions on MainViewModel covering the automatic backup system.
//
// v16 change: all per-target mutations now take targetId: Long (the surrogate
// PK) instead of volumeUuid: String.
// ─────────────────────────────────────────────────────────────────────────────

import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import app.soundtree.data.entities.BackupLogEntity
import app.soundtree.data.entities.BackupLogEventEntity
import app.soundtree.ui.MainViewModel.MigrationState
import app.soundtree.worker.BackupWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

// ── Log queries ───────────────────────────────────────────────────────────────

/**
 * Live single-row query for [BackupLogDetailDialog].
 * Emits on every write the worker makes to this log row.
 */
fun MainViewModel.getBackupLog(logId: Long): Flow<BackupLogEntity?> =
    repo.getBackupLog(logId)

/**
 * Backup log entries for a specific volume, newest first.
 * Consumed by [BackupTargetConfigDialog] for targets that have a volumeUuid.
 * Note: still keyed on volumeUuid because the log denormalizes that column.
 */
fun MainViewModel.getBackupLogsForVolume(volumeUuid: String): Flow<List<BackupLogEntity>> =
    repo.getBackupLogsForVolume(volumeUuid)

/**
 * Backup log entries for a specific target by surrogate PK, newest first.
 * Used by [BackupTargetConfigDialog] for SAF-only targets where volumeUuid
 * is null and [getBackupLogsForVolume] would return nothing.
 */
fun MainViewModel.getBackupLogsForTarget(targetId: Long): Flow<List<BackupLogEntity>> =
    repo.getBackupLogsForTarget(targetId)

/**
 * All events (INFO + WARNING + ERROR) for a specific backup run.
 */
fun MainViewModel.getBackupLogEvents(logId: Long): Flow<List<BackupLogEventEntity>> =
    repo.getBackupLogEvents(logId)

/**
 * WARNING + ERROR events only for a specific backup run.
 */
fun MainViewModel.getBackupLogProblems(logId: Long): Flow<List<BackupLogEventEntity>> =
    repo.getBackupLogProblems(logId)

// ── Target management ─────────────────────────────────────────────────────────

/**
 * Designates a new recurring backup target and persists the SAF directory URI
 * chosen by the user. Called immediately after a successful
 * [Intent.ACTION_OPEN_DOCUMENT_TREE] result in [SettingsFragment].
 *
 * [volumeUuid] may be null for SAF-only targets where no volume UUID could
 * be resolved from the tree URI.
 *
 * The [dirUri] is passed directly into the entity at insert time (rather than
 * via a separate [setBackupTargetDirUri] UPDATE) to ensure the UNIQUE constraint
 * on backup_dir_uri fires cleanly on the insert if the directory is already
 * a configured target.
 */
fun MainViewModel.addBackupTarget(volumeUuid: String?, dirUri: String) {
    viewModelScope.launch {
        val targetId = repo.addBackupTarget(volumeUuid, dirUri)
        if (targetId <= 0L) return@launch   // insert was ignored (duplicate URI)
        // Cache the label while we know the volume is mounted.
        if (volumeUuid != null) {
            storageVolumes.value
                .firstOrNull { it.uuid == volumeUuid }
                ?.label
                ?.let { repo.setBackupTargetLabel(targetId, it) }
        }
    }
}

/**
 * Finds or creates a manual-only backup target for [dirUri], then immediately
 * enqueues a one-time WorkManager backup.
 *
 * This is the "Back Up to Folder…" path:
 *  - If a target already exists for [dirUri], the existing target is reused
 *    (its trigger settings are not modified).
 *  - If no target exists, a new one is created with both automatic triggers
 *    disabled. The target appears in "Backup Destinations" as "Manual only"
 *    and can be promoted via the gear dialog at any time.
 *  - A [BackupWorker] one-time job is always enqueued (WorkManager's KEEP
 *    policy means it's a no-op if a job for this target is already running).
 *
 * [volumeUuid] is null for true one-time SAF targets where no volume UUID
 * could be extracted from the URI.
 *
 * [exportMetadata] controls whether JSON sidecars are written during the
 * backup run. Only applied when a **new** target is created.
 */
fun MainViewModel.addOneTimeBackupTarget(
    dirUri: String,
    volumeUuid: String? = null,
    exportMetadata: Boolean = true,
) {
    viewModelScope.launch {
        val targetId = repo.getOrCreateManualBackupTarget(
            dirUri        = dirUri,
            volumeUuid    = volumeUuid,
            exportMetadata = exportMetadata,
        )
        BackupWorker.enqueueOneTime(
            context  = getApplication(),
            targetId = targetId,
            trigger  = BackupLogEntity.BackupTrigger.MANUAL,
        )
    }
}

/**
 * Removes a backup target and cancels its periodic WorkManager job.
 * Any currently-running or enqueued one-time backup is left to complete.
 */
fun MainViewModel.removeBackupTarget(targetId: Long) {
    viewModelScope.launch { repo.removeBackupTarget(targetId) }
}

fun MainViewModel.setBackupOnConnectEnabled(targetId: Long, enabled: Boolean) {
    viewModelScope.launch { repo.setBackupOnConnectEnabled(targetId, enabled) }
}

fun MainViewModel.setBackupTargetLabel(targetId: Long, label: String) {
    viewModelScope.launch { repo.setBackupTargetLabel(targetId, label) }
}

/**
 * Toggles scheduled backups for [targetId]. Enqueues or cancels the
 * WorkManager [PeriodicWorkRequest] accordingly via the repository.
 */
fun MainViewModel.setBackupScheduledEnabled(targetId: Long, enabled: Boolean) {
    viewModelScope.launch { repo.setBackupScheduledEnabled(targetId, enabled) }
}

/**
 * Toggles companion .json metadata export for recordings backed up to [targetId].
 */
fun MainViewModel.setExportMetadataEnabled(targetId: Long, enabled: Boolean) {
    viewModelScope.launch { repo.setExportMetadataEnabled(targetId, enabled) }
}

/**
 * Updates the scheduled interval and replaces the live WorkManager periodic
 * request if scheduling is currently enabled.
 */
fun MainViewModel.setBackupIntervalHours(targetId: Long, hours: Int) {
    viewModelScope.launch { repo.setBackupIntervalHours(targetId, hours) }
}

// ── Log management ────────────────────────────────────────────────────────────

/**
 * Clears all backup log entries for [volumeUuid].
 *
 * Silently no-ops if a backup is currently running for this volume —
 * the UI is responsible for checking [backupUiState] first and showing
 * a Toast so the user understands why nothing happened.
 */
fun MainViewModel.clearBackupLogsForVolume(volumeUuid: String) {
    if (backupUiState.value.activeJobs.any { it.log.volumeUuid == volumeUuid }) return
    viewModelScope.launch { repo.clearBackupLogsForVolume(volumeUuid) }
}

/**
 * Clears all backup log entries across every volume.
 * Silently no-ops if any backup is currently running.
 */
fun MainViewModel.clearAllBackupLogs() {
    if (backupUiState.value.isAnyRunning) return
    viewModelScope.launch { repo.clearAllBackupLogs() }
}

// ── Operational ───────────────────────────────────────────────────────────────

/**
 * Enqueues a one-time manual backup for [targetId]. Safe to call even if a
 * backup is already running — ExistingWorkPolicy.KEEP makes it a no-op until
 * the current job finishes.
 */
fun MainViewModel.triggerManualBackup(targetId: Long) {
    viewModelScope.launch {
        BackupWorker.enqueueOneTime(
            context  = getApplication(),
            targetId = targetId,
            trigger  = BackupLogEntity.BackupTrigger.MANUAL,
        )
    }
}

/**
 * Cancels all WorkManager jobs for a specific backup target (both the running
 * one-time job and any enqueued periodic job).
 *
 * Uses the per-target WorkManager tag [BackupWorker.TAG_TARGET_PREFIX] + targetId,
 * which is attached to every job enqueued via [BackupWorker.enqueueOneTime] and
 * [BackupWorker.enqueueOrUpdatePeriodic]. WorkManager will call
 * [ListenableWorker.onStopped] on any in-progress worker, giving it a chance
 * to finalise the log row before exiting.
 */
fun MainViewModel.cancelBackupForTarget(targetId: Long) {
    WorkManager.getInstance(getApplication())
        .cancelAllWorkByTag("${BackupWorker.TAG_TARGET_PREFIX}$targetId")
}

fun MainViewModel.dismissBackupStrip(logId: Long) {
    _stripDismissedIds.value = _stripDismissedIds.value + logId
}

/**
 * Emits on [navigateToStorageTab], requesting navigation to the
 * Settings → Storage tab. Observed by both [MainActivity] and [SettingsFragment].
 */
fun MainViewModel.requestNavigateToStorageTab() {
    _navigateToStorageTab.tryEmit(Unit)
}

// ── Migration (Future Mode only) ──────────────────────────────────────────────

/**
 * Kicks off a recording structure migration run.
 *
 * No-ops if a run is already in progress. The caller (SettingsFragment)
 * should separately guard against active recording before calling this.
 */
fun MainViewModel.migrateRecordingStructure() {
    if (_migrationState.value is MigrationState.Running) return
    viewModelScope.launch {
        _migrationState.value = MigrationState.Running(0, "")
        val result = repo.migrateRecordingStructure { movedSoFar, filename ->
            _migrationState.value = MigrationState.Running(movedSoFar, filename)
        }
        _migrationState.value = MigrationState.Done(result.moved, result.failed)
    }
}