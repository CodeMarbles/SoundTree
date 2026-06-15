package app.soundtree.ui.settings

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import app.soundtree.R
import app.soundtree.data.entities.BackupLogEntity
import app.soundtree.databinding.FragmentSettingsBinding
import app.soundtree.databinding.ItemBackupAvailableVolumeBinding
import app.soundtree.databinding.ItemBackupLogRowBinding
import app.soundtree.databinding.ViewBackupProgressCardBinding
import app.soundtree.service.RecordingService
import app.soundtree.storage.AppVolume
import app.soundtree.ui.BackupTargetUiState
import app.soundtree.ui.BackupUiState
import app.soundtree.ui.MainViewModel
import app.soundtree.ui.ProcessingStatus
import app.soundtree.ui.addBackupTarget
import app.soundtree.ui.cancelBackupForVolume
import app.soundtree.ui.cancelWaveformProcessing
import app.soundtree.ui.clearCompletedWaveformJobs
import app.soundtree.ui.getDbPruneCount
import app.soundtree.ui.getDbPruneEnabled
import app.soundtree.ui.labelForJob
import app.soundtree.ui.migrateRecordingStructure
import app.soundtree.ui.recovery.OrphanRecoveryDialogFragment
import app.soundtree.ui.refreshStorageVolumes
import app.soundtree.ui.reprocessAllWaveforms
import app.soundtree.ui.restore.RestoreWizardDialogFragment
import app.soundtree.ui.setDbPruneCount
import app.soundtree.ui.setDbPruneEnabled
import app.soundtree.ui.setDefaultStorageUuid
import app.soundtree.ui.setDevOptions
import app.soundtree.ui.setFutureMode
import app.soundtree.ui.setSimulateWaveformLoading
import app.soundtree.ui.setVerboseBackupLogging
import app.soundtree.ui.tickProcessingRefresh
import app.soundtree.util.BackupProgressCalc
import app.soundtree.util.OrphanRecording
import app.soundtree.util.themeColor
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    internal val binding get() = _binding!!
    internal val viewModel: MainViewModel by activityViewModels()

    // ── Tab state ─────────────────────────────────────────────────────
    private enum class Tab { DISPLAY, BEHAVIOR, STORAGE, TOOLS }
    private var activeTab = Tab.DISPLAY

    /**
     * Stores the UUID of the volume the user tapped "Add" on while the SAF
     * directory picker is open. Cleared when the picker returns.
     */
    private var pendingBackupVolumeUuid: String? = null

    private var backupProgressCardBinding: ViewBackupProgressCardBinding? = null

    /**
     * SAF directory picker launcher.
     *
     * Opened when the user taps "Add as backup target" on an available volume.
     * On a successful result:
     *   1. Persist read+write permission so it survives app restart.
     *   2. Hand the volume UUID + URI to the ViewModel to insert the target row.
     *
     * A null result (user cancelled) is silently ignored.
     */
    private val openDocumentTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val volumeUuid = pendingBackupVolumeUuid ?: return@registerForActivityResult
        pendingBackupVolumeUuid = null
        if (uri == null) return@registerForActivityResult

        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        viewModel.addBackupTarget(volumeUuid, uri.toString())
    }

    private val openDocumentTreeForRestore = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult

        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )

        RestoreWizardDialogFragment.newInstance(backupRootUri = uri.toString())
            .show(parentFragmentManager, RestoreWizardDialogFragment.TAG)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTabs()
        setupHeader()
        setupTheme()
        setupWaveformStyleSettings()
        setupPlayheadVis()
        setupPlaybackMemory()
        setupLayoutSection()
        setupRecordingWidgetSection()
        setupPlaybackWidgetSection()
        setupPlaybackSettings()
        setupStorageSection()
        setupBackupProgressCard()
        setupRecordingRecoverySection()
        setupBackupSection()
        setupRestoreSection()
        setupProcessingSection()
        setupMigrationSection()
        setupDevOptionsSection()
        loadStats()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        backupProgressCardBinding = null
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStorageVolumes()
    }

    // ── Tab management ────────────────────────────────────────────────

    // Change from local fun inside setupTabs() to a private member function:
    private fun selectTab(tab: Tab) {
        activeTab = tab
        val scrolls = mapOf(
            Tab.DISPLAY  to binding.scrollDisplay,
            Tab.BEHAVIOR to binding.scrollBehavior,
            Tab.STORAGE  to binding.scrollStorage,
            Tab.TOOLS    to binding.scrollTools,
        )
        scrolls.forEach { (t, scroll) ->
            scroll.visibility = if (t == tab) View.VISIBLE else View.GONE
        }
        // update tab pill visuals — move the existing styling logic here too
        listOf(
            binding.tabDisplay  to Tab.DISPLAY,
            binding.tabBehavior to Tab.BEHAVIOR,
            binding.tabStorage  to Tab.STORAGE,
            binding.tabTools    to Tab.TOOLS,
        ).forEach { (view, t) ->
            val isActive = t == tab
            view.isSelected = isActive
            view.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
            view.setTextColor(
                if (isActive) requireContext().themeColor(R.attr.colorTextPrimary)
                else          requireContext().themeColor(R.attr.colorTextSecondary)
            )
            view.background = if (isActive) android.graphics.drawable.GradientDrawable().apply {
                shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = resources.getDimension(R.dimen.settings_card_corner_radius) -
                        resources.displayMetrics.density * 3f
                setColor(requireContext().themeColor(R.attr.colorSurfaceElevated))
            } else null
        }
    }

    private fun setupTabs() {
        val tabs = listOf(
            binding.tabDisplay  to Tab.DISPLAY,
            binding.tabBehavior to Tab.BEHAVIOR,
            binding.tabStorage  to Tab.STORAGE,
            binding.tabTools    to Tab.TOOLS
        )

        tabs.forEach { (tv, tab) ->
            tv.setOnClickListener { selectTab(tab) }
        }

        // Apply initial state
        selectTab(Tab.DISPLAY)
    }

    private fun setupHeader() {
        binding.tvAppIdentity.text = getString(R.string.app_name)
    }

    private fun setupRecordingRecoverySection() {
        binding.groupRecordingRecovery.btnReviewOrphans.setOnClickListener {
            OrphanRecoveryDialogFragment
                .newInstance(viewModel.orphanRecordings.value)
                .show(parentFragmentManager, OrphanRecoveryDialogFragment.TAG)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.orphanRecordings.collect { orphans ->
                    renderOrphanSummary(orphans)
                }
            }
        }
    }

    private fun renderOrphanSummary(orphans: List<OrphanRecording>) {
        val recoverable   = orphans.filter { it.isPlayable }
        val unrecoverable = orphans.filter { !it.isPlayable }
        binding.groupRecordingRecovery.tvOrphanRecoverableSummary.text = formatOrphanSummary(recoverable)
        binding.groupRecordingRecovery.tvOrphanCorruptSummary.text     = formatOrphanSummary(unrecoverable)
    }

    private fun formatOrphanSummary(orphans: List<OrphanRecording>): String {
        if (orphans.isEmpty()) return "None"
        val count      = orphans.size
        val totalBytes = orphans.sumOf { it.file.length() }
        val label      = if (count == 1) "1 recording" else "$count recordings"
        return "$label · ${AppVolume.formatBytes(totalBytes)}"
    }

    private fun setupBackupSection() {
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

                // ── Mini backup log (last 3 runs) + "View all" button ─────────
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

    private fun setupRestoreSection() {
        binding.groupRestore.btnRestoreFromBackup.setOnClickListener {
            openDocumentTreeForRestore.launch(null)
        }
    }

    /**
     * Renders the "Designated backup volumes" list.
     * Each row shows label, mount status, last-backup time, and a gear icon
     * that opens [BackupTargetConfigDialog].
     */
    private fun renderBackupTargets(targets: List<BackupTargetUiState>) {
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
                isClickable  = true
                isFocusable  = true
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
                orientation = LinearLayout.VERTICAL
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
     *
     * Each volume is shown as an [ItemBackupAvailableVolumeBinding] card with
     * a subtle stroke border, volume name, free-space annotation, and an
     * "Add as Target" outlined button.
     */
    private fun renderBackupAvailable(available: List<AppVolume>) {
        // Section is always visible — no visibility toggle.
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
    private fun renderBackupMiniLog(logs: List<BackupLogEntity>) {
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
            val rowBinding = ItemBackupLogRowBinding.inflate(
                layoutInflater, container, false
            )
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
    private fun buildBackupSubtitle(state: BackupTargetUiState): String {
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
    private fun formatRelativeTime(epochMs: Long): String {
        val deltaMs = System.currentTimeMillis() - epochMs
        return when {
            deltaMs < TimeUnit.MINUTES.toMillis(2)  -> getString(R.string.settings_backup_time_just_now)
            deltaMs < TimeUnit.HOURS.toMillis(1)    -> getString(R.string.settings_backup_time_minutes_ago, TimeUnit.MILLISECONDS.toMinutes(deltaMs))
            deltaMs < TimeUnit.HOURS.toMillis(48)   -> getString(R.string.settings_backup_time_hours_ago, TimeUnit.MILLISECONDS.toHours(deltaMs))
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMs))
        }
    }

    // ── Backup progress card ──────────────────────────────────────────────────

    /**
     * Observes [MainViewModel.backupUiState] and shows/hides the running-job card
     * inside [containerBackupProgressCard].
     *
     * Inflates [ViewBackupProgressCardBinding] once and rebinds it on each emission.
     * The card fades in when a job starts and fades out when all jobs complete.
     */
    private fun setupBackupProgressCard() {
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

    private fun renderBackupProgressCard(state: BackupUiState) {
        val container = binding.groupBackups.containerBackupProgressCard
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
            viewModel.cancelBackupForVolume(log.volumeUuid)
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
        // indeterminate until it does.  Also null for any unrecognised phase
        // value (forward-compat guard).
        val progressFraction = BackupProgressCalc.fraction(log)

        // The setProgress / isIndeterminate block below is unchanged:
        if (progressFraction != null) {
            val prog = BackupProgressCalc.toProgress(progressFraction)
            card.progressBackup.isIndeterminate = false
            card.progressBackup.setProgress(prog, true)
        } else {
            card.progressBackup.isIndeterminate = true
        }

        if (progressFraction != null) {
            val prog = (progressFraction * 10_000).toInt().coerceIn(0, 10_000)
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

    // ── Waveform processing output state ─────────────────────────────────────────
    private var processingOutputExpanded = true
    private lateinit var recentJobsAdapter: WaveformJobAdapter

    private fun setupProcessingSection() {
        recentJobsAdapter = WaveformJobAdapter()
        binding.groupProcessingJobs.rvRecentJobs.apply {
            layoutManager = LinearLayoutManager(requireContext()).also {
                // Newest items are prepended, so we want the list to scroll to
                // position 0 (top) on each update — no reverseLayout needed.
            }
            adapter = recentJobsAdapter
        }

        // Confirmation dialog guards the destructive button.
        binding.groupProcessingJobs.btnReprocessWaveforms.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_tools_regenerate_confirm_title)
                .setMessage(R.string.settings_tools_regenerate_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_tools_regenerate_confirm_action) { _, _ ->
                    viewModel.reprocessAllWaveforms()
                }
                .show()
        }

        binding.groupProcessingJobs.btnCancelWaveforms.setOnClickListener {
            // Optimistically hide the cancel button — WorkManager cancellation is async
            // and the flow won't confirm until WM emits the cancelled state.
            binding.groupProcessingJobs.btnCancelWaveforms.visibility = View.GONE
            viewModel.cancelWaveformProcessing()
        }

        binding.groupProcessingJobs.btnClearWaveformOutput.setOnClickListener {
            // Optimistically clear the UI immediately — don't wait for the flow.
            binding.groupProcessingJobs.containerRecent.visibility        = View.GONE
            binding.groupProcessingJobs.btnClearWaveformOutput.visibility = View.GONE
            // Then do the actual data work (also triggers a flow emission as confirmation).
            viewModel.clearCompletedWaveformJobs()
        }

        binding.groupProcessingJobs.btnToggleProcessingOutput.setOnClickListener {
            processingOutputExpanded = !processingOutputExpanded
            // Re-render with the current status so visibility updates immediately.
            renderProcessingOutput(processingOutputExpanded)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.processingStatus.collect { status ->
                        renderProcessingStatus(status)
                    }
                }
                launch {
                    while (true) {
                        kotlinx.coroutines.delay(3_000L)
                        viewModel.tickProcessingRefresh()
                    }
                }
            }
        }
    }

    private fun renderProcessingStatus(status: ProcessingStatus) {
        val hasActive  = status.active != null
        val hasPending = status.pending.isNotEmpty()
        val hasRecent  = status.recent.isNotEmpty()
        val isActive   = hasActive || hasPending
        val isIdle     = !hasActive && !hasPending && !hasRecent

        // When we go idle, reset expansion so the next pass starts fresh.
        if (isIdle) processingOutputExpanded = true

        // ── Top-bar controls ──────────────────────────────────────────────────────
        binding.groupProcessingJobs.processingSpinner.visibility     = if (hasActive) View.VISIBLE else View.GONE
        binding.groupProcessingJobs.tvProcessingIdle.visibility      = if (isIdle)   View.VISIBLE else View.GONE
        binding.groupProcessingJobs.btnToggleProcessingOutput.visibility = if (!isIdle) View.VISIBLE else View.GONE

        // Disable the regenerate button while a pass is in flight.
        binding.groupProcessingJobs.btnReprocessWaveforms.isEnabled = !isActive

        // ── Active job row ────────────────────────────────────────────────────────
        binding.groupProcessingJobs.rowActiveJob.visibility = if (hasActive) View.VISIBLE else View.GONE
        status.active?.let { binding.groupProcessingJobs.tvActiveJobTitle.text = viewModel.labelForJob(it) }

        // ── Progress bar ──────────────────────────────────────────────────────────
        val showProgress = status.totalEnqueued > 0
        binding.groupProcessingJobs.pbWaveformProgress.visibility = if (showProgress) View.VISIBLE else View.GONE
        if (showProgress) {
            val progressFraction = status.completedCount.toFloat() / status.totalEnqueued
            binding.groupProcessingJobs.pbWaveformProgress.progress = (progressFraction * 1000).toInt()
        }

        // ── Job counts ────────────────────────────────────────────────────────────
        binding.groupProcessingJobs.tvJobCounts.visibility = if (showProgress) View.VISIBLE else View.GONE
        if (showProgress) {
            val remaining = (status.totalEnqueued - status.completedCount - (if (hasActive) 1 else 0))
                .coerceAtLeast(0)
            binding.groupProcessingJobs.tvJobCounts.text = getString(
                R.string.settings_tools_jobs_summary,
                status.completedCount,
                remaining,
            )
        }

        // ── Recent jobs RecyclerView ──────────────────────────────────────────────
        binding.groupProcessingJobs.containerRecent.visibility = if (hasRecent) View.VISIBLE else View.GONE
        if (hasRecent) {
            val rows = status.recent.map { job ->
                val failed    = job.state == WorkInfo.State.FAILED
                val timeLabel = job.completedAt?.let { formatCompletionTime(it) } ?: ""
                WaveformJobAdapterRow(
                    label     = viewModel.labelForJob(job),
                    timeLabel = timeLabel,
                    failed    = failed,
                    isDone    = true,
                )
            }
            recentJobsAdapter.submitList(rows)
            // Scroll to top — newest items appear first (list is already reversed upstream).
            binding.groupProcessingJobs.rvRecentJobs.scrollToPosition(0)
        }

        // ── Cancel / Clear button visibility ─────────────────────────────────────
        binding.groupProcessingJobs.btnCancelWaveforms.visibility    = if (isActive)  View.VISIBLE else View.GONE
        binding.groupProcessingJobs.btnClearWaveformOutput.visibility = if (hasRecent) View.VISIBLE else View.GONE

        // ── Expandable output section ─────────────────────────────────────────────
        renderProcessingOutput(processingOutputExpanded)
    }

    /** Applies the current expanded/collapsed state to the output container. */
    private fun renderProcessingOutput(expanded: Boolean) {
        val hasAnythingToShow = binding.groupProcessingJobs.containerRecent.visibility == View.VISIBLE ||
                binding.groupProcessingJobs.btnCancelWaveforms.visibility == View.VISIBLE ||
                binding.groupProcessingJobs.btnClearWaveformOutput.visibility == View.VISIBLE
        binding.groupProcessingJobs.containerProcessingOutput.visibility =
            if (expanded && hasAnythingToShow) View.VISIBLE else View.GONE

        // Icon update lives here — the single place that knows the expanded state.
        binding.groupProcessingJobs.btnToggleProcessingOutput.setImageResource(
            if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
        )
    }

    private fun formatCompletionTime(epochMs: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(Date(epochMs))

    // ── Waveform job list adapter ─────────────────────────────────────────────────

    private data class WaveformJobAdapterRow(
        val label: String,
        val timeLabel: String,
        val failed: Boolean,
        val isDone: Boolean,
    )

    /**
     * RecyclerView adapter for the completed-waveform-jobs list in the Processing
     * section of the Tools tab.
     *
     * Each row shows: topic emoji + recording title (left), completion timestamp
     * (centre-right), and a ✓ / ✗ status glyph (far right).
     *
     * Backed by a plain list rather than DiffUtil because the list only ever
     * prepends items (newest-first), so a full rebind is cheap and correct.
     */
    private inner class WaveformJobAdapter : RecyclerView.Adapter<WaveformJobAdapter.VH>() {

        private var items: List<WaveformJobAdapterRow> = emptyList()

        fun submitList(newItems: List<WaveformJobAdapterRow>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val density = parent.context.resources.displayMetrics.density
            val hPad = (16 * density).toInt()
            val vPad = (6  * density).toInt()

            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
                setPadding(hPad, vPad, hPad, vPad)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                )
            }
            return VH(row, density)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(private val row: LinearLayout, density: Float) : RecyclerView.ViewHolder(row) {

            private val tvLabel  : TextView
            private val tvTime   : TextView
            private val tvStatus : TextView

            init {
                tvLabel = TextView(row.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                    )
                    textSize = 13f
                    setTextColor(row.context.themeColor(R.attr.colorTextPrimary))
                }
                tvTime = TextView(row.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    textSize = 12f
                    setTextColor(row.context.themeColor(R.attr.colorTextSecondary))
                }
                tvStatus = TextView(row.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).also { lp -> lp.marginStart = (8 * density).toInt() }
                    textSize = 13f
                }
                row.addView(tvLabel)
                row.addView(tvTime)
                row.addView(tvStatus)
            }

            fun bind(item: WaveformJobAdapterRow) {
                tvLabel.text  = item.label
                tvTime.text   = item.timeLabel
                tvStatus.text = when {
                    !item.isDone -> "⏳"
                    item.failed  -> "✗"
                    else         -> "✓"
                }
                tvStatus.setTextColor(
                    row.context.themeColor(
                        if (item.failed) R.attr.colorTextSecondary else R.attr.colorAccent,
                    )
                )
            }
        }
    }

    /**
     * Wires the recording folder migration section in the Tools tab.
     *
     * Visibility of the entire section is gated on the Future Mode flag so it
     * can be removed cleanly once the install base has been migrated.
     *
     * Guards against starting a migration while a recording is active — the
     * migrator would be moving files that RecordingService has open.
     */
    private fun setupMigrationSection() {
        // Gate visibility on futureMode.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.futureMode.collect { enabled ->
                    binding.groupMigration.root.visibility =
                        if (enabled) View.VISIBLE else View.GONE
                }
            }
        }

        binding.groupMigration.btnMigrateRecordings.setOnClickListener {
            if (viewModel.recordingState.value != RecordingService.State.IDLE) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.settings_migration_blocked_recording),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.settings_migration_confirm_title))
                .setMessage(getString(R.string.settings_migration_confirm_message))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(getString(R.string.settings_migration_confirm_action)) { _, _ ->
                    viewModel.migrateRecordingStructure()
                }
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.migrationState.collect { state ->
                    renderMigrationState(state)
                }
            }
        }
    }

    private fun renderMigrationState(state: MainViewModel.MigrationState) {
        when (state) {
            is MainViewModel.MigrationState.Idle -> {
                binding.groupMigration.progressMigration.visibility    = View.GONE
                binding.groupMigration.tvMigrationStatus.visibility    = View.GONE
                binding.groupMigration.btnMigrateRecordings.isEnabled  = true
            }
            is MainViewModel.MigrationState.Running -> {
                binding.groupMigration.progressMigration.visibility    = View.VISIBLE
                binding.groupMigration.tvMigrationStatus.visibility    = View.VISIBLE
                binding.groupMigration.tvMigrationStatus.text          = if (state.currentFile.isNotEmpty()) {
                    getString(R.string.settings_migration_running, state.currentFile)
                } else {
                    getString(R.string.settings_migration_scanning)
                }
                binding.groupMigration.btnMigrateRecordings.isEnabled  = false
            }
            is MainViewModel.MigrationState.Done -> {
                binding.groupMigration.progressMigration.visibility    = View.GONE
                binding.groupMigration.tvMigrationStatus.visibility    = View.VISIBLE
                binding.groupMigration.tvMigrationStatus.text          = when {
                    state.moved == 0 && state.failed == 0 ->
                        getString(R.string.settings_migration_done_none)
                    state.failed == 0 ->
                        getString(R.string.settings_migration_done_clean, state.moved)
                    else ->
                        getString(R.string.settings_migration_done, state.moved, state.failed)
                }
                binding.groupMigration.btnMigrateRecordings.isEnabled  = true
            }
        }
    }

    private fun setupDevOptionsSection() {
        // ── Future Mode switch ────────────────────────────────────────────────────
        binding.groupDevOptions.switchFutureMode.isChecked = viewModel.futureMode.value
        binding.groupDevOptions.switchFutureMode.setOnCheckedChangeListener { _, checked ->
            viewModel.setFutureMode(checked)
        }

        // ── Developer Options switch ──────────────────────────────────────────────
        binding.groupDevOptions.switchSimulateWfLoading.isChecked = viewModel.simulateWaveformLoading.value
        binding.groupDevOptions.switchSimulateWfLoading.setOnCheckedChangeListener { _, checked ->
            viewModel.setSimulateWaveformLoading(checked)
        }

        // ── Show/hide the Developer Options card based on devOptions flag ─────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.devOptions.collect { enabled ->
                    val vis = if (enabled) View.VISIBLE else View.GONE
                    binding.groupDevOptions.labelDeveloperOptionsSection.visibility = vis
                    binding.groupDevOptions.containerDeveloperOptionsCard.visibility = vis
                    // Reset simulate switch when hiding, mirroring the VM reset.
                    if (!enabled) binding.groupDevOptions.switchSimulateWfLoading.isChecked = false
                }
            }
        }

        binding.groupDevOptions.switchDevOptions.isChecked = viewModel.devOptions.value
        binding.groupDevOptions.switchDevOptions.setOnCheckedChangeListener { _, checked ->
            viewModel.setDevOptions(checked)
        }
    }

    private fun setupStorageSection() {
        // Observe the live volume list and usage stats together.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Combine volumes + DB usage stats into a single emission.
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

        // ── Verbose backup logging ────────────────────────────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.verboseBackupLogging.collect { enabled ->
                binding.groupBackups.switchVerboseBackupLogging.isChecked = enabled
            }
        }
        binding.groupBackups.switchVerboseBackupLogging.setOnCheckedChangeListener { _, checked ->
            viewModel.setVerboseBackupLogging(checked)
        }

        // ── DB snapshot pruning ───────────────────────────────────────────────────────
        val switchDbPrune  = binding.groupBackups.switchDbPrune
        val rowDbPruneCount = binding.groupBackups.rowDbPruneCount
        val tvDbPruneCount  = binding.groupBackups.tvDbPruneCount
        val btnMinus        = binding.groupBackups.btnDbPruneMinus
        val btnPlus         = binding.groupBackups.btnDbPrunePlus

        fun refreshPruneCount() {
            tvDbPruneCount.text = viewModel.getDbPruneCount().toString()
        }

        switchDbPrune.isChecked = viewModel.getDbPruneEnabled()
        rowDbPruneCount.alpha   = if (switchDbPrune.isChecked) 1f else 0.4f
        rowDbPruneCount.isEnabled = switchDbPrune.isChecked
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
    private fun renderStorageVolumes(
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
                gravity = Gravity.CENTER_VERTICAL
                setPadding(64, 20, 64, 20)
                isClickable = true
                isFocusable  = true
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
                orientation = LinearLayout.VERTICAL
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

            // Divider between rows (not after last)
            if (volume != volumes.last()) {
                val divider = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).also { lp -> lp.marginStart = 64; lp.marginEnd = 64 }
                    setBackgroundColor(requireContext().themeColor(R.attr.colorSurfaceElevated))
                }
                container.addView(divider)
            }
        }
    }

    private fun renderTotalUsed(usageMap: Map<String, Long>) {
        val totalBytes = usageMap.values.sum()
        binding.groupStorageVolumes.tvTotalUsed.text =
            if (totalBytes == 0L) getString(R.string.common_placeholder_empty)
            else AppVolume.formatBytes(totalBytes)
    }

}