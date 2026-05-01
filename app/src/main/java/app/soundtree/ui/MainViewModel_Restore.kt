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
import app.soundtree.ui.restore.FileCategory
import app.soundtree.ui.restore.FileCounters
import app.soundtree.ui.restore.FileLogEntry
import app.soundtree.util.DatabaseRestoreManager
import app.soundtree.worker.BackupWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Restore progress state ────────────────────────────────────────────────────

enum class MilestoneState { PENDING, RUNNING, SUCCESS, FAILURE }

/**
 * UI representation of a single milestone row.
 *
 * [state]     — drives the icon and dimming in the milestone board.
 * [timestamp] — formatted "HH:mm:ss", empty until the milestone resolves.
 * [detail]    — optional extra text shown beneath the label (e.g. file count).
 */
data class MilestoneEntry(
    val label: String,
    val state: MilestoneState = MilestoneState.PENDING,
    val timestamp: String = "",
    val detail: String? = null,
)

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
        // ── Progress bar ───────────────────────────────────────────────────────
        val label: String,
        val current: Int = 0,
        val total: Int = 0,

        // ── Milestone board — always 4 entries, ordered by sequence ───────────
        // Starts as all-PENDING; updated as each milestone fires.
        val milestones: List<MilestoneEntry> = INITIAL_MILESTONES,

        // ── Per-category file events (unbounded, append-only) ─────────────────
        val recordingEvents: List<FileLogEntry> = emptyList(),
        val waveformEvents: List<FileLogEntry> = emptyList(),

        // ── Per-category outcome counters ─────────────────────────────────────
        val recordingCounts: FileCounters = FileCounters(),
        val waveformCounts: FileCounters = FileCounters(),

        // ── Section lifecycle flags ───────────────────────────────────────────
        // Used by the fragment to drive section header spinner / chevron state
        // and to trigger auto-collapse on clean completion.
        val recordingsRunning: Boolean = false,
        val recordingsComplete: Boolean = false,
        val waveformsRunning: Boolean = false,
        val waveformsComplete: Boolean = false,
    ) : RestorePhase() {
        companion object {
            // Ordered to match DatabaseRestoreManager.Milestone ordinal sequence.
            val INITIAL_MILESTONES = listOf(
                MilestoneEntry("Safety snapshot"),
                MilestoneEntry("Metadata export"),
                MilestoneEntry("Database restored"),
                MilestoneEntry("Recording paths updated"),
            )

            // Maps Milestone enum to index in the milestones list above.
            fun milestoneIndex(m: DatabaseRestoreManager.Milestone) = m.ordinal
        }
    }

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
    val phaseFlow  = restorePhaseFlow()
    val timeFmt    = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    viewModelScope.launch {
        WorkManager.getInstance(getApplication())
            .cancelAllWorkByTag(BackupWorker.TAG)

        val backupRootDir = withContext(Dispatchers.IO) {
            DocumentFile.fromTreeUri(getApplication(), android.net.Uri.parse(backupRootDirUri))
        }

        if (backupRootDir == null) {
            phaseFlow.value = RestorePhase.Error(
                "Could not open the backup directory. The permission may have been revoked."
            )
            return@launch
        }

        // ── Mutable local state — mutated on IO thread, snapshotted into
        // RestorePhase.Running on every onProgress or onMilestone call.
        // Single-threaded access guaranteed: restore runs on one IO coroutine.

        var currentRunning = RestorePhase.Running(label = "Starting restore…")

        // Convenience: emit a new Running snapshot with updated fields.
        fun emit(update: RestorePhase.Running.() -> RestorePhase.Running) {
            currentRunning = currentRunning.update()
            phaseFlow.value = currentRunning
        }

        val result = DatabaseRestoreManager.restore(
            context       = getApplication(),
            backupFile    = backupFile,
            backupRootDir = backupRootDir,

            onProgress = { label, current, total ->
                emit {
                    copy(
                        label   = label,
                        current = current,
                        total   = total,
                        // Mark which section is actively running based on label.
                        // This is intentionally label-driven rather than a separate
                        // callback — the label already encodes the current phase.
                        recordingsRunning = label.startsWith("Copying recordings"),
                        waveformsRunning  = label.startsWith("Restoring waveforms"),
                    )
                }
            },

            onMilestone = { milestone, success, detail ->
                val idx       = RestorePhase.Running.milestoneIndex(milestone)
                val timestamp = timeFmt.format(Date())
                val state     = if (success) MilestoneState.SUCCESS else MilestoneState.FAILURE

                emit {
                    val updated = milestones.toMutableList().also { list ->
                        list[idx] = list[idx].copy(
                            state     = state,
                            timestamp = timestamp,
                            detail    = detail,
                        )
                    }

                    // When PATH_REMAP resolves, the recordings section is complete.
                    val recComplete = milestone == DatabaseRestoreManager.Milestone.PATH_REMAP
                            || recordingsComplete

                    copy(
                        milestones         = updated,
                        recordingsComplete = recComplete,
                        recordingsRunning  = if (recComplete) false else recordingsRunning,
                    )
                }
            },

            onFileEvent = { category, type, filename ->
                // Append to the appropriate event list and increment counters.
                // The next onProgress call will snapshot this into the StateFlow,
                // so we don't emit here — avoids one StateFlow update per file.
                val entry = FileLogEntry(category, type, filename)
                currentRunning = when (category) {
                    FileCategory.RECORDINGS -> currentRunning.copy(
                        recordingEvents = currentRunning.recordingEvents + entry,
                        recordingCounts = currentRunning.recordingCounts.increment(type),
                    )
                    FileCategory.WAVEFORMS -> currentRunning.copy(
                        waveformEvents = currentRunning.waveformEvents + entry,
                        waveformCounts = currentRunning.waveformCounts.increment(type),
                    )
                }
            },
        )

        // Mark waveform section complete once restore() returns (success or not).
        emit { copy(waveformsRunning = false, waveformsComplete = true) }

        when (result) {
            is DatabaseRestoreManager.Result.Success -> {
                withContext(Dispatchers.Main) {
                    DatabaseRestoreManager.scheduleRestartAndExit(getApplication())
                }
            }
            is DatabaseRestoreManager.Result.NoDbFound -> {
                withContext(Dispatchers.Main) {
                    phaseFlow.value = RestorePhase.Error(
                        "No database file was found in the snapshot."
                    )
                }
            }
            is DatabaseRestoreManager.Result.FailedPreFlight -> {
                withContext(Dispatchers.Main) {
                    phaseFlow.value = RestorePhase.Error(result.reason, isPostSwap = false)
                }
            }
            is DatabaseRestoreManager.Result.FailedPostSwap -> {
                withContext(Dispatchers.Main) {
                    phaseFlow.value = RestorePhase.Error(result.reason, isPostSwap = true)
                }
            }
        }
    }
}

/** Increments the counter corresponding to [type]. */
private fun FileCounters.increment(type: DatabaseRestoreManager.FileEventType) = when (type) {
    DatabaseRestoreManager.FileEventType.COPIED  -> copy(copied  = copied  + 1)
    DatabaseRestoreManager.FileEventType.SKIPPED -> copy(skipped = skipped + 1)
    DatabaseRestoreManager.FileEventType.FAILED  -> copy(failed  = failed  + 1)
}

/**
 * Resets [restorePhase] back to [RestorePhase.Idle].
 * Called by the wizard when the user dismisses an error and starts over.
 */
fun MainViewModel.resetRestorePhase() {
    restorePhaseFlow().value = RestorePhase.Idle
}