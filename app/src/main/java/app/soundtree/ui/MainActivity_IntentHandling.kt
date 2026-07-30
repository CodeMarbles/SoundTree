package app.soundtree.ui

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import app.soundtree.ui.MainActivity.Companion.EXTRA_SAVED_RECORDING_ID
import app.soundtree.ui.MainActivity.Companion.EXTRA_SAVED_TOPIC_ID
import app.soundtree.ui.recovery.OrphanRecoveryDialogFragment
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// MainActivity_IntentHandling.kt
//
// Extension functions on MainActivity covering intent-driven entry points
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Navigates to the Library and selects the recording identified by the
 * extras in [intent]. Mirrors the post-save navigation that
 * [app.soundtree.ui.record.RecordFragment.stopAndSave] performs when
 * the app is in the foreground.
 *
 * Only called when [EXTRA_SAVED_RECORDING_ID] is present; the topic extra
 * is optional (null means the recording landed in Unsorted).
 */
internal fun MainActivity.handleNotificationSaveIntent(intent: Intent) {
    val recordingId = intent.getLongExtra(EXTRA_SAVED_RECORDING_ID, -1L)
    if (recordingId == -1L) return

    val topicId = if (intent.hasExtra(EXTRA_SAVED_TOPIC_ID))
        intent.getLongExtra(EXTRA_SAVED_TOPIC_ID, -1L).takeIf { it != -1L }
    else null

    viewModel.selectRecording(recordingId)
    navigateToLibraryForRecording(topicId)
}

/**
 * Observes [MainViewModel.orphanRecordings] and shows
 * [OrphanRecoveryDialogFragment] the first time the scan result arrives
 * with a non-empty list.
 *
 * The scan itself runs in [MainViewModel.init] on a background coroutine;
 * this function just wires up the observer and guards against re-showing
 * the dialog on a configuration change.
 *
 * Called once from [MainActivity.onCreate].
 */
internal fun MainActivity.checkAndShowOrphanRecovery() {
    if (isRestoredFromState) return  // Don't re-show on config change

    lifecycleScope.launch {
        // Wait for the scan result (emits once on init, again after rescanOrphans())
        viewModel.orphanRecordings
            .filter { it.isNotEmpty() }
            .take(1)
            .collect { orphans ->
                // Guard again inside collect — config change can race the emission
                if (!isRestoredFromState && !isFinishing) {
                    OrphanRecoveryDialogFragment
                        .newInstance(orphans)
                        .show(supportFragmentManager, OrphanRecoveryDialogFragment.TAG)
                }
            }
    }
}