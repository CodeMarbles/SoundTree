package com.treecast.app.ui.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.treecast.app.R
import com.treecast.app.data.entities.BackupLogEntity
import com.treecast.app.databinding.ItemBackupLogRowBinding
import com.treecast.app.util.AppVolume
import com.treecast.app.util.themeColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Binds a [BackupLogEntity] to an [ItemBackupLogRowBinding] row view.
 *
 * Call sites:
 *  - [SettingsFragment.renderBackupMiniLog] — mini-list in the Settings backup card
 *  - [BackupLogHistoryDialog]              — full all-volume history dialog
 *  - [BackupTargetConfigDialog]            — per-volume recent backups section
 *
 * @param log             The entity to display.
 * @param showVolumeLabel True in multi-volume contexts (history dialog, Settings card).
 *                        False inside BackupTargetConfigDialog where the volume is already implicit.
 * @param context         Used for theme attribute resolution and string resources.
 */
fun ItemBackupLogRowBinding.bindLog(
    log: BackupLogEntity,
    showVolumeLabel: Boolean,
    context: Context,
) {
    tvVolumeLabel.visibility = if (showVolumeLabel) View.VISIBLE else View.GONE
    tvVolumeLabel.text = log.volumeLabel

    tvDate.text = SimpleDateFormat(
        context.getString(R.string.backup_log_date_format),
        Locale.getDefault()
    ).format(Date(log.startedAt))

    // Stats: "12 · 0" for a completed run, "…" while in progress
    tvStats.text = if (log.status != null) {
        "${log.filesCopied} · ${log.filesFailed}"
    } else {
        context.getString(R.string.backup_log_status_running_ellipsis)
    }

    // Status chip
    val (chipLabel, chipColor) = log.statusChipParams(context)
    tvStatusChip.text = chipLabel
    tvStatusChip.background = pillBackground(chipColor, context)
}

/**
 * Returns the (label, color) pair for the status chip given the log's [BackupLogEntity.status].
 * Exposed at package level so [BackupLogDetailDialog] can reuse the same mapping.
 */
fun BackupLogEntity.statusChipParams(context: Context): Pair<String, Int> = when (status) {
    BackupLogEntity.BackupStatus.SUCCESS ->
        context.getString(R.string.backup_log_status_success) to Color.parseColor("#2A7A3B")
    BackupLogEntity.BackupStatus.PARTIAL ->
        context.getString(R.string.backup_log_status_partial) to Color.parseColor("#8A5A00")
    BackupLogEntity.BackupStatus.FAILED  ->
        context.getString(R.string.backup_log_status_failed)  to context.themeColor(R.attr.colorError)
    else ->
        context.getString(R.string.backup_log_status_running) to context.themeColor(R.attr.colorTextSecondary)
}

/**
 * Creates a rounded-rectangle background drawable for a status chip pill.
 * cornerRadius = 4dp.
 */
fun pillBackground(color: Int, context: Context): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.resources.displayMetrics.density * 4f
        setColor(color)
    }

/**
 * Creates a circular background drawable for the event severity dot
 * in [BackupLogDetailDialog].
 */
fun circleBackground(color: Int): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

/**
 * Formats a byte count into a human-readable string.
 * Delegates to [AppVolume.formatBytes] for consistency with the rest of the app.
 */
fun formatBytes(bytes: Long): String = AppVolume.formatBytes(bytes)

/**
 * Formats a run duration given start and optional end epoch ms.
 * Returns "—" if the run is still in progress (endedAt == null).
 */
fun formatDuration(startedAt: Long, endedAt: Long?, context: Context): String {
    if (endedAt == null) return "—"
    val seconds = ((endedAt - startedAt) / 1000L).coerceAtLeast(0L)
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) context.getString(R.string.backup_log_duration_minutes, m, s)
    else context.getString(R.string.backup_log_duration_seconds, s)
}