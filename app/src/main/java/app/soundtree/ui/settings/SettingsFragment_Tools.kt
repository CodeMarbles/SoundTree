package app.soundtree.ui.settings

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import app.soundtree.R
import app.soundtree.service.RecordingService
import app.soundtree.ui.MainViewModel
import app.soundtree.ui.ProcessingStatus
import app.soundtree.ui.cancelWaveformProcessing
import app.soundtree.ui.clearCompletedWaveformJobs
import app.soundtree.ui.labelForJob
import app.soundtree.ui.migrateRecordingStructure
import app.soundtree.ui.reprocessAllWaveforms
import app.soundtree.ui.setDevOptions
import app.soundtree.ui.setFutureMode
import app.soundtree.ui.setSimulateWaveformLoading
import app.soundtree.ui.tickProcessingRefresh
import app.soundtree.util.themeColor
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Processing section ────────────────────────────────────────────────────────

internal fun SettingsFragment.setupProcessingSection() {
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

internal fun SettingsFragment.renderProcessingStatus(status: ProcessingStatus) {
    val hasActive  = status.active != null
    val hasPending = status.pending.isNotEmpty()
    val hasRecent  = status.recent.isNotEmpty()
    val isActive   = hasActive || hasPending
    val isIdle     = !hasActive && !hasPending && !hasRecent

    // When we go idle, reset expansion so the next pass starts fresh.
    if (isIdle) processingOutputExpanded = true

    // ── Top-bar controls ──────────────────────────────────────────────────────
    binding.groupProcessingJobs.processingSpinner.visibility         = if (hasActive) View.VISIBLE else View.GONE
    binding.groupProcessingJobs.tvProcessingIdle.visibility          = if (isIdle)   View.VISIBLE else View.GONE
    binding.groupProcessingJobs.btnToggleProcessingOutput.visibility = if (!isIdle)  View.VISIBLE else View.GONE

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
    binding.groupProcessingJobs.btnCancelWaveforms.visibility     = if (isActive)  View.VISIBLE else View.GONE
    binding.groupProcessingJobs.btnClearWaveformOutput.visibility = if (hasRecent) View.VISIBLE else View.GONE

    // ── Expandable output section ─────────────────────────────────────────────
    renderProcessingOutput(processingOutputExpanded)
}

/** Applies the current expanded/collapsed state to the output container. */
internal fun SettingsFragment.renderProcessingOutput(expanded: Boolean) {
    val hasAnythingToShow =
        binding.groupProcessingJobs.containerRecent.visibility          == View.VISIBLE ||
                binding.groupProcessingJobs.btnCancelWaveforms.visibility       == View.VISIBLE ||
                binding.groupProcessingJobs.btnClearWaveformOutput.visibility   == View.VISIBLE
    binding.groupProcessingJobs.containerProcessingOutput.visibility =
        if (expanded && hasAnythingToShow) View.VISIBLE else View.GONE

    // Icon update lives here — the single place that knows the expanded state.
    binding.groupProcessingJobs.btnToggleProcessingOutput.setImageResource(
        if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
    )
}

internal fun SettingsFragment.formatCompletionTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

// ── Waveform job list adapter ─────────────────────────────────────────────────

internal data class WaveformJobAdapterRow(
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
 *
 * Originally a private inner class of SettingsFragment; extracted here since
 * extension files cannot declare inner classes on another class.
 */
internal class WaveformJobAdapter : RecyclerView.Adapter<WaveformJobAdapter.VH>() {

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

// ── Migration section ─────────────────────────────────────────────────────────

/**
 * Wires the recording folder migration section in the Tools tab.
 *
 * Visibility of the entire section is gated on the Future Mode flag so it
 * can be removed cleanly once the install base has been migrated.
 *
 * Guards against starting a migration while a recording is active — the
 * migrator would be moving files that RecordingService has open.
 */
internal fun SettingsFragment.setupMigrationSection() {
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

internal fun SettingsFragment.renderMigrationState(state: MainViewModel.MigrationState) {
    when (state) {
        is MainViewModel.MigrationState.Idle -> {
            binding.groupMigration.progressMigration.visibility   = View.GONE
            binding.groupMigration.tvMigrationStatus.visibility   = View.GONE
            binding.groupMigration.btnMigrateRecordings.isEnabled = true
        }
        is MainViewModel.MigrationState.Running -> {
            binding.groupMigration.progressMigration.visibility   = View.VISIBLE
            binding.groupMigration.tvMigrationStatus.visibility   = View.VISIBLE
            binding.groupMigration.tvMigrationStatus.text         = if (state.currentFile.isNotEmpty()) {
                getString(R.string.settings_migration_running, state.currentFile)
            } else {
                getString(R.string.settings_migration_scanning)
            }
            binding.groupMigration.btnMigrateRecordings.isEnabled = false
        }
        is MainViewModel.MigrationState.Done -> {
            binding.groupMigration.progressMigration.visibility   = View.GONE
            binding.groupMigration.tvMigrationStatus.visibility   = View.VISIBLE
            binding.groupMigration.tvMigrationStatus.text         = when {
                state.moved == 0 && state.failed == 0 ->
                    getString(R.string.settings_migration_done_none)
                state.failed == 0 ->
                    getString(R.string.settings_migration_done_clean, state.moved)
                else ->
                    getString(R.string.settings_migration_done, state.moved, state.failed)
            }
            binding.groupMigration.btnMigrateRecordings.isEnabled = true
        }
    }
}

// ── Dev options section ───────────────────────────────────────────────────────

internal fun SettingsFragment.setupDevOptionsSection() {
    // ── Future Mode switch ────────────────────────────────────────────────────
    binding.groupDevOptions.switchFutureMode.isChecked = viewModel.futureMode.value
    binding.groupDevOptions.switchFutureMode.setOnCheckedChangeListener { _, checked ->
        viewModel.setFutureMode(checked)
    }

    // ── Simulate Waveform Loading switch ──────────────────────────────────────
    binding.groupDevOptions.switchSimulateWfLoading.isChecked = viewModel.simulateWaveformLoading.value
    binding.groupDevOptions.switchSimulateWfLoading.setOnCheckedChangeListener { _, checked ->
        viewModel.setSimulateWaveformLoading(checked)
    }

    // ── Storage Probe button ──────────────────────────────────────────────
    binding.groupDevOptions.btnStorageProbe.setOnClickListener {
        StorageProbeDialogFragment.newInstance()
            .show(childFragmentManager, StorageProbeDialogFragment.TAG)
    }

    // ── Storage Event Log button ──────────────────────────────────────────────
    binding.groupDevOptions.btnStorageEventLog.setOnClickListener {
        StorageMountEventLogDialog()
            .show(childFragmentManager, StorageMountEventLogDialog.TAG)
    }

    // ── Show/hide the Developer Options card based on devOptions flag ─────────
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.devOptions.collect { enabled ->
                val vis = if (enabled) View.VISIBLE else View.GONE
                binding.groupDevOptions.labelDeveloperOptionsSection.visibility  = vis
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