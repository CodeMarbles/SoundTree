package app.soundtree.ui

// ─────────────────────────────────────────────────────────────────────────────
// MainViewModel_Backup.kt
//
// Extension functions on MainViewModel covering the automatic backup system
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
 * Emits on every write the worker makes to this log row (stats update,
 * status flip, etc.). The dialog observes this to keep its header and
 * metadata grid live while the backup is in progress.
 */
fun MainViewModel.getBackupLog(logId: Long): Flow<BackupLogEntity?> =
    repo.getBackupLog(logId)

/**
 * Backup log entries for a specific volume, newest first.
 * Consumed by [BackupTargetConfigDialog] to show per-volume recent runs.
 */
fun MainViewModel.getBackupLogsForVolume(volumeUuid: String): Flow<List<BackupLogEntity>> =
    repo.getBackupLogsForVolume(volumeUuid)

/**
 * All events (INFO + WARNING + ERROR) for a specific backup run.
 * Used by [BackupLogDetailDialog] when the "Show milestones" toggle is on.
 */
fun MainViewModel.getBackupLogEvents(logId: Long): Flow<List<BackupLogEventEntity>> =
    repo.getBackupLogEvents(logId)

/**
 * WARNING + ERROR events only for a specific backup run.
 * The default view in [BackupLogDetailDialog]; hides INFO milestone rows.
 */
fun MainViewModel.getBackupLogProblems(logId: Long): Flow<List<BackupLogEventEntity>> =
    repo.getBackupLogProblems(logId)

// ── Target management ─────────────────────────────────────────────────────────

/**
 * Designates [volumeUuid] as a backup target and persists the SAF
 * directory URI chosen by the user. Called immediately after a
 * successful [Intent.ACTION_OPEN_DOCUMENT_TREE] result in [SettingsFragment].
 */
fun MainViewModel.addBackupTarget(volumeUuid: String, dirUri: String) {
    viewModelScope.launch {
        repo.addBackupTarget(volumeUuid)
        repo.setBackupTargetDirUri(volumeUuid, dirUri)
        // Cache the label while we know the volume is mounted (it's in
        // backupAvailableVolumes, so the OS label is guaranteed available).
        storageVolumes.value
            .firstOrNull { it.uuid == volumeUuid }
            ?.label
            ?.let { repo.setBackupTargetLabel(volumeUuid, it) }
    }
}

/**
 * Removes a backup target and cancels its periodic WorkManager job.
 * Any currently-running or enqueued one-time backup is left to complete.
 */
fun MainViewModel.removeBackupTarget(volumeUuid: String) {
    viewModelScope.launch { repo.removeBackupTarget(volumeUuid) }
}

fun MainViewModel.setBackupOnConnectEnabled(volumeUuid: String, enabled: Boolean) {
    viewModelScope.launch { repo.setBackupOnConnectEnabled(volumeUuid, enabled) }
}

fun MainViewModel.setBackupTargetLabel(volumeUuid: String, label: String) {
    viewModelScope.launch { repo.setBackupTargetLabel(volumeUuid, label) }
}

/**
 * Toggles scheduled backups for [volumeUuid]. Enqueues or cancels the
 * WorkManager [PeriodicWorkRequest] accordingly via the repository.
 */
fun MainViewModel.setBackupScheduledEnabled(volumeUuid: String, enabled: Boolean) {
    viewModelScope.launch { repo.setBackupScheduledEnabled(volumeUuid, enabled) }
}

/**
 * Toggles companion .json metadata export for recordings backed up to [volumeUuid].
 * No WorkManager side-effects — the worker reads this flag at the start of each run.
 */
fun MainViewModel.setExportMetadataEnabled(volumeUuid: String, enabled: Boolean) {
    viewModelScope.launch { repo.setExportMetadataEnabled(volumeUuid, enabled) }
}

/**
 * Updates the scheduled interval and replaces the live WorkManager
 * periodic request if scheduling is currently enabled.
 */
fun MainViewModel.setBackupIntervalHours(volumeUuid: String, hours: Int) {
    viewModelScope.launch { repo.setBackupIntervalHours(volumeUuid, hours) }
}

// ── Log management ────────────────────────────────────────────────────────────

/**
 * Clears all backup log entries for [volumeUuid].
 *
 * Silently no-ops if a backup is currently running for this volume —
 * the UI is responsible for checking [backupUiState] first and showing
 * a [Toast] so the user understands why nothing happened. The guard here
 * is a secondary safety net so a direct ViewModel call can never corrupt
 * an in-progress log row.
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
 * Enqueues a one-time manual backup for [volumeUuid]. Safe to call even
 * if a backup is already running — ExistingWorkPolicy.KEEP makes it a
 * no-op until the current job finishes.
 */
fun MainViewModel.triggerManualBackup(volumeUuid: String) {
    BackupWorker.enqueueOneTime(
        context    = getApplication(),
        volumeUuid = volumeUuid,
        trigger    = BackupLogEntity.BackupTrigger.MANUAL,
    )
}

/**
 * Cancels all WorkManager jobs tagged with this volume's per-volume tag.
 * BackupWorker checks [ListenableWorker.isStopped] and will finalise the
 * log row with FAILED status on cancellation.
 */
fun MainViewModel.cancelBackupForVolume(volumeUuid: String) {
    WorkManager.getInstance(getApplication())
        .cancelAllWorkByTag("${BackupWorker.TAG_VOLUME_PREFIX}$volumeUuid")
}

// ── Strip / navigation ────────────────────────────────────────────────────────

/**
 * Dismisses [logId] from the title-bar strip.
 * Called by auto-dismiss timers and direct user taps on a Completed strip.
 */
fun MainViewModel.dismissBackupStrip(logId: Long) {
    _stripDismissedIds.value += logId
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