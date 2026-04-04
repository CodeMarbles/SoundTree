@file:OptIn(ExperimentalCoroutinesApi::class)

package app.treecast.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import app.treecast.R
import app.treecast.data.entities.BackupLogEntity
import app.treecast.data.entities.BackupLogEventEntity
import app.treecast.databinding.DialogBackupLogDetailBinding
import app.treecast.databinding.ItemBackupLogEventBinding
import app.treecast.ui.MainViewModel
import app.treecast.ui.getBackupLog
import app.treecast.ui.getBackupLogEvents
import app.treecast.ui.getBackupLogProblems
import app.treecast.util.backupDirDisplayPath
import app.treecast.util.themeColor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bottom-sheet dialog showing the complete detail of a single [BackupLogEntity].
 *
 * Header: volume label, status chip, date.
 * Metadata grid: trigger, duration, files copied/skipped/failed, bytes copied, DB status.
 * Events: scrollable list of [BackupLogEventEntity] rows.
 *   - Default: WARNING + ERROR rows only (user-visible problems).
 *   - "Show milestones" toggle: includes INFO rows (verbose log output).
 *
 * All entity data is passed via Bundle args to avoid adding a per-ID DAO query.
 * Events are observed reactively via [MainViewModel.getBackupLogEvents] /
 * [MainViewModel.getBackupLogProblems] so any new events written by an
 * in-progress run appear without reopening the dialog.
 *
 * Launched from [BackupLogHistoryDialog] and [BackupTargetConfigDialog].
 */
class BackupLogDetailDialog : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.Theme_TreeCast_BottomSheet

    companion object {
        const val TAG = "backup_log_detail"

        private const val ARG_LOG_ID         = "log_id"
        private const val ARG_VOLUME_LABEL   = "volume_label"
        private const val ARG_VOLUME_UUID    = "volume_uuid"
        private const val ARG_BACKUP_DIR_URI = "backup_dir_uri"
        private const val ARG_STARTED_AT     = "started_at"
        private const val ARG_ENDED_AT       = "ended_at"      // 0L when null
        private const val ARG_STATUS         = "status"
        private const val ARG_TRIGGER        = "trigger"
        private const val ARG_FILES_COPIED   = "files_copied"
        private const val ARG_FILES_SKIPPED  = "files_skipped"
        private const val ARG_FILES_FAILED   = "files_failed"
        private const val ARG_BYTES_COPIED   = "bytes_copied"
        private const val ARG_DB_BACKED_UP   = "db_backed_up"
        private const val ARG_ERROR_MESSAGE  = "error_message"

        fun newInstance(log: BackupLogEntity) = BackupLogDetailDialog().apply {
            arguments = Bundle().apply {
                putLong(ARG_LOG_ID,          log.id)
                putString(ARG_VOLUME_LABEL,   log.volumeLabel)
                putString(ARG_VOLUME_UUID,    log.volumeUuid)
                putString(ARG_BACKUP_DIR_URI, log.backupDirUri)
                putLong(ARG_STARTED_AT,      log.startedAt)
                putLong(ARG_ENDED_AT,        log.endedAt ?: 0L)
                putString(ARG_STATUS,        log.status)
                putString(ARG_TRIGGER,       log.trigger)
                putInt(ARG_FILES_COPIED,     log.filesCopied)
                putInt(ARG_FILES_SKIPPED,    log.filesSkipped)
                putInt(ARG_FILES_FAILED,     log.filesFailed)
                putLong(ARG_BYTES_COPIED,    log.bytesCopied)
                putBoolean(ARG_DB_BACKED_UP, log.dbBackedUp)
                putString(ARG_ERROR_MESSAGE, log.errorMessage)
            }
        }
    }

    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: DialogBackupLogDetailBinding? = null
    private val binding get() = _binding!!

    // Drives the events filter toggle; flatMapLatest switches flows reactively.
    private val showInfoFlow = MutableStateFlow(false)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogBackupLogDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args  = requireArguments()
        val logId = args.getLong(ARG_LOG_ID)

        // Reconstruct a seed entity from the bundle args so the dialog isn't
        // blank for the brief moment before the first DB emission arrives.
        val seedLog = BackupLogEntity(
            id               = logId,
            backupTargetUuid = null,
            volumeUuid       = args.getString(ARG_VOLUME_UUID, ""),
            volumeLabel      = args.getString(ARG_VOLUME_LABEL, ""),
            backupDirUri     = args.getString(ARG_BACKUP_DIR_URI, ""),
            trigger          = args.getString(ARG_TRIGGER, ""),
            status           = args.getString(ARG_STATUS),
            startedAt        = args.getLong(ARG_STARTED_AT),
            endedAt          = args.getLong(ARG_ENDED_AT).takeIf { it != 0L },
            filesCopied      = args.getInt(ARG_FILES_COPIED),
            filesSkipped     = args.getInt(ARG_FILES_SKIPPED),
            filesFailed      = args.getInt(ARG_FILES_FAILED),
            bytesCopied      = args.getLong(ARG_BYTES_COPIED),
            dbBackedUp       = args.getBoolean(ARG_DB_BACKED_UP),
            errorMessage     = args.getString(ARG_ERROR_MESSAGE),
        )
        bindHeader(seedLog)
        bindMetadata(seedLog)

        binding.btnToggleInfo.setOnClickListener {
            val next = !showInfoFlow.value
            showInfoFlow.value = next
            binding.btnToggleInfo.text = getString(
                if (next) R.string.backup_log_detail_btn_hide_info
                else      R.string.backup_log_detail_btn_show_info
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // ── Live header + metadata ─────────────────────────────────────
                launch {
                    viewModel.getBackupLog(logId).collect { log ->
                        if (log != null) {
                            bindHeader(log)
                            bindMetadata(log)
                        }
                    }
                }

                // ── Events list ───────────────────────────────────────────────
                launch {
                    showInfoFlow
                        .flatMapLatest { showInfo ->
                            if (showInfo) viewModel.getBackupLogEvents(logId)
                            else          viewModel.getBackupLogProblems(logId)
                        }
                        .collectLatest { events -> bindEvents(events) }
                }
            }
        }
    }

    // ── Header ────────────────────────────────────────────────────────────────

    /**
     * Populates the three-line volume identity block and status chip.
     *
     * Line 1 (tvDetailVolume):     volume label, bold 18sp
     * Line 2 (tvDetailVolumeUuid): volume UUID, monospace secondary
     * Line 3 (tvDetailVolumeDir):  root-relative backup path — hidden when
     *                              the URI is absent or unparseable
     *
     * The layout mirrors the volume info header in [BackupTargetConfigDialog].
     */
    private fun bindHeader(log: BackupLogEntity) {
        binding.tvDetailVolume.text     = log.volumeLabel.ifBlank { log.volumeUuid }
        binding.tvDetailVolumeUuid.text = log.volumeUuid

        val path = backupDirDisplayPath(log.backupDirUri)
        if (path != null) {
            binding.tvDetailVolumeDir.text       = path
            binding.tvDetailVolumeDir.visibility = View.VISIBLE
        } else {
            binding.tvDetailVolumeDir.visibility = View.GONE
        }

        binding.tvDetailDate.text = formatDateTime(log.startedAt)

        val (chipLabel, chipColor) = log.statusChipParams(requireContext())
        binding.tvDetailStatusChip.text       = chipLabel
        binding.tvDetailStatusChip.background = pillBackground(chipColor, requireContext())
    }

    // ── Metadata ──────────────────────────────────────────────────────────────

    private fun bindMetadata(log: BackupLogEntity) {
        binding.tvDetailTrigger.text  = formatTrigger(log.trigger ?: "")
        binding.tvDetailDuration.text = formatDuration(log.startedAt, log.endedAt, requireContext())
        binding.tvDetailFiles.text = getString(
            R.string.backup_log_detail_files_summary,
            log.filesCopied, log.filesSkipped, log.filesFailed
        )
        binding.tvDetailBytes.text = formatBytes(log.bytesCopied)
        binding.tvDetailDb.text = getString(
            if (log.dbBackedUp) R.string.backup_log_detail_db_backed_up
            else                R.string.backup_log_detail_db_not_backed_up
        )

        if (log.status == BackupLogEntity.BackupStatus.FAILED && !log.errorMessage.isNullOrBlank()) {
            binding.rowDetailErrorMessage.visibility = View.VISIBLE
            binding.tvDetailErrorMessage.text = log.errorMessage
        } else {
            binding.rowDetailErrorMessage.visibility = View.GONE
        }
    }

    // ── Events ────────────────────────────────────────────────────────────────

    private fun bindEvents(events: List<BackupLogEventEntity>) {
        val container = binding.containerEvents
        container.removeAllViews()

        if (events.isEmpty()) {
            binding.tvEventsEmpty.visibility = View.VISIBLE
            return
        }
        binding.tvEventsEmpty.visibility = View.GONE

        events.forEach { event ->
            val itemBinding = ItemBackupLogEventBinding.inflate(
                layoutInflater, container, false
            )

            itemBinding.tvMessage.text = event.message
            itemBinding.tvTime.text    = formatTime(event.occurredAt)

            val sourcePath = event.sourcePath
            if (!sourcePath.isNullOrBlank()) {
                itemBinding.tvSourcePath.visibility = View.VISIBLE
                // Display only the filename to keep the row compact
                itemBinding.tvSourcePath.text = sourcePath.substringAfterLast('/')
            }

            val dotColor = when (event.severity) {
                BackupLogEventEntity.EventType.INFO    -> Color.parseColor("#4A90D9")
                BackupLogEventEntity.EventType.WARNING -> Color.parseColor("#CC8800")
                BackupLogEventEntity.EventType.ERROR   ->
                    requireContext().themeColor(R.attr.colorError)
                else -> requireContext().themeColor(R.attr.colorTextSecondary)
            }
            itemBinding.viewSeverityDot.background = circleBackground(dotColor)

            container.addView(itemBinding.root)
        }
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    private fun formatTrigger(trigger: String) = when (trigger) {
        BackupLogEntity.BackupTrigger.ON_CONNECT -> getString(R.string.backup_log_trigger_on_connect)
        BackupLogEntity.BackupTrigger.SCHEDULED  -> getString(R.string.backup_log_trigger_scheduled)
        BackupLogEntity.BackupTrigger.MANUAL     -> getString(R.string.backup_log_trigger_manual)
        else -> trigger
    }

    private fun formatDateTime(epochMs: Long): String =
        SimpleDateFormat(
            getString(R.string.backup_log_date_format),
            Locale.getDefault()
        ).format(Date(epochMs))

    private fun formatTime(epochMs: Long): String =
        SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(epochMs))

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}