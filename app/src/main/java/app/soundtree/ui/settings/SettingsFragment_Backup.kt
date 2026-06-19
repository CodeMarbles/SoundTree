package app.soundtree.ui.settings

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.soundtree.R
import app.soundtree.data.entities.BackupLogEntity
import app.soundtree.databinding.ItemBackupAvailableVolumeBinding
import app.soundtree.databinding.ItemBackupLogRowBinding
import app.soundtree.databinding.ViewBackupProgressCardBinding
import app.soundtree.storage.AppVolume
import app.soundtree.ui.BackupTargetUiState
import app.soundtree.ui.BackupUiState
import app.soundtree.ui.cancelBackupForTarget
import app.soundtree.ui.settings.SettingsFragment.Tab
import app.soundtree.util.BackupProgressCalc
import app.soundtree.util.themeColor
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// ── Backup progress card ──────────────────────────────────────────────────────

/**
 * Observes [MainViewModel.backupUiState] and shows/hides the running-job card
 * inside [containerBackupProgressCard].
 *
 * Inflates [ViewBackupProgressCardBinding] once and rebinds it on each emission.
 * The card fades in when a job starts and fades out when all jobs complete.
 */
internal fun SettingsFragment.setupBackupProgressCard() {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                viewModel.backupUiState.collect { state ->
                    renderBackupProgressCard(state)
                }
            }
            launch {
                viewModel.navigateToStorageTab.collect {
                    selectTab(Tab.STORAGE)
                }
            }
        }
    }
}

internal fun SettingsFragment.renderBackupProgressCard(state: BackupUiState) {
    val container  = binding.groupBackups.containerBackupProgressCard
    val primaryJob = state.primaryActive

    if (primaryJob == null) {
        // Animate out if currently visible.
        if (container.visibility == View.VISIBLE) {
            container.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction { container.visibility = View.GONE }
                .start()
        }
        return
    }

    // Inflate the card view once; rebind on subsequent emissions.
    if (backupProgressCardBinding == null) {
        backupProgressCardBinding = ViewBackupProgressCardBinding.inflate(
            layoutInflater, container, false
        )
        container.addView(backupProgressCardBinding!!.root)
    }
    val card = backupProgressCardBinding!!
    val log  = primaryJob.log

    // Header
    card.tvBackupProgressTitle.text =
        getString(R.string.backup_progress_title, log.volumeLabel)
    card.btnCancelBackup.setOnClickListener {
        val targetId = log.backupTargetId ?: return@setOnClickListener
        viewModel.cancelBackupForTarget(targetId)
    }

    // Progress bar — phase-aware single continuous bar.
    //
    // The bar is divided into four slices with fixed boundary points:
    //
    //   0  – 10 %  DB snapshot      (opaque duration; hold at slice midpoint)
    //   10 – 75 %  Recording copy   (byte-based within-slice progress)
    //   75 – 88 %  Metadata export  (file-count-based)
    //   88 – 100%  Waveform sync    (file-count-based)
    //
    // progressFraction is null when currentPhase is null, meaning the job
    // was just inserted and hasn't called updatePhase() yet — show
    // indeterminate until it does. Also null for any unrecognised phase
    // value (forward-compat guard).
    val progressFraction = BackupProgressCalc.fraction(log)

    if (progressFraction != null) {
        val prog = BackupProgressCalc.toProgress(progressFraction)
        card.progressBackup.isIndeterminate = false
        card.progressBackup.setProgress(prog, true)
    } else {
        card.progressBackup.isIndeterminate = true
    }

    // Status line
    card.tvBackupProgressStatus.text =
        primaryJob.latestEventMessage
            ?: getString(R.string.backup_progress_copying)

    // Tally chips
    card.chipCopied.text  = getString(R.string.backup_progress_chip_copied,  log.filesCopied)
    card.chipSkipped.text = getString(R.string.backup_progress_chip_skipped, log.filesSkipped)
    card.chipErrors.text  = if (log.filesFailed == 1)
        getString(R.string.backup_progress_chip_error_singular)
    else
        getString(R.string.backup_progress_chip_errors, log.filesFailed)
    card.chipErrors.setTextColor(
        requireContext().themeColor(
            if (log.filesFailed > 0) R.attr.colorError else R.attr.colorTextSecondary
        )
    )

    // Animate in if newly visible.
    if (container.visibility != View.VISIBLE) {
        container.alpha = 0f
        container.visibility = View.VISIBLE
        container.animate().alpha(1f).setDuration(200).start()
    }
}

// ── Backup section ────────────────────────────────────────────────────────────

internal fun SettingsFragment.setupBackupSection() {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Observe designated targets and available volumes together so
            // a single volume moving between the two lists re-renders atomically.
            launch {
                combine(
                    viewModel.backupTargetUiStates,
                    viewModel.backupAvailableVolumes,
                ) { targets, available -> targets to available }
                    .collect { (targets, available) ->
                        renderBackupTargets(targets)
                        renderBackupAvailable(available)
                    }
            }

            // ── Mini backup log (last 3 runs) + "View all" button ─────────────
            launch {
                viewModel.backupLogs
                    .filterNotNull()
                    .collect { logs -> renderBackupMiniLog(logs) }
            }
        }
    }

    binding.groupBackups.btnViewAllHistory.setOnClickListener {
        BackupLogHistoryDialog()
            .show(childFragmentManager, BackupLogHistoryDialog.TAG)
    }
}

/**
 * Renders the "Designated backup volumes" list.
 * Each row shows label, mount status, last-backup time, and a gear icon
 * that opens [BackupTargetConfigDialog].
 */
internal fun SettingsFragment.renderBackupTargets(targets: List<BackupTargetUiState>) {
    val container = binding.groupBackups.containerBackupTargets
    container.removeAllViews()

    if (targets.isEmpty()) {
        container.addView(TextView(requireContext()).apply {
            text = getString(R.string.settings_backup_no_targets)
            setTextColor(requireContext().themeColor(R.attr.colorTextSecondary))
            textSize = 13f
            setPadding(64, 24, 64, 8)
        })
        return
    }

    targets.forEach { state ->
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(64, 24, 32, 24)
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.util.TypedValue().also { tv ->
                requireContext().theme.resolveAttribute(
                    android.R.attr.selectableItemBackground, tv, true
                )
            }.resourceId)
            setOnClickListener {
                BackupTargetConfigDialog.newInstance(state)
                    .show(childFragmentManager, BackupTargetConfigDialog.TAG)
            }
        }

        val textBlock = LinearLayout(requireContext()).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        textBlock.addView(TextView(requireContext()).apply {
            text = state.displayLabel
            textSize = 14f
            setTextColor(requireContext().themeColor(R.attr.colorTextPrimary))
        })

        textBlock.addView(TextView(requireContext()).apply {
            text = buildBackupSubtitle(state)
            textSize = 12f
            setTextColor(requireContext().themeColor(R.attr.colorTextSecondary))
            setPadding(0, 2, 0, 0)
        })

        // Gear icon — tappable affordance hint; row click handles the action.
        val ivGear = android.widget.ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_gear)
            setColorFilter(requireContext().themeColor(R.attr.colorTextSecondary))
            val iconPx = (20 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(iconPx, iconPx).also {
                it.marginStart = 16
            }
            isClickable = false
        }

        row.addView(textBlock)
        row.addView(ivGear)
        container.addView(row)

        if (state != targets.last()) container.addView(rowDivider())
    }
}

/**
 * Renders the "Available to Add" list — mounted removable volumes not yet
 * designated as targets.
 *
 * Always visible. Shows a placeholder when no volumes are available rather
 * than hiding the section, so the user understands the feature exists even
 * when no drives are currently connected.
 */
internal fun SettingsFragment.renderBackupAvailable(available: List<AppVolume>) {
    val container = binding.groupBackups.containerBackupAvailable
    container.removeAllViews()

    if (available.isEmpty()) {
        container.addView(TextView(requireContext()).apply {
            text = getString(R.string.settings_backup_no_available)
            setTextColor(requireContext().themeColor(R.attr.colorTextSecondary))
            textSize = 13f
            setPadding(64, 24, 64, 8)
        })
        return
    }

    available.forEach { volume ->
        val itemBinding = ItemBackupAvailableVolumeBinding.inflate(
            layoutInflater, container, false
        )
        itemBinding.tvVolumeName.text = volume.label
        itemBinding.tvVolumeInfo.text = volume.freeLabel()
        itemBinding.btnAddAsTarget.setOnClickListener {
            pendingBackupVolumeUuid = volume.uuid
            openDocumentTree.launch(buildVolumeRootUri(volume.uuid))
        }
        container.addView(itemBinding.root)
    }
}

/**
 * Renders the last 3 completed backup runs in the Settings backup card.
 *
 * Shows a placeholder when there are no runs yet. Each row uses
 * [ItemBackupLogRowBinding] (showVolumeLabel = true) and opens
 * [BackupLogDetailDialog] on tap.
 */
internal fun SettingsFragment.renderBackupMiniLog(logs: List<BackupLogEntity>) {
    val container = binding.groupBackups.containerBackupLog
    container.removeAllViews()

    val recent = logs.take(3)

    if (recent.isEmpty()) {
        container.addView(TextView(requireContext()).apply {
            text = getString(R.string.backup_log_history_empty)
            setTextColor(requireContext().themeColor(R.attr.colorTextSecondary))
            textSize = 13f
            setPadding(64, 24, 64, 8)
        })
        return
    }

    recent.forEachIndexed { index, log ->
        val rowBinding = ItemBackupLogRowBinding.inflate(layoutInflater, container, false)
        rowBinding.bindLog(log, showVolumeLabel = true, context = requireContext())
        rowBinding.root.setOnClickListener {
            BackupLogDetailDialog.newInstance(log)
                .show(childFragmentManager, BackupLogDetailDialog.TAG)
        }
        container.addView(rowBinding.root)
        if (index < recent.lastIndex) container.addView(rowDivider())
    }
}

/**
 * Builds the subtitle line for a backup target row.
 * Shows mount status, trigger modes enabled, and last backup time.
 */
internal fun SettingsFragment.buildBackupSubtitle(state: BackupTargetUiState): String {
    val parts = mutableListOf<String>()

    if (!state.isMounted) {
        parts += getString(R.string.settings_backup_status_not_connected)
    } else {
        val triggers = mutableListOf<String>()
        if (state.entity.onConnectEnabled) triggers += getString(R.string.settings_backup_trigger_on_connect)
        if (state.entity.scheduledEnabled) triggers += getString(R.string.settings_backup_trigger_scheduled, state.entity.intervalHours)
        if (triggers.isNotEmpty()) parts += triggers.joinToString(" · ")
    }

    val lastBackup = state.entity.lastBackupAt
    if (lastBackup != null) {
        parts += getString(R.string.settings_backup_last_backed_up, formatRelativeTime(lastBackup))
    } else {
        parts += getString(R.string.settings_backup_never_backed_up)
    }

    return parts.joinToString("  ·  ")
}

/** Formats an epoch-ms timestamp as a relative or absolute time string. */
internal fun SettingsFragment.formatRelativeTime(epochMs: Long): String {
    val deltaMs = System.currentTimeMillis() - epochMs
    return when {
        deltaMs < TimeUnit.MINUTES.toMillis(2)  -> getString(R.string.settings_backup_time_just_now)
        deltaMs < TimeUnit.HOURS.toMillis(1)    -> getString(R.string.settings_backup_time_minutes_ago, TimeUnit.MILLISECONDS.toMinutes(deltaMs))
        deltaMs < TimeUnit.HOURS.toMillis(48)   -> getString(R.string.settings_backup_time_hours_ago, TimeUnit.MILLISECONDS.toHours(deltaMs))
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMs))
    }
}