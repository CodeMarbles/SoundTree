package app.treecast.ui.settings

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import app.treecast.R
import app.treecast.data.entities.BackupLogEntity
import app.treecast.databinding.FragmentSettingsBinding
import app.treecast.databinding.ItemBackupAvailableVolumeBinding
import app.treecast.databinding.ItemBackupLogRowBinding
import app.treecast.databinding.ViewBackupProgressCardBinding
import app.treecast.service.RecordingService
import app.treecast.storage.AppVolume
import app.treecast.storage.StorageVolumeHelper
import app.treecast.ui.BackupTargetUiState
import app.treecast.ui.BackupUiState
import app.treecast.ui.MainViewModel
import app.treecast.ui.PlayerWidgetVisibility
import app.treecast.ui.ProcessingStatus
import app.treecast.ui.RecorderWidgetVisibility
import app.treecast.ui.addBackupTarget
import app.treecast.ui.cancelBackupForVolume
import app.treecast.ui.cancelWaveformProcessing
import app.treecast.ui.clearCompletedWaveformJobs
import app.treecast.ui.getDbPruneCount
import app.treecast.ui.getDbPruneEnabled
import app.treecast.ui.getLastSessionOpenedAt
import app.treecast.ui.getNearEndDurationThresholdSecs
import app.treecast.ui.getNearEndEnabled
import app.treecast.ui.getNearEndLongPct
import app.treecast.ui.getNearEndShortSecs
import app.treecast.ui.getRememberLongThresholdSecs
import app.treecast.ui.getTotalRecordingTime
import app.treecast.ui.labelForJob
import app.treecast.ui.migrateRecordingStructure
import app.treecast.ui.recovery.OrphanRecoveryDialogFragment
import app.treecast.ui.refreshStorageVolumes
import app.treecast.ui.reprocessAllWaveforms
import app.treecast.ui.restore.RestoreWizardDialogFragment
import app.treecast.ui.setAlwaysShowPlayerPill
import app.treecast.ui.setAlwaysShowRecorderPill
import app.treecast.ui.setAutoNavigateToListen
import app.treecast.ui.setBgAlpha
import app.treecast.ui.setBgExtendsUnderRuler
import app.treecast.ui.setBgUnplayedOnly
import app.treecast.ui.setDbPruneCount
import app.treecast.ui.setDbPruneEnabled
import app.treecast.ui.setDefaultStorageUuid
import app.treecast.ui.setDevOptions
import app.treecast.ui.setFutureMode
import app.treecast.ui.setHidePlayerOnListenTab
import app.treecast.ui.setHideRecorderOnRecordTab
import app.treecast.ui.setInvertWaveformTheme
import app.treecast.ui.setJumpToLibraryOnSave
import app.treecast.ui.setLayoutOrder
import app.treecast.ui.setMarkNudgeSecs
import app.treecast.ui.setMarkRewindThresholdSecs
import app.treecast.ui.setNearEndDurationThresholdSecs
import app.treecast.ui.setNearEndEnabled
import app.treecast.ui.setNearEndLongPct
import app.treecast.ui.setNearEndShortSecs
import app.treecast.ui.setPlayerWidgetVisibility
import app.treecast.ui.setPlayheadVisEnabled
import app.treecast.ui.setPlayheadVisIntensity
import app.treecast.ui.setRecorderWidgetVisibility
import app.treecast.ui.setRememberLongThresholdSecs
import app.treecast.ui.setRememberPositionMode
import app.treecast.ui.setScrubBackSecs
import app.treecast.ui.setScrubForwardSecs
import app.treecast.ui.setShowTitleBar
import app.treecast.ui.setSimulateWaveformLoading
import app.treecast.ui.setThemeMode
import app.treecast.ui.setVerboseBackupLogging
import app.treecast.ui.setWaveformStyleKey
import app.treecast.ui.tickProcessingRefresh
import app.treecast.util.BackupProgressCalc
import app.treecast.util.OrphanRecording
import app.treecast.util.PlaybackPositionHelper
import app.treecast.util.themeColor
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

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
        binding.tvAppIdentity.text = getString(R.string.app_label_identity, getString(R.string.app_name), getString(R.string.app_emoji))
    }

    private fun setupRecordingRecoverySection() {
        binding.btnReviewOrphans.setOnClickListener {
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
        binding.tvOrphanRecoverableSummary.text = formatOrphanSummary(recoverable)
        binding.tvOrphanCorruptSummary.text     = formatOrphanSummary(unrecoverable)
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

        binding.btnViewAllHistory.setOnClickListener {
            BackupLogHistoryDialog()
                .show(childFragmentManager, BackupLogHistoryDialog.TAG)
        }
    }

    private fun setupRestoreSection() {
        binding.btnRestoreFromBackup.setOnClickListener {
            openDocumentTreeForRestore.launch(null)
        }
    }

    /**
     * Renders the "Designated backup volumes" list.
     * Each row shows label, mount status, last-backup time, and a gear icon
     * that opens [BackupTargetConfigDialog].
     */
    private fun renderBackupTargets(targets: List<BackupTargetUiState>) {
        val container = binding.containerBackupTargets
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
        val container = binding.containerBackupAvailable
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
        val container = binding.containerBackupLog
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
        val container = binding.containerBackupProgressCard
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

    /** Creates a thin horizontal divider consistent with other Storage tab rows. */
    private fun rowDivider(): View {
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
    private fun setChildrenEnabled(group: ViewGroup, enabled: Boolean) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            child.isEnabled = enabled
            if (child is ViewGroup) setChildrenEnabled(child, enabled)
        }
    }

    // ── Waveform processing output state ─────────────────────────────────────────
    private var processingOutputExpanded = true
    private lateinit var recentJobsAdapter: WaveformJobAdapter

    private fun setupProcessingSection() {
        recentJobsAdapter = WaveformJobAdapter()
        binding.rvRecentJobs.apply {
            layoutManager = LinearLayoutManager(requireContext()).also {
                // Newest items are prepended, so we want the list to scroll to
                // position 0 (top) on each update — no reverseLayout needed.
            }
            adapter = recentJobsAdapter
        }

        // Confirmation dialog guards the destructive button.
        binding.btnReprocessWaveforms.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_tools_regenerate_confirm_title)
                .setMessage(R.string.settings_tools_regenerate_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_tools_regenerate_confirm_action) { _, _ ->
                    viewModel.reprocessAllWaveforms()
                }
                .show()
        }

        binding.btnCancelWaveforms.setOnClickListener {
            // Optimistically hide the cancel button — WorkManager cancellation is async
            // and the flow won't confirm until WM emits the cancelled state.
            binding.btnCancelWaveforms.visibility = View.GONE
            viewModel.cancelWaveformProcessing()
        }

        binding.btnClearWaveformOutput.setOnClickListener {
            // Optimistically clear the UI immediately — don't wait for the flow.
            binding.containerRecent.visibility        = View.GONE
            binding.btnClearWaveformOutput.visibility = View.GONE
            // Then do the actual data work (also triggers a flow emission as confirmation).
            viewModel.clearCompletedWaveformJobs()
        }

        binding.btnToggleProcessingOutput.setOnClickListener {
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
        binding.processingSpinner.visibility     = if (hasActive) View.VISIBLE else View.GONE
        binding.tvProcessingIdle.visibility      = if (isIdle)   View.VISIBLE else View.GONE
        binding.btnToggleProcessingOutput.visibility = if (!isIdle) View.VISIBLE else View.GONE

        // Disable the regenerate button while a pass is in flight.
        binding.btnReprocessWaveforms.isEnabled = !isActive

        // ── Active job row ────────────────────────────────────────────────────────
        binding.rowActiveJob.visibility = if (hasActive) View.VISIBLE else View.GONE
        status.active?.let { binding.tvActiveJobTitle.text = viewModel.labelForJob(it) }

        // ── Progress bar ──────────────────────────────────────────────────────────
        val showProgress = status.totalEnqueued > 0
        binding.pbWaveformProgress.visibility = if (showProgress) View.VISIBLE else View.GONE
        if (showProgress) {
            val progressFraction = status.completedCount.toFloat() / status.totalEnqueued
            binding.pbWaveformProgress.progress = (progressFraction * 1000).toInt()
        }

        // ── Job counts ────────────────────────────────────────────────────────────
        binding.tvJobCounts.visibility = if (showProgress) View.VISIBLE else View.GONE
        if (showProgress) {
            val remaining = (status.totalEnqueued - status.completedCount - (if (hasActive) 1 else 0))
                .coerceAtLeast(0)
            binding.tvJobCounts.text = getString(
                R.string.settings_tools_jobs_summary,
                status.completedCount,
                remaining,
            )
        }

        // ── Recent jobs RecyclerView ──────────────────────────────────────────────
        binding.containerRecent.visibility = if (hasRecent) View.VISIBLE else View.GONE
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
            binding.rvRecentJobs.scrollToPosition(0)
        }

        // ── Cancel / Clear button visibility ─────────────────────────────────────
        binding.btnCancelWaveforms.visibility    = if (isActive)  View.VISIBLE else View.GONE
        binding.btnClearWaveformOutput.visibility = if (hasRecent) View.VISIBLE else View.GONE

        // ── Expandable output section ─────────────────────────────────────────────
        renderProcessingOutput(processingOutputExpanded)
    }

    /** Applies the current expanded/collapsed state to the output container. */
    private fun renderProcessingOutput(expanded: Boolean) {
        val hasAnythingToShow = binding.containerRecent.visibility == View.VISIBLE ||
                binding.btnCancelWaveforms.visibility == View.VISIBLE ||
                binding.btnClearWaveformOutput.visibility == View.VISIBLE
        binding.containerProcessingOutput.visibility =
            if (expanded && hasAnythingToShow) View.VISIBLE else View.GONE

        // Icon update lives here — the single place that knows the expanded state.
        binding.btnToggleProcessingOutput.setImageResource(
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
                    binding.containerMigrationSection.visibility =
                        if (enabled) View.VISIBLE else View.GONE
                }
            }
        }

        binding.btnMigrateRecordings.setOnClickListener {
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
                binding.progressMigration.visibility    = View.GONE
                binding.tvMigrationStatus.visibility    = View.GONE
                binding.btnMigrateRecordings.isEnabled  = true
            }
            is MainViewModel.MigrationState.Running -> {
                binding.progressMigration.visibility    = View.VISIBLE
                binding.tvMigrationStatus.visibility    = View.VISIBLE
                binding.tvMigrationStatus.text          = if (state.currentFile.isNotEmpty()) {
                    getString(R.string.settings_migration_running, state.currentFile)
                } else {
                    getString(R.string.settings_migration_scanning)
                }
                binding.btnMigrateRecordings.isEnabled  = false
            }
            is MainViewModel.MigrationState.Done -> {
                binding.progressMigration.visibility    = View.GONE
                binding.tvMigrationStatus.visibility    = View.VISIBLE
                binding.tvMigrationStatus.text          = when {
                    state.moved == 0 && state.failed == 0 ->
                        getString(R.string.settings_migration_done_none)
                    state.failed == 0 ->
                        getString(R.string.settings_migration_done_clean, state.moved)
                    else ->
                        getString(R.string.settings_migration_done, state.moved, state.failed)
                }
                binding.btnMigrateRecordings.isEnabled  = true
            }
        }
    }

    private fun setupDevOptionsSection() {
        // ── Future Mode switch ────────────────────────────────────────────────────
        binding.switchFutureMode.isChecked = viewModel.futureMode.value
        binding.switchFutureMode.setOnCheckedChangeListener { _, checked ->
            viewModel.setFutureMode(checked)
        }

        // ── Developer Options switch ──────────────────────────────────────────────
        binding.switchSimulateWfLoading.isChecked = viewModel.simulateWaveformLoading.value
        binding.switchSimulateWfLoading.setOnCheckedChangeListener { _, checked ->
            viewModel.setSimulateWaveformLoading(checked)
        }

        // ── Show/hide the Developer Options card based on devOptions flag ─────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.devOptions.collect { enabled ->
                    val vis = if (enabled) View.VISIBLE else View.GONE
                    binding.labelDeveloperOptionsSection.visibility = vis
                    binding.containerDeveloperOptionsCard.visibility = vis
                    // Reset simulate switch when hiding, mirroring the VM reset.
                    if (!enabled) binding.switchSimulateWfLoading.isChecked = false
                }
            }
        }

        binding.switchDevOptions.isChecked = viewModel.devOptions.value
        binding.switchDevOptions.setOnCheckedChangeListener { _, checked ->
            viewModel.setDevOptions(checked)
        }
    }

    private fun setupLayoutSection() {
        val widget   = binding.layoutReorderWidget
        val btnEdit  = binding.btnEditLayout
        val toggle   = binding.switchShowTitleBar

        // ── Initialise widget from current ViewModel state ────────────
        widget.setOrder(viewModel.layoutOrder.value)
        widget.showTitleBar = viewModel.showTitleBar.value
        toggle.isChecked    = viewModel.showTitleBar.value

        // ── Title bar toggle ──────────────────────────────────────────
        toggle.setOnCheckedChangeListener { _, isChecked ->
            widget.showTitleBar = isChecked

            // If we aren't in edit mode show the title bar immediately
            if (!widget.isInEditMode) {
                viewModel.setShowTitleBar(isChecked)
                viewModel.setLayoutOrder(widget.getOrder())
            }
        }

        // ── Edit / Apply button ───────────────────────────────────────
        fun enterEditMode() {
            widget.setEditing(true)
            btnEdit.text = getString(R.string.settings_btn_layout_apply)
        }

        fun applyAndLock() {
            viewModel.setLayoutOrder(widget.getOrder())
            viewModel.setShowTitleBar(widget.showTitleBar)
            widget.setEditing(false)
            btnEdit.text = getString(R.string.settings_btn_layout_edit)
        }

        btnEdit.setOnClickListener {
            if (widget.editing) applyAndLock() else enterEditMode()
        }

        // ── Keep widget in sync if another screen changes prefs ───────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.layoutOrder,
                    viewModel.showTitleBar
                ) { order, show -> order to show }
                    .collect { (order, show) ->
                        if (!widget.editing) {
                            widget.setOrder(order)
                            widget.showTitleBar = show
                            toggle.isChecked = show
                        }
                    }
            }
        }
    }

    private fun setupRecordingWidgetSection() {

        // ── Recorder Widget visibility toggle group ───────────────────────
        val toggleGroup = binding.toggleRecorderVisibility

        // Map each button id → enum value (and back)
        val btnToMode = mapOf(
            R.id.btnRecorderVisNever          to RecorderWidgetVisibility.NEVER,
            R.id.btnRecorderVisWhileRecording to RecorderWidgetVisibility.WHILE_RECORDING,
            R.id.btnRecorderVisAlways         to RecorderWidgetVisibility.ALWAYS
        )
        val modeToBtn = btnToMode.entries.associate { (k, v) -> v to k }

        fun applyMode(mode: RecorderWidgetVisibility) {
            // Check the right button
            toggleGroup.check(modeToBtn[mode] ?: R.id.btnRecorderVisWhileRecording)
            // Dependent row: enabled only when not NEVER
            val dependentEnabled = mode != RecorderWidgetVisibility.NEVER
            binding.rowHideRecorderOnRecordTab.alpha = if (dependentEnabled) 1f else 0.4f
            binding.switchHideRecorderOnRecordTab.isEnabled = dependentEnabled
        }

        // Initialise from ViewModel
        applyMode(viewModel.recorderWidgetVisibility.value)
        binding.switchHideRecorderOnRecordTab.isChecked = viewModel.hideRecorderOnRecordTab.value

        // User taps a button
        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = btnToMode[checkedId] ?: return@addOnButtonCheckedListener
            viewModel.setRecorderWidgetVisibility(mode)
            applyMode(mode)
        }

        // Dependent toggle
        binding.switchHideRecorderOnRecordTab.setOnCheckedChangeListener { _, checked ->
            viewModel.setHideRecorderOnRecordTab(checked)
        }

        // ── Always show recorder shortcut ────────────────────────────────────
        binding.switchAlwaysShowRecorderPill.isChecked = viewModel.alwaysShowRecorderPill.value
        binding.switchAlwaysShowRecorderPill.setOnCheckedChangeListener { _, checked ->
            viewModel.setAlwaysShowRecorderPill(checked)
        }

        // ── Mark nudge seconds ────────────────────────────────────────────
        fun Float.toNudgeDisplay() =
            if (this == this.toLong().toFloat()) "${this.toInt()}s" else "${this}s"

        binding.tvMarkNudgeSecs.text = viewModel.markNudgeSecs.value.toNudgeDisplay()

        binding.btnMarkNudgeMinus.setOnClickListener {
            val newVal = (viewModel.markNudgeSecs.value - 1f).coerceAtLeast(1f)
            viewModel.setMarkNudgeSecs(newVal)
            binding.tvMarkNudgeSecs.text = newVal.toNudgeDisplay()
        }
        binding.btnMarkNudgePlus.setOnClickListener {
            val newVal = (viewModel.markNudgeSecs.value + 1f).coerceAtMost(30f)
            viewModel.setMarkNudgeSecs(newVal)
            binding.tvMarkNudgeSecs.text = newVal.toNudgeDisplay()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.recorderWidgetVisibility.collect { mode -> applyMode(mode) }
                }
                launch {
                    viewModel.hideRecorderOnRecordTab.collect { hide ->
                        binding.switchHideRecorderOnRecordTab.isChecked = hide
                    }
                }
                launch {
                    viewModel.alwaysShowRecorderPill.collect { show ->
                        binding.switchAlwaysShowRecorderPill.isChecked = show
                    }
                }
            }
        }
    }

    private fun setupTheme() {
        fun updateToggleVisuals(selected: String) {
            val activeText = requireContext().themeColor(R.attr.colorTextPrimary)
            val activeBg   = requireContext().themeColor(R.attr.colorSurfaceElevated)
            val inactiveText = requireContext().themeColor(R.attr.colorTextSecondary)
            listOf(
                binding.btnThemeSystem to "system",
                binding.btnThemeLight  to "light",
                binding.btnThemeDark   to "dark"
            ).forEach { (btn, mode) ->
                val isActive = mode == selected
                btn.setTextColor(if (isActive) activeText else inactiveText)
                btn.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
                btn.setBackgroundColor(if (isActive) activeBg else android.graphics.Color.TRANSPARENT)
            }
        }

        updateToggleVisuals(viewModel.themeMode.value)

        fun select(mode: String) {
            viewModel.setThemeMode(mode)
            updateToggleVisuals(mode)
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark"  -> AppCompatDelegate.MODE_NIGHT_YES
                    else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
            // Activity recreates automatically — no manual call needed.
        }

        binding.btnThemeSystem.setOnClickListener { select("system") }
        binding.btnThemeLight.setOnClickListener  { select("light")  }
        binding.btnThemeDark.setOnClickListener   { select("dark")   }
    }

    private fun setupWaveformStyleSettings() {

        // ── View refs ─────────────────────────────────────────────────────────
        val btnStyleStandard  = binding.btnWaveformStyleStandard
        val btnStyleSky       = binding.btnWaveformStyleSky
        val btnStyleSkyLights = binding.btnWaveformStyleSkyLights
        val rowSubOptions     = binding.rowWaveformSubOptions
        val switchInvert      = binding.switchInvertWaveformTheme
        val rowInvert         = binding.rowInvertWaveformTheme
        val sliderAlpha       = binding.sliderWaveformBgAlpha
        val rowAlpha          = binding.rowWaveformBgAlpha
        val switchRuler       = binding.switchWaveformExtendsUnderRuler
        val switchUnplayed    = binding.switchWaveformUnplayedOnly

        val btnToKey = mapOf(
            btnStyleStandard  to MainViewModel.STYLE_STANDARD,
            btnStyleSky       to MainViewModel.STYLE_SKY,
            btnStyleSkyLights to MainViewModel.STYLE_SKY_LIGHTS,
        )

        fun applyStyleButtonVisuals(activeKey: String) {
            val activeText   = requireContext().themeColor(R.attr.colorTextPrimary)
            val activeBg     = requireContext().themeColor(R.attr.colorSurfaceElevated)
            val inactiveText = requireContext().themeColor(R.attr.colorTextSecondary)
            btnToKey.forEach { (btn, key) ->
                val isActive = key == activeKey
                btn.setTextColor(if (isActive) activeText else inactiveText)
                btn.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
                btn.setBackgroundColor(if (isActive) activeBg else android.graphics.Color.TRANSPARENT)
            }
        }

        /** Dim and disable the sub-options block when Standard is selected. */
        fun applySubOptionState(styleKey: String) {
            val themed = styleKey != MainViewModel.STYLE_STANDARD
            rowSubOptions.alpha      = if (themed) 1f else 0.38f
            rowInvert.alpha          = if (themed) 1f else 0.38f
            rowAlpha.alpha           = if (themed) 1f else 0.38f
            switchInvert.isEnabled   = themed
            sliderAlpha.isEnabled    = themed
            switchRuler.isEnabled    = themed
            switchUnplayed.isEnabled = themed
        }

        // ── Seed from ViewModel ───────────────────────────────────────────────
        val initialKey    = viewModel.waveformStyleKey.value
        val initialConfig = viewModel.waveformDisplayConfig.value

        applyStyleButtonVisuals(initialKey)
        applySubOptionState(initialKey)

        switchInvert.isChecked   = viewModel.invertWaveformTheme.value
        sliderAlpha.value        = (initialConfig.backgroundAlpha * 100f).roundToInt().toFloat().coerceIn(0f, 100f)
        switchRuler.isChecked    = initialConfig.extendsUnderRuler
        switchUnplayed.isChecked = initialConfig.unplayedOnly

        // ── Observe ───────────────────────────────────────────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.waveformStyleKey.collect { key ->
                        applyStyleButtonVisuals(key)
                        applySubOptionState(key)
                    }
                }
                launch {
                    viewModel.invertWaveformTheme.collect { inverted ->
                        if (switchInvert.isChecked != inverted) switchInvert.isChecked = inverted
                    }
                }
                launch {
                    viewModel.waveformDisplayConfig.collect { cfg ->
                        val sliderTarget = (cfg.backgroundAlpha * 100f).roundToInt().toFloat().coerceIn(0f, 100f)
                        if (sliderAlpha.value != sliderTarget) sliderAlpha.value = sliderTarget
                        if (switchRuler.isChecked    != cfg.extendsUnderRuler) switchRuler.isChecked    = cfg.extendsUnderRuler
                        if (switchUnplayed.isChecked != cfg.unplayedOnly)      switchUnplayed.isChecked = cfg.unplayedOnly
                    }
                }
            }
        }

        // ── User interaction ──────────────────────────────────────────────────
        btnToKey.forEach { (btn, key) ->
            btn.setOnClickListener { viewModel.setWaveformStyleKey(key) }
        }

        switchInvert.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setInvertWaveformTheme(isChecked)
        }

        // Slider: commit to ViewModel only on touch-up to avoid rapid pref writes
        sliderAlpha.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                viewModel.setBgAlpha(slider.value / 100f)
            }
        })

        switchRuler.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setBgExtendsUnderRuler(isChecked)
        }

        switchUnplayed.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setBgUnplayedOnly(isChecked)
        }
    }

    private fun setupPlayheadVis() {
        val switch   = binding.switchPlayheadVisEnabled
        val rowIntensity = binding.rowPlayheadVisIntensity
        val slider   = binding.sliderPlayheadVisIntensity

        // Initial state
        switch.isChecked = viewModel.playheadVisEnabled.value
        slider.value     = viewModel.playheadVisIntensity.value
        rowIntensity.visibility = if (switch.isChecked) View.VISIBLE else View.GONE

        switch.setOnCheckedChangeListener { _, checked ->
            viewModel.setPlayheadVisEnabled(checked)
            rowIntensity.visibility = if (checked) View.VISIBLE else View.GONE
        }

        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.setPlayheadVisIntensity(value)
        }
    }

// ── In setupBehavior() — wire the new Playback Memory card ───────────────────

    private fun setupPlaybackMemory() {
        // Mode picker
        val btnAlways   = binding.btnRememberAlways
        val btnLongOnly = binding.btnRememberLongOnly
        val btnNever    = binding.btnRememberNever
        val rowThreshold = binding.rowRememberLongThreshold
        val tvThreshold  = binding.tvRememberThresholdValue

        fun highlightMode(mode: String) {
            listOf(
                btnAlways   to PlaybackPositionHelper.MODE_ALWAYS,
                btnLongOnly to PlaybackPositionHelper.MODE_LONG_ONLY,
                btnNever    to PlaybackPositionHelper.MODE_NEVER,
            ).forEach { (btn, m) ->
                val isActive = m == mode
                btn.background = if (isActive) {
                    android.graphics.drawable.GradientDrawable().apply {
                        shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = resources.getDimension(R.dimen.settings_card_corner_radius) -
                                resources.displayMetrics.density * 3f
                        setColor(requireContext().themeColor(R.attr.colorSurfaceElevated))
                    }
                } else null
                btn.setTextColor(
                    requireContext().themeColor(
                        if (isActive) R.attr.colorTextPrimary else R.attr.colorTextSecondary
                    )
                )
            }
            rowThreshold.visibility = if (mode == PlaybackPositionHelper.MODE_LONG_ONLY) View.VISIBLE else View.GONE
        }

        val initialMode = viewModel.rememberPositionMode.value
        highlightMode(initialMode)

        btnAlways.setOnClickListener   { viewModel.setRememberPositionMode(PlaybackPositionHelper.MODE_ALWAYS);    highlightMode(PlaybackPositionHelper.MODE_ALWAYS) }
        btnLongOnly.setOnClickListener { viewModel.setRememberPositionMode(PlaybackPositionHelper.MODE_LONG_ONLY); highlightMode(PlaybackPositionHelper.MODE_LONG_ONLY) }
        btnNever.setOnClickListener    { viewModel.setRememberPositionMode(PlaybackPositionHelper.MODE_NEVER);     highlightMode(PlaybackPositionHelper.MODE_NEVER) }

        // Long threshold stepper
        fun renderThreshold() {
            val secs = viewModel.getRememberLongThresholdSecs()
            tvThreshold.text = if (secs % 60 == 0) "${secs / 60} min" else "${secs}s"
        }
        renderThreshold()
        binding.btnRememberThresholdDown.setOnClickListener {
            val current = viewModel.getRememberLongThresholdSecs()
            viewModel.setRememberLongThresholdSecs((current - 60).coerceAtLeast(60))
            renderThreshold()
        }
        binding.btnRememberThresholdUp.setOnClickListener {
            val current = viewModel.getRememberLongThresholdSecs()
            viewModel.setRememberLongThresholdSecs(current + 60)
            renderThreshold()
        }

        // ── Near-End Reset ────────────────────────────────────────────────────────────

        val switchNearEnd       = binding.switchNearEndEnabled
        val rowShort            = binding.rowNearEndShort
        val rowLong             = binding.rowNearEndLong
        val rowNearEndThreshold = binding.rowNearEndThreshold
        val tvShort             = binding.tvNearEndShortValue
        val tvShortDesc         = binding.tvNearEndShortDesc
        val tvLong              = binding.tvNearEndLongValue

        /** Alpha applied to the stepper rows when the master toggle is off. */
        val DISABLED_ALPHA = 0.38f

        fun renderNearEndSection() {
            val enabled = viewModel.getNearEndEnabled()

            // Sync the switch without triggering its listener.
            switchNearEnd.setOnCheckedChangeListener(null)
            switchNearEnd.isChecked = enabled
            switchNearEnd.setOnCheckedChangeListener { _, checked ->
                viewModel.setNearEndEnabled(checked)
                renderNearEndSection()
            }

            // Dim and block interaction on the three stepper rows when disabled.
            val alpha = if (enabled) 1f else DISABLED_ALPHA
            rowShort.alpha     = alpha;  rowShort.isEnabled     = enabled
            rowLong.alpha      = alpha;  rowLong.isEnabled      = enabled
            rowNearEndThreshold.alpha = alpha;  rowNearEndThreshold.isEnabled = enabled
            // Propagate enabled state to children so the +/− buttons don't fire.
            setChildrenEnabled(rowShort,     enabled)
            setChildrenEnabled(rowLong,      enabled)
            setChildrenEnabled(rowNearEndThreshold, enabled)
        }

        fun renderNearEndShort() {
            val secs = viewModel.getNearEndShortSecs()
            tvShort.text = "${secs}s"
            val thresholdMins = viewModel.getNearEndDurationThresholdSecs() / 60
            tvShortDesc.text = getString(R.string.settings_playback_near_end_short_desc, thresholdMins)
        }
        fun renderNearEndLong() { tvLong.text = "${viewModel.getNearEndLongPct()}%" }

        renderNearEndSection()
        renderNearEndShort()
        renderNearEndLong()

        binding.btnNearEndShortDown.setOnClickListener {
            viewModel.setNearEndShortSecs(viewModel.getNearEndShortSecs() - 5)
            renderNearEndShort()
        }
        binding.btnNearEndShortUp.setOnClickListener {
            viewModel.setNearEndShortSecs(viewModel.getNearEndShortSecs() + 5)
            renderNearEndShort()
        }
        binding.btnNearEndLongDown.setOnClickListener {
            viewModel.setNearEndLongPct(viewModel.getNearEndLongPct() - 1)
            renderNearEndLong()
        }
        binding.btnNearEndLongUp.setOnClickListener {
            viewModel.setNearEndLongPct(viewModel.getNearEndLongPct() + 1)
            renderNearEndLong()
        }

        // Duration threshold stepper
        val tvDurThresh = binding.tvNearEndThresholdValue
        fun renderDurThreshold() {
            val mins = viewModel.getNearEndDurationThresholdSecs() / 60
            tvDurThresh.text = "${mins} min"
        }
        renderDurThreshold()
        binding.btnNearEndThresholdDown.setOnClickListener {
            val current = viewModel.getNearEndDurationThresholdSecs()
            viewModel.setNearEndDurationThresholdSecs((current - 60).coerceAtLeast(60))
            renderDurThreshold()
            renderNearEndShort()  // description references this value
        }
        binding.btnNearEndThresholdUp.setOnClickListener {
            val current = viewModel.getNearEndDurationThresholdSecs()
            viewModel.setNearEndDurationThresholdSecs(current + 60)
            renderDurThreshold()
            renderNearEndShort()
        }
    }

    private fun setupPlaybackSettings() {
        // ── Switch to Listen on play ──────────────────────────────────────────────
        binding.switchAutoNavigate.isChecked = viewModel.autoNavigateToListen.value
        binding.switchAutoNavigate.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoNavigateToListen(isChecked)
        }

        // ── Jump to Library on save ───────────────────────────────────────────────
        binding.switchJumpToLibrary.isChecked = viewModel.jumpToLibraryOnSave.value
        binding.switchJumpToLibrary.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setJumpToLibraryOnSave(isChecked)
        }

        // ── Player widget visibility (3-state) ───────────────────────────────
        val playerToggleGroup = binding.togglePlayerVisibility

        val playerBtnToMode = mapOf(
            R.id.btnPlayerVisNever       to PlayerWidgetVisibility.NEVER,
            R.id.btnPlayerVisWhilePlaying to PlayerWidgetVisibility.WHILE_PLAYING,
            R.id.btnPlayerVisAlways      to PlayerWidgetVisibility.ALWAYS
        )
        val playerModeToBtn = playerBtnToMode.entries.associate { (k, v) -> v to k }

        fun applyPlayerMode(mode: PlayerWidgetVisibility) {
            playerToggleGroup.check(playerModeToBtn[mode] ?: R.id.btnPlayerVisWhilePlaying)
            // "Hide on Listen Tab" only makes sense when the widget can appear,
            // i.e. not when NEVER is selected.
            val dependentEnabled = mode != PlayerWidgetVisibility.NEVER
            binding.rowHidePlayerOnListenTab.alpha = if (dependentEnabled) 1f else 0.4f
            binding.switchHidePlayerOnListenTab.isEnabled = dependentEnabled
        }

        applyPlayerMode(viewModel.playerWidgetVisibility.value)
        binding.switchHidePlayerOnListenTab.isChecked = viewModel.hidePlayerOnListenTab.value

        playerToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = playerBtnToMode[checkedId] ?: return@addOnButtonCheckedListener
            viewModel.setPlayerWidgetVisibility(mode)
            applyPlayerMode(mode)
        }

        binding.switchHidePlayerOnListenTab.setOnCheckedChangeListener { _, checked ->
            viewModel.setHidePlayerOnListenTab(checked)
        }

        binding.switchAlwaysShowPlayerPill.isChecked = viewModel.alwaysShowPlayerPill.value
        binding.switchAlwaysShowPlayerPill.setOnCheckedChangeListener { _, checked ->
            viewModel.setAlwaysShowPlayerPill(checked)
        }

        // ── Scrub Back ────────────────────────────────────────────────────────
        binding.tvScrubBackSecs.text = viewModel.scrubBackSecs.value.toString()
        binding.btnScrubBackMinus.setOnClickListener {
            val newVal = (viewModel.scrubBackSecs.value - 5).coerceAtLeast(5)
            viewModel.setScrubBackSecs(newVal)
            binding.tvScrubBackSecs.text = newVal.toString()
        }
        binding.btnScrubBackPlus.setOnClickListener {
            val newVal = viewModel.scrubBackSecs.value + 5
            viewModel.setScrubBackSecs(newVal)
            binding.tvScrubBackSecs.text = newVal.toString()
        }

        // ── Scrub Forward ─────────────────────────────────────────────────────
        binding.tvScrubForwardSecs.text = viewModel.scrubForwardSecs.value.toString()
        binding.btnScrubForwardMinus.setOnClickListener {
            val newVal = (viewModel.scrubForwardSecs.value - 5).coerceAtLeast(5)
            viewModel.setScrubForwardSecs(newVal)
            binding.tvScrubForwardSecs.text = newVal.toString()
        }
        binding.btnScrubForwardPlus.setOnClickListener {
            val newVal = viewModel.scrubForwardSecs.value + 5
            viewModel.setScrubForwardSecs(newVal)
            binding.tvScrubForwardSecs.text = newVal.toString()
        }

        // ── Mark Rewind Threshold ─────────────────────────────────────────────
        fun Float.toDisplayString() =
            if (this == this.toLong().toFloat()) "${this.toInt()}s" else "${this}s"

        binding.tvMarkRewindSecs.text = viewModel.markRewindThresholdSecs.value.toDisplayString()
        binding.btnMarkRewindMinus.setOnClickListener {
            val newVal = (viewModel.markRewindThresholdSecs.value - 0.5f).coerceAtLeast(0.5f)
            viewModel.setMarkRewindThresholdSecs(newVal)
            binding.tvMarkRewindSecs.text = newVal.toDisplayString()
        }
        binding.btnMarkRewindPlus.setOnClickListener {
            val newVal = (viewModel.markRewindThresholdSecs.value + 0.5f).coerceAtMost(5.0f)
            viewModel.setMarkRewindThresholdSecs(newVal)
            binding.tvMarkRewindSecs.text = newVal.toDisplayString()
        }

        // ── Keep controls in sync with external pref changes ─────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.playerWidgetVisibility.collect { mode -> applyPlayerMode(mode) }
                }
                launch {
                    viewModel.hidePlayerOnListenTab.collect { hide ->
                        binding.switchHidePlayerOnListenTab.isChecked = hide
                    }
                }
                launch {
                    viewModel.alwaysShowPlayerPill.collect { show ->
                        binding.switchAlwaysShowPlayerPill.isChecked = show
                    }
                }
            }
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
                binding.switchVerboseBackupLogging.isChecked = enabled
            }
        }
        binding.switchVerboseBackupLogging.setOnCheckedChangeListener { _, checked ->
            viewModel.setVerboseBackupLogging(checked)
        }

        // ── DB snapshot pruning ───────────────────────────────────────────────────────
        val switchDbPrune  = binding.switchDbPrune
        val rowDbPruneCount = binding.rowDbPruneCount
        val tvDbPruneCount  = binding.tvDbPruneCount
        val btnMinus        = binding.btnDbPruneMinus
        val btnPlus         = binding.btnDbPrunePlus

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
        val container = binding.storageVolumeContainer
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

    /** Updates the "Total used by TreeCast" summary line. */
    private fun renderTotalUsed(usageMap: Map<String, Long>) {
        val totalBytes = usageMap.values.sum()
        binding.tvTotalUsed.text =
            if (totalBytes == 0L) getString(R.string.common_placeholder_empty)
            else AppVolume.formatBytes(totalBytes)
    }

    private fun loadStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Total recorded time + last session (one-shot suspends)
                launch {
                    val totalMs = viewModel.getTotalRecordingTime()
                    binding.tvTotalRecordedTime.text =
                        if (totalMs > 0) formatGap(totalMs)
                        else getString(R.string.common_placeholder_empty)

                    val lastOpenedAt = viewModel.getLastSessionOpenedAt()
                    binding.tvLastOpened.text = if (lastOpenedAt != null) {
                        getString(R.string.settings_label_time_ago, formatGap(System.currentTimeMillis() - lastOpenedAt))
                    } else {
                        getString(R.string.settings_label_first_use)
                    }
                }

                // Listened count + total recordings — reactive, updates if user
                // listens to something while Settings is open
                launch {
                    viewModel.allRecordings.collect { recordings ->
                        binding.tvRecordingCount.text = recordings.size.toString()
                    }
                }

                // Topic count — reactive
                launch {
                    viewModel.allTopics.collect { topics ->
                        binding.tvTopicCount.text = topics.size.toString()
                    }
                }

                // Total storage — reactive, reuses the same flow that drives
                // the per-volume rows in the Storage card above
                launch {
                    viewModel.storageUsageByVolume.collect { usageMap ->
                        val totalBytes = usageMap.values.sum()
                        binding.tvStatsTotalStorage.text =
                            if (totalBytes > 0) AppVolume.formatBytes(totalBytes)
                            else getString(R.string.common_placeholder_empty)
                    }
                }
            }
        }
    }

    private fun formatGap(ms: Long): String {
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
    private fun buildVolumeRootUri(volumeUuid: String): Uri? {
        if (volumeUuid == StorageVolumeHelper.UUID_PRIMARY) return null
        return DocumentsContract.buildRootUri(
            "com.android.externalstorage.documents",
            volumeUuid
        )
    }
}