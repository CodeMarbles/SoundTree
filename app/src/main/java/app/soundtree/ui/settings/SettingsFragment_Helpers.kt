package app.soundtree.ui.settings

import android.net.Uri
import android.provider.DocumentsContract
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.soundtree.R
import app.soundtree.storage.AppVolume
import app.soundtree.storage.StorageVolumeHelper
import app.soundtree.ui.getTotalRecordingTime
import app.soundtree.ui.getLastSessionOpenedAt
import app.soundtree.util.themeColor
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

// ── Cross-cutting helpers ─────────────────────────────────────────────────────

/**
 * Creates a thin horizontal divider consistent with card rows throughout the
 * Storage and Backup sections.
 */
internal fun SettingsFragment.rowDivider(): View {
    return View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).also { lp -> lp.marginStart = 64; lp.marginEnd = 64 }
        setBackgroundColor(requireContext().themeColor(R.attr.colorSurfaceElevated))
    }
}

/**
 * Recursively enables or disables all clickable/focusable children of [group].
 * Used to prevent interaction with stepper rows when their section is toggled off.
 */
internal fun SettingsFragment.setChildrenEnabled(group: ViewGroup, enabled: Boolean) {
    for (i in 0 until group.childCount) {
        val child = group.getChildAt(i)
        child.isEnabled = enabled
        if (child is ViewGroup) setChildrenEnabled(child, enabled)
    }
}

/**
 * Formats a duration in milliseconds as a human-readable gap string.
 * Used by [loadStats] for total recorded time and last-session display.
 */
internal fun SettingsFragment.formatGap(ms: Long): String {
    val hours   = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    return when {
        hours >= 24 -> "${hours / 24}d ${hours % 24}h"
        hours > 0   -> "${hours}h ${minutes}m"
        else        -> "${minutes}m"
    }
}

/**
 * Builds an initial-URI hint for ACTION_OPEN_DOCUMENT_TREE that opens the
 * picker at the root of the specified storage volume.
 *
 * Works on stock Android 8+ and most OEM ROMs. Some Samsung/older OEM
 * ROMs ignore the hint entirely — the picker still opens, just at its
 * default location, so this is always safe to pass.
 *
 * Returns null for the primary volume since the picker already defaults
 * there; only meaningful for removable volumes.
 */
internal fun SettingsFragment.buildVolumeRootUri(volumeUuid: String): Uri? {
    if (volumeUuid == StorageVolumeHelper.UUID_PRIMARY) return null
    return DocumentsContract.buildRootUri(
        "com.android.externalstorage.documents",
        volumeUuid
    )
}

// ── Stats ─────────────────────────────────────────────────────────────────────

/**
 * Populates the Stats display card (Tools tab).
 * Cross-cutting: touches recording count, topic count, total recorded time,
 * last-session timestamp, and total storage — all from different domains.
 */
internal fun SettingsFragment.loadStats() {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

            // Total recorded time + last session (one-shot suspends)
            launch {
                val totalMs = viewModel.getTotalRecordingTime()
                binding.groupStatsDisplay.tvTotalRecordedTime.text =
                    if (totalMs > 0) formatGap(totalMs)
                    else getString(R.string.common_placeholder_empty)

                val lastOpenedAt = viewModel.getLastSessionOpenedAt()
                binding.groupStatsDisplay.tvLastOpened.text = if (lastOpenedAt != null) {
                    getString(R.string.settings_label_time_ago, formatGap(System.currentTimeMillis() - lastOpenedAt))
                } else {
                    getString(R.string.settings_label_first_use)
                }
            }

            // Recording count — reactive, updates if user records while Settings is open
            launch {
                viewModel.allRecordings.collect { recordings ->
                    binding.groupStatsDisplay.tvRecordingCount.text = recordings.size.toString()
                }
            }

            // Topic count — reactive
            launch {
                viewModel.allTopics.collect { topics ->
                    binding.groupStatsDisplay.tvTopicCount.text = topics.size.toString()
                }
            }

            // Total storage — reactive, reuses the same flow that drives
            // the per-volume rows in the Storage card
            launch {
                viewModel.storageUsageByVolume.collect { usageMap ->
                    val totalBytes = usageMap.values.sum()
                    binding.groupStatsDisplay.tvStatsTotalStorage.text =
                        if (totalBytes > 0) AppVolume.formatBytes(totalBytes)
                        else getString(R.string.common_placeholder_empty)
                }
            }
        }
    }
}