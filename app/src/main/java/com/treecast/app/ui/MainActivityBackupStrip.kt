package com.treecast.app.ui

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.treecast.app.R
import com.treecast.app.data.entities.BackupLogEntity
import com.treecast.app.ui.MainActivity.Companion.PAGE_SETTINGS
import com.treecast.app.util.themeColor
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// MainActivityBackupStrip.kt
//
// Extension functions on MainActivity covering the title-bar backup status strip.
//   • setupBackupStatusStrip() — wires the strip to backupStripState Flow,
//                                drives Running/Completed content and visibility,
//                                and tap-to-navigate / X-dismiss behaviour.
//
// The strip background and text color are set per-state using semantic theme
// attributes (colorStatusRunning/Success/Warning/Failed Background/Foreground),
// making it theme-aware automatically.
//
// Pass 1B: all strip states now show volume identity — "Label (UUID)" when the
// volume has a name, or just "UUID" when it doesn't. The helper volumeStripLabel()
// encapsulates this rule so it is applied consistently across Running, Completed,
// and (via BackupWorker) the system notification.
// ─────────────────────────────────────────────────────────────────────────────

internal fun MainActivity.setupBackupStatusStrip() {
    val strip = binding.backupStatusStrip

    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.backupStripState.collect { state ->
                when (state) {

                    is BackupStripState.Hidden -> {
                        strip.root.visibility = View.GONE
                    }

                    is BackupStripState.Running -> {
                        val log = state.primary.log
                        val bg = themeColor(R.attr.colorStatusRunningBackground)
                        val fg = themeColor(R.attr.colorStatusRunningForeground)

                        strip.root.setBackgroundColor(bg)
                        strip.tvStripStatus.setTextColor(fg)
                        strip.tvStripBadge.setTextColor(fg)

                        strip.tvStripStatus.text = getString(
                            R.string.backup_strip_running,
                            volumeStripLabel(log.volumeLabel, log.volumeUuid),
                        )
                        strip.tvStripBadge.visibility =
                            if (state.extraCount > 0) View.VISIBLE else View.GONE
                        strip.tvStripBadge.text =
                            getString(R.string.backup_strip_extra_jobs, state.extraCount)

                        strip.progressStrip.visibility = View.VISIBLE
                        val estimatedTotal = viewModel.backupLogs.value
                            .firstOrNull { it.volumeUuid == log.volumeUuid && it.status != null }
                            ?.totalBytesOnDestination ?: 0L
                        if (estimatedTotal > 0 && log.bytesCopied > 0) {
                            val prog = ((log.bytesCopied.toFloat() / estimatedTotal) * 10_000)
                                .toInt().coerceIn(0, 10_000)
                            strip.progressStrip.isIndeterminate = false
                            strip.progressStrip.setProgress(prog, true)
                        } else {
                            strip.progressStrip.isIndeterminate = true
                        }

                        strip.btnStripDismiss.visibility = View.GONE
                        strip.root.visibility = View.VISIBLE
                    }

                    is BackupStripState.Completed -> {
                        val log = state.log
                        val volId = volumeStripLabel(log.volumeLabel, log.volumeUuid)

                        val (bg, fg) = when (log.status) {
                            BackupLogEntity.BackupStatus.SUCCESS ->
                                themeColor(R.attr.colorStatusSuccessBackground) to
                                        themeColor(R.attr.colorStatusSuccessForeground)
                            BackupLogEntity.BackupStatus.PARTIAL ->
                                themeColor(R.attr.colorStatusWarningBackground) to
                                        themeColor(R.attr.colorStatusWarningForeground)
                            else ->
                                themeColor(R.attr.colorStatusFailedBackground) to
                                        themeColor(R.attr.colorStatusFailedForeground)
                        }

                        strip.root.setBackgroundColor(bg)
                        strip.tvStripStatus.setTextColor(fg)
                        strip.btnStripDismiss.setTextColor(fg)

                        strip.tvStripStatus.text = when (log.status) {
                            BackupLogEntity.BackupStatus.SUCCESS ->
                                getString(R.string.backup_strip_success, volId, log.filesCopied)
                            BackupLogEntity.BackupStatus.PARTIAL ->
                                getString(R.string.backup_strip_partial, volId, log.filesFailed)
                            else ->
                                getString(R.string.backup_strip_failed, volId)
                        }
                        strip.tvStripBadge.visibility = View.GONE
                        strip.progressStrip.visibility = View.GONE

                        strip.btnStripDismiss.visibility = View.VISIBLE
                        strip.btnStripDismiss.setOnClickListener {
                            viewModel.dismissBackupStrip(log.id)
                        }

                        strip.root.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    // Tap anywhere on the strip (X button consumes its own event and won't bubble)
    strip.stripNavArea.setOnClickListener {
        binding.viewPager.setCurrentItem(PAGE_SETTINGS, false)
        viewModel.requestNavigateToStorageTab()
    }
}

/**
 * Formats a volume identity string for the title-bar strip and notification.
 *
 * When the volume has a distinct OS-assigned name (i.e. [volumeLabel] differs
 * from [volumeUuid]), returns "Label (UUID)" so both the human-readable name
 * and the stable identifier are visible at a glance.
 *
 * When the volume has no name — which happens when [BackupWorker] falls back
 * to the raw UUID as the label — returns just the UUID to avoid redundancy.
 */
internal fun volumeStripLabel(volumeLabel: String, volumeUuid: String): String =
    if (volumeLabel != volumeUuid) "$volumeLabel ($volumeUuid)" else volumeUuid