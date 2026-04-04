package com.treecast.app.ui

import android.content.Intent
import com.treecast.app.ui.MainActivity.Companion.EXTRA_ORPHAN_CORRUPT_PATHS
import com.treecast.app.ui.MainActivity.Companion.EXTRA_ORPHAN_PLAYABLE_DURATIONS_MS
import com.treecast.app.ui.MainActivity.Companion.EXTRA_ORPHAN_PLAYABLE_PATHS
import com.treecast.app.ui.MainActivity.Companion.EXTRA_SAVED_RECORDING_ID
import com.treecast.app.ui.MainActivity.Companion.EXTRA_SAVED_TOPIC_ID
import com.treecast.app.ui.recovery.OrphanRecoveryDialogFragment
import com.treecast.app.util.OrphanRecording
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────
// MainActivity_IntentHandling.kt
//
// Extension functions on MainActivity covering intent-driven entry points
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Navigates to the Library and selects the recording identified by the
 * extras in [intent]. Mirrors the post-save navigation that
 * [com.treecast.app.ui.record.RecordFragment.stopAndSave] performs when
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
 * Reads orphan-recording extras placed by [com.treecast.app.ui.SplashActivity],
 * publishes the results to [MainViewModel.setOrphanResults] unconditionally
 * (so the Settings card always shows accurate counts), then shows
 * [OrphanRecoveryDialogFragment] only when the list is non-empty.
 *
 * Only shows the dialog on a genuine cold start — not when the activity is
 * being recreated due to a configuration change (e.g. a theme switch on
 * Android 10), which would re-surface the dialog after the user dismissed it.
 *
 * Called once from [MainActivity.onCreate].
 */
internal fun MainActivity.checkAndShowOrphanRecovery() {
    val playablePaths     = intent.getStringArrayListExtra(EXTRA_ORPHAN_PLAYABLE_PATHS).orEmpty()
    val playableDurations = intent.getLongArrayExtra(EXTRA_ORPHAN_PLAYABLE_DURATIONS_MS) ?: LongArray(0)
    val corruptPaths      = intent.getStringArrayListExtra(EXTRA_ORPHAN_CORRUPT_PATHS).orEmpty()

    val orphans = buildList {
        playablePaths.forEachIndexed { i, path ->
            add(
                OrphanRecording(
                    file           = File(path),
                    suggestedTitle = "",   // re-derived inside the dialog
                    durationMs     = playableDurations.getOrElse(i) { 0L },
                )
            )
        }
        corruptPaths.forEach { path ->
            add(
                OrphanRecording(
                    file           = File(path),
                    suggestedTitle = "",
                    durationMs     = 0L,
                )
            )
        }
    }

    // Always publish — SettingsFragment observes this to show counts/sizes.
    viewModel.setOrphanResults(orphans)

    if (orphans.isEmpty()) return

    if (!isRestoredFromState) {
        OrphanRecoveryDialogFragment
            .newInstance(orphans)
            .show(supportFragmentManager, OrphanRecoveryDialogFragment.TAG)
    }
}