package app.soundtree.ui.settings

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.soundtree.R
import app.soundtree.storage.AppVolume
import app.soundtree.ui.getDbPruneCount
import app.soundtree.ui.getDbPruneEnabled
import app.soundtree.ui.setDbPruneCount
import app.soundtree.ui.setDbPruneEnabled
import app.soundtree.ui.setDefaultStorageUuid
import app.soundtree.ui.setVerboseBackupLogging
import app.soundtree.util.themeColor
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

// ── Storage ───────────────────────────────────────────────────────────────────

internal fun SettingsFragment.setupStorageSection() {
    // ── Volume list + usage ───────────────────────────────────────────────────
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            combine(
                viewModel.storageVolumes,
                viewModel.storageUsageByVolume,
                viewModel.defaultStorageUuid
            ) { volumes, usageMap, selectedUuid ->
                Triple(volumes, usageMap, selectedUuid)
            }.collect { (volumes, usageMap, selectedUuid) ->
                renderStorageVolumes(volumes, usageMap, selectedUuid)
                renderTotalUsed(usageMap)
            }
        }
    }

    // ── Verbose backup logging ────────────────────────────────────────────────
    viewLifecycleOwner.lifecycleScope.launch {
        viewModel.verboseBackupLogging.collect { enabled ->
            binding.groupBackups.switchVerboseBackupLogging.isChecked = enabled
        }
    }
    binding.groupBackups.switchVerboseBackupLogging.setOnCheckedChangeListener { _, checked ->
        viewModel.setVerboseBackupLogging(checked)
    }

    // ── DB snapshot pruning ───────────────────────────────────────────────────
    val switchDbPrune   = binding.groupBackups.switchDbPrune
    val rowDbPruneCount = binding.groupBackups.rowDbPruneCount
    val tvDbPruneCount  = binding.groupBackups.tvDbPruneCount
    val btnMinus        = binding.groupBackups.btnDbPruneMinus
    val btnPlus         = binding.groupBackups.btnDbPrunePlus

    fun refreshPruneCount() {
        tvDbPruneCount.text = viewModel.getDbPruneCount().toString()
    }

    switchDbPrune.isChecked       = viewModel.getDbPruneEnabled()
    rowDbPruneCount.alpha         = if (switchDbPrune.isChecked) 1f else 0.4f
    rowDbPruneCount.isEnabled     = switchDbPrune.isChecked
    refreshPruneCount()

    switchDbPrune.setOnCheckedChangeListener { _, checked ->
        viewModel.setDbPruneEnabled(checked)
        rowDbPruneCount.alpha     = if (checked) 1f else 0.4f
        rowDbPruneCount.isEnabled = checked
    }

    btnMinus.setOnClickListener {
        viewModel.setDbPruneCount(viewModel.getDbPruneCount() - 1)
        refreshPruneCount()
    }

    btnPlus.setOnClickListener {
        viewModel.setDbPruneCount(viewModel.getDbPruneCount() + 1)
        refreshPruneCount()
    }
}

/**
 * Inflates one radio-button row per available storage volume into
 * [storageVolumeContainer]. Clears previous rows on each update so the
 * list stays in sync if volumes change while the Settings tab is open.
 */
internal fun SettingsFragment.renderStorageVolumes(
    volumes: List<AppVolume>,
    usageMap: Map<String, Long>,
    selectedUuid: String
) {
    val container = binding.groupStorageVolumes.storageVolumeContainer
    container.removeAllViews()

    if (volumes.isEmpty()) {
        // Edge case: no volumes found yet (first render before refresh completes).
        val placeholder = TextView(requireContext()).apply {
            text = getString(R.string.settings_msg_detecting_storage)
            setTextColor(requireContext().themeColor(R.attr.colorTextSecondary))
            textSize = 13f
            setPadding(64, 12, 64, 12)
        }
        container.addView(placeholder)
        return
    }

    volumes.forEach { volume ->
        val usedBytes  = usageMap[volume.uuid] ?: 0L
        val isSelected = volume.uuid == selectedUuid

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(64, 20, 64, 20)
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.util.TypedValue().also { tv ->
                requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            }.resourceId)
        }

        val radio = RadioButton(requireContext()).apply {
            isChecked   = isSelected
            isClickable = false
            isFocusable = false
        }

        val textBlock = LinearLayout(requireContext()).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        val tvLabel = TextView(requireContext()).apply {
            text = if (volume.isMounted) volume.label
            else getString(R.string.settings_label_volume_unavailable, volume.label)
            setTextColor(
                if (volume.isMounted) requireContext().themeColor(R.attr.colorTextPrimary)
                else requireContext().themeColor(R.attr.colorTextSecondary)
            )
            textSize = 14f
            if (isSelected) setTypeface(null, Typeface.BOLD)
        }

        val tvUsage = TextView(requireContext()).apply {
            val usedLabel = AppVolume.formatBytes(usedBytes)
            text = if (volume.isMounted)
                getString(R.string.settings_label_volume_usage_mounted, usedLabel, volume.freeLabel())
            else
                getString(R.string.settings_label_volume_usage_offline, usedLabel)
            setTextColor(requireContext().themeColor(R.attr.colorTextSecondary))
            textSize = 12f
            setPadding(0, 2, 0, 0)
        }

        textBlock.addView(tvLabel)
        textBlock.addView(tvUsage)
        row.addView(radio)
        row.addView(textBlock)

        // Only allow selecting mounted volumes.
        if (volume.isMounted) {
            row.setOnClickListener { viewModel.setDefaultStorageUuid(volume.uuid) }
        }

        container.addView(row)

        // Divider between rows (not after last).
        if (volume != volumes.last()) {
            container.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { lp -> lp.marginStart = 64; lp.marginEnd = 64 }
                setBackgroundColor(requireContext().themeColor(R.attr.colorSurfaceElevated))
            })
        }
    }
}

internal fun SettingsFragment.renderTotalUsed(usageMap: Map<String, Long>) {
    val totalBytes = usageMap.values.sum()
    binding.groupStorageVolumes.tvTotalUsed.text =
        if (totalBytes == 0L) getString(R.string.common_placeholder_empty)
        else AppVolume.formatBytes(totalBytes)
}