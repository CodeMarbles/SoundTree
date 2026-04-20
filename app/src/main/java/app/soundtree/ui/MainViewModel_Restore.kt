package app.soundtree.ui

// ─────────────────────────────────────────────────────────────────────────────
// MainViewModel_Restore.kt
//
// Extension functions and state types on MainViewModel covering database
// restore from backup, including the restore wizard's progress state.
// ─────────────────────────────────────────────────────────────────────────────

import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import app.soundtree.util.DatabaseRestoreManager
import app.soundtree.worker.BackupWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Restore progress state ────────────────────────────────────────────────────

/**
 * Models the current state of a restore operation for the wizard's progress step.
 *
 * [Idle]       — no restore in progress; initial / post-cancel state.
 * [Running]    — a phase is executing. [label] describes the current phase;
 *                [current] and [total] drive a determinate progress bar when
 *                total > 0, or an indeterminate spinner when total == 0.
 * [Error]      — the restore failed. [message] is shown in the wizard.
 *                Distinguishes pre-flight failures (safe to retry) from
 *                post-swap failures (DB may be partially restored).
 *
 * Success has no terminal state here because the process is killed and
 * the app restarts — the wizard never reaches a "Done" screen.
 */
sealed class RestorePhase {
    object Idle : RestorePhase()

    data class Running(
        val label: String,
        val current: Int = 0,
        val total: Int = 0,
    ) : RestorePhase()

    data class Error(
        val message: String,
        val isPostSwap: Boolean = false,
    ) : RestorePhase()
}

// ── ViewModel state fields ────────────────────────────────────────────────────
//
// Stored as an extension property backed by a MutableStateFlow. Because
// extension properties cannot hold backing fields, we use a companion-object
// map pattern with a dedicated holder object.

private val _restorePhaseHolder =
    java.util.WeakHashMap<MainViewModel, MutableStateFlow<RestorePhase>>()

private fun MainViewModel.restorePhaseFlow(): MutableStateFlow<RestorePhase> =
    synchronized(_restorePhaseHolder) {
        _restorePhaseHolder[this]
            ?: MutableStateFlow<RestorePhase>(RestorePhase.Idle)
                .also { _restorePhaseHolder[this] = it }
    }

/**
 * Current restore phase. Observed by [RestoreWizardDialogFragment] to drive
 * its progress step. Remains [RestorePhase.Idle] when no restore is running.
 */
val MainViewModel.restorePhase: StateFlow<RestorePhase>
    get() = restorePhaseFlow()

// ── Snapshot listing ──────────────────────────────────────────────────────────

/**
 * Scans [backupDirUri] for restorable database snapshots and returns them
 * sorted newest-first (legacy flat backup appended last if present).
 *
 * Runs on [Dispatchers.IO] internally — safe to call from any coroutine scope.
 * Returns an empty list if the directory contains no recognisable snapshots.
 */
suspend fun MainViewModel.listDbSnapshots(
    backupDirUri: String,
): List<DatabaseRestoreManager.DbSnapshot> =
    DatabaseRestoreManager.listSnapshots(getApplication(), backupDirUri)

// ── Library summary ───────────────────────────────────────────────────────────

/**
 * Snapshot of the current live library's content counts.
 * Surfaced in the restore wizard's summary step so the user knows exactly
 * what is about to be overwritten.
 */
typealias LibrarySummary = DatabaseRestoreManager.LibrarySummary

/**
 * Queries the live database for recording / mark / topic counts.
 * Safe to call from any coroutine scope — runs on [Dispatchers.IO] internally.
 */
suspend fun MainViewModel.getLibrarySummary(): LibrarySummary =
    DatabaseRestoreManager.getLibrarySummary(getApplication())

// ── Core restore ──────────────────────────────────────────────────────────────

/**
 * Performs a full database restore from [backupFile], including:
 *  - A safety snapshot of the live database.
 *  - A metadata JSON export of all current recordings (marks safety net).
 *  - A destructive database swap.
 *  - An audio file copy from [backupRootDir]'s `recordings/` subtree.
 *  - A `file_path` remap pass in the restored database.
 *  - A waveform cache restore from `appdata/waveforms/` if present.
 *
 * ## Progress
 * [restorePhase] is updated throughout. The wizard observes this StateFlow
 * to drive its progress step UI.
 *
 * ## Sequence
 * 1. Cancels all [BackupWorker] jobs.
 * 2. Delegates to [DatabaseRestoreManager.restore] for the full sequence.
 * 3. On success: calls [DatabaseRestoreManager.scheduleRestartAndExit] on
 *    the main thread. **Does not return.**
 * 4. On failure: sets [restorePhase] to [RestorePhase.Error] on the main
 *    thread. The wizard surfaces the error message.
 *
 * @param backupRootDirUri  SAF URI string of the backup's root directory
 *                          (the folder containing `db/`, `recordings/`, etc.).
 * @param backupFile        The exact snapshot [DocumentFile] chosen by the user,
 *                          as returned by [listDbSnapshots].
 */
fun MainViewModel.restoreFromBackup(
    backupRootDirUri: String,
    backupFile: DocumentFile,
) {
    val phaseFlow = restorePhaseFlow()

    viewModelScope.launch {
        // Cancel every backup job — we're about to close the database.
        WorkManager.getInstance(getApplication())
            .cancelAllWorkByTag(BackupWorker.TAG)

        val backupRootDir = withContext(Dispatchers.IO) {
            DocumentFile.fromTreeUri(getApplication(), android.net.Uri.parse(backupRootDirUri))
        }

        if (backupRootDir == null) {
            phaseFlow.value = RestorePhase.Error(
                "Could not open the backup directory. " +
                        "The permission may have been revoked."
            )
            return@launch
        }

        val result = DatabaseRestoreManager.restore(
            context       = getApplication(),
            backupFile    = backupFile,
            backupRootDir = backupRootDir,
            onProgress    = { label, current, total ->
                phaseFlow.value = RestorePhase.Running(label, current, total)
            },
        )

        when (result) {
            is DatabaseRestoreManager.Result.Success -> {
                withContext(Dispatchers.Main) {
                    DatabaseRestoreManager.scheduleRestartAndExit(getApplication())
                }
            }

            is DatabaseRestoreManager.Result.NoDbFound -> {
                withContext(Dispatchers.Main) {
                    phaseFlow.value = RestorePhase.Error(
                        "No database file was found in the snapshot. " +
                                "The file may have been deleted."
                    )
                }
            }

            is DatabaseRestoreManager.Result.FailedPreFlight -> {
                withContext(Dispatchers.Main) {
                    phaseFlow.value = RestorePhase.Error(
                        message    = result.reason,
                        isPostSwap = false,
                    )
                }
            }

            is DatabaseRestoreManager.Result.FailedPostSwap -> {
                withContext(Dispatchers.Main) {
                    phaseFlow.value = RestorePhase.Error(
                        message    = result.reason,
                        isPostSwap = true,
                    )
                }
            }
        }
    }
}

/**
 * Resets [restorePhase] back to [RestorePhase.Idle].
 * Called by the wizard when the user dismisses an error and starts over.
 */
fun MainViewModel.resetRestorePhase() {
    restorePhaseFlow().value = RestorePhase.Idle
}