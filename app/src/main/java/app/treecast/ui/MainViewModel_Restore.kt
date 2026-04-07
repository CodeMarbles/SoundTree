package app.treecast.ui

// ─────────────────────────────────────────────────────────────────────────────
// MainViewModel_Restore.kt
//
// Extension functions on MainViewModel covering database restore from backup.
// ─────────────────────────────────────────────────────────────────────────────

import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import app.treecast.util.DatabaseRestoreManager
import app.treecast.worker.BackupWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Performs a destructive database restore from the backup directory associated
 * with [volumeUuid].
 *
 * ## Sequence
 * 1. Cancels all WorkManager jobs tagged [BackupWorker.TAG] so no backup
 *    worker can be touching the DB while the restore runs.
 * 2. Delegates to [DatabaseRestoreManager.restore] which handles the
 *    checkpoint → close → copy → fixup chain on IO threads.
 * 3. On success: calls [DatabaseRestoreManager.scheduleRestartAndExit] —
 *    this schedules a relaunch and kills the process. **Does not return.**
 * 4. On failure: invokes [onError] on the main thread with a human-readable
 *    message so the UI can display it. The live database is unmodified if
 *    the error occurred before the file copy, or in the best possible state
 *    if it occurred after (Room will still open, possibly after migrations).
 *
 * @param volumeUuid    UUID of the backup target (unused by restore logic itself,
 *                      kept for symmetry with other backup extension functions and
 *                      for future per-volume restore logging).
 * @param backupDirUri  SAF tree URI string from [BackupTargetEntity.backupDirUri].
 * @param onError       Called on the main thread if the restore fails. Not called
 *                      on success because the process is killed instead.
 */
fun MainViewModel.restoreFromBackup(
    @Suppress("UNUSED_PARAMETER") volumeUuid: String,
    backupDirUri: String,
    onError: (String) -> Unit,
) {
    viewModelScope.launch {
        // Cancel every backup job — we're about to close the database.
        WorkManager.getInstance(getApplication())
            .cancelAllWorkByTag(BackupWorker.TAG)

        val result = DatabaseRestoreManager.restore(
            context      = getApplication(),
            backupDirUri = backupDirUri,
        )

        when (result) {
            is DatabaseRestoreManager.Result.Success -> {
                // Schedule restart on main thread then exit. Never returns.
                withContext(Dispatchers.Main) {
                    DatabaseRestoreManager.scheduleRestartAndExit(getApplication())
                }
            }

            is DatabaseRestoreManager.Result.NoDbFound -> {
                withContext(Dispatchers.Main) {
                    onError(
                        "No database file was found in this backup. " +
                                "The backup may be incomplete or was made before " +
                                "database backup was enabled."
                    )
                }
            }

            is DatabaseRestoreManager.Result.Failed -> {
                withContext(Dispatchers.Main) {
                    onError(result.reason)
                }
            }
        }
    }
}