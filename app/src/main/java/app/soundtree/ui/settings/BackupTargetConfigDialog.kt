package app.soundtree.ui.settings

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import app.soundtree.R
import app.soundtree.data.entities.BackupLogEntity
import app.soundtree.databinding.ItemBackupLogRowBinding
import app.soundtree.ui.BackupTargetUiState
import app.soundtree.ui.MainViewModel
import app.soundtree.ui.clearBackupLogsForVolume
import app.soundtree.ui.getBackupLogsForVolume
import app.soundtree.ui.removeBackupTarget
import app.soundtree.ui.setBackupIntervalHours
import app.soundtree.ui.setBackupOnConnectEnabled
import app.soundtree.ui.setBackupScheduledEnabled
import app.soundtree.ui.setExportMetadataEnabled
import app.soundtree.ui.triggerManualBackup
import app.soundtree.util.backupDirDisplayPath
import app.soundtree.util.themeColor
import kotlinx.coroutines.launch

/**
 * Bottom-sheet dialog for configuring an existing backup target.
 *
 * Structured as three fixed zones:
 *
 *   ┌─────────────────────────────────────┐
 *   │  [drag handle]                      │ ← always visible
 *   │  Volume info (name / uuid / path)   │
 *   │  Mount status                       │
 *   │  Back up now (when mounted)         │
 *   │  On-connect toggle                  │
 *   │  Scheduled toggle + chevron         │
 *   │    [interval picker — expandable]   │
 *   ├─────────────────────────────────────┤
 *   │  Backup History        [Clear]      │ ← scrollable RecyclerView zone;
 *   │  [log row] …                        │   fills remaining screen height
 *   │  [log row] …                        │
 *   ├─────────────────────────────────────┤
 *   │  [Remove backup target]             │ ← always visible footer
 *   └─────────────────────────────────────┘
 *
 * The scheduled section (2C): when scheduling is enabled, the interval
 * picker is collapsed behind a ▶/▼ chevron. The currently-selected interval
 * is shown inline as the row sublabel so it's visible without expanding.
 * Tapping anywhere on the label/chevron area toggles the picker; the Switch
 * widget itself consumes its own touch events and does not toggle expand.
 *
 * The history section (2D): a "Clear" text button sits right-aligned in the
 * section header. It is suppressed while a backup is actively running for
 * this volume.
 *
 * All mutations delegate directly to [MainViewModel] which owns the
 * WorkManager coordination via the repository.
 *
 * Launched from [SettingsFragment] when the user taps the gear icon on a
 * designated backup target row.
 */
class BackupTargetConfigDialog : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.Theme_SoundTree_BottomSheet

    companion object {
        const val TAG = "backup_target_config"

        private const val ARG_VOLUME_UUID     = "volume_uuid"
        private const val ARG_ON_CONNECT      = "on_connect_enabled"
        private const val ARG_SCHEDULED       = "scheduled_enabled"
        private const val ARG_INTERVAL_HOURS  = "interval_hours"
        private const val ARG_IS_MOUNTED      = "is_mounted"
        private const val ARG_DISPLAY_LABEL   = "display_label"
        private const val ARG_VOLUME_LABEL    = "volume_label"    // raw OS label; null when unnamed/unmounted
        private const val ARG_BACKUP_DIR_URI  = "backup_dir_uri"  // SAF tree URI; null when not yet chosen
        private const val ARG_EXPORT_METADATA = "export_metadata_enabled"

        /** Interval options shown in the selector.
         *  Labels live in @array/settings_backup_interval_labels — parallel by index. */
        val INTERVAL_HOURS = intArrayOf(1, 6, 12, 24, 48, 168)

        fun newInstance(state: BackupTargetUiState): BackupTargetConfigDialog =
            BackupTargetConfigDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_VOLUME_UUID,    state.entity.volumeUuid)
                    putBoolean(ARG_ON_CONNECT,    state.entity.onConnectEnabled)
                    putBoolean(ARG_SCHEDULED,     state.entity.scheduledEnabled)
                    putInt(ARG_INTERVAL_HOURS,    state.entity.intervalHours)
                    putBoolean(ARG_IS_MOUNTED,    state.isMounted)
                    putString(ARG_DISPLAY_LABEL,  state.displayLabel)
                    putString(ARG_VOLUME_LABEL,   state.volume?.label ?: state.entity.volumeLabel)
                    putString(ARG_BACKUP_DIR_URI, state.entity.backupDirUri)
                    putBoolean(ARG_EXPORT_METADATA, state.entity.exportMetadataEnabled)
                }
            }

        // Defined here rather than inside BackupLogAdapter because inner classes
        // cannot have companion objects in Kotlin.
        val LOG_DIFF = object : DiffUtil.ItemCallback<BackupLogEntity>() {
            override fun areItemsTheSame(a: BackupLogEntity, b: BackupLogEntity) =
                a.id == b.id
            override fun areContentsTheSame(a: BackupLogEntity, b: BackupLogEntity) =
                a == b
        }
    }

    private val viewModel: MainViewModel by activityViewModels()

    // Adapter is created eagerly; submitList is called once the Flow emits in onViewCreated.
    private val backupLogAdapter = BackupLogAdapter()

    // ── Args convenience ──────────────────────────────────────────────────────

    private val volumeUuid   get() = requireArguments().getString(ARG_VOLUME_UUID)!!
    private val isMounted    get() = requireArguments().getBoolean(ARG_IS_MOUNTED)
    private val displayLabel get() = requireArguments().getString(ARG_DISPLAY_LABEL)!!
    private val volumeLabel  get() = requireArguments().getString(ARG_VOLUME_LABEL)   // null = unnamed
    private val backupDirUri get() = requireArguments().getString(ARG_BACKUP_DIR_URI) // null = not set

    // ── Bottom-sheet behaviour ────────────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state         = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true   // drag down goes straight to dismiss; no half-height stop
        }
    }

    // ── View construction ─────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx  = requireContext()
        val args = requireArguments()
        val dm   = ctx.resources.displayMetrics
        val px4  = (4  * dm.density).toInt()
        val px8  = (8  * dm.density).toInt()
        val px12 = (12 * dm.density).toInt()
        val px16 = (16 * dm.density).toInt()
        val px24 = (24 * dm.density).toInt()

        // ── Root — fills the sheet top-to-bottom ──────────────────────────────
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(ctx.themeColor(R.attr.colorSurfaceBase))
        }

        // ══════════════════════════════════════════════════════════════════════
        // ZONE 1 — Fixed header: drag handle + volume info + controls
        // ══════════════════════════════════════════════════════════════════════

        val headerZone = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        // ── Drag handle ───────────────────────────────────────────────────────
        headerZone.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                (32 * dm.density).toInt(),
                (4  * dm.density).toInt(),
            ).also {
                it.gravity      = Gravity.CENTER_HORIZONTAL
                it.topMargin    = px12
                it.bottomMargin = px8
            }
            background = GradientDrawable().apply {
                shape        = GradientDrawable.RECTANGLE
                cornerRadius = 2 * dm.density
                setColor(ctx.themeColor(R.attr.colorTextSecondary))
                alpha = 80   // ~31 % opacity — standard Material handle treatment
            }
        })

        // ── Volume info header ────────────────────────────────────────────────
        val hasName = !volumeLabel.isNullOrEmpty()
        // Line 1: volume name, or "Unnamed Volume" in secondary/italic when absent
        headerZone.addView(TextView(ctx).apply {
            text = if (hasName) volumeLabel
            else getString(R.string.backup_volume_unnamed)
            textSize = 18f
            setTypeface(
                null,
                if (hasName) android.graphics.Typeface.BOLD
                else android.graphics.Typeface.ITALIC,
            )
            setTextColor(
                if (hasName) ctx.themeColor(R.attr.colorTextPrimary)
                else ctx.themeColor(R.attr.colorTextSecondary),
            )
            setPadding(px24, px8, px24, 2)
        })
        // Line 2: volume UUID — always shown, monospace for legibility
        headerZone.addView(TextView(ctx).apply {
            text     = volumeUuid
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(ctx.themeColor(R.attr.colorTextSecondary))
            setPadding(px24, 0, px24, 2)
        })
        // Line 3: root-relative backup path — omitted when SAF URI not yet set
        backupDirDisplayPath(backupDirUri)?.let { path ->
            headerZone.addView(TextView(ctx).apply {
                text     = path
                textSize = 12f
                setTextColor(ctx.themeColor(R.attr.colorTextSecondary))
                setPadding(px24, 0, px24, 4)
            })
        }

        // ── Mount status ──────────────────────────────────────────────────────
        headerZone.addView(TextView(ctx).apply {
            text = if (isMounted) getString(R.string.settings_backup_config_status_connected)
            else getString(R.string.settings_backup_status_not_connected)
            textSize = 13f
            setTextColor(
                if (isMounted) ctx.themeColor(R.attr.colorAccent)
                else ctx.themeColor(R.attr.colorTextSecondary)
            )
            setPadding(px24, 0, px24, px16)
        })

        headerZone.addView(divider(px24))

        // ── Back up now (only when mounted) ───────────────────────────────────
        if (isMounted) {
            headerZone.addView(MaterialButton(ctx).apply {
                text = getString(R.string.settings_backup_config_btn_back_up_now)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { it.setMargins(px24, px16, px24, 0) }
                setOnClickListener {
                    viewModel.triggerManualBackup(volumeUuid)
                    dismissAllowingStateLoss()
                }
            })
        }

        // ── On-connect toggle ─────────────────────────────────────────────────
        headerZone.addView(switchRow(
            ctx       = ctx,
            label     = getString(R.string.settings_backup_config_label_on_connect),
            sublabel  = getString(R.string.settings_backup_config_sublabel_on_connect),
            checked   = args.getBoolean(ARG_ON_CONNECT),
            px24      = px24,
            onChanged = { enabled -> viewModel.setBackupOnConnectEnabled(volumeUuid, enabled) },
        ))

        headerZone.addView(divider(px24))

        // ── Scheduled backup — toggle + collapsible interval picker (2C) ──────
        headerZone.addView(buildScheduledSection(ctx, args, px8, px12, px24))

        root.addView(headerZone)

        // ── Metadata export toggle ────────────────────────────────────────────────
        headerZone.addView(divider(px24))

        headerZone.addView(switchRow(
            ctx       = ctx,
            label     = getString(R.string.settings_backup_config_label_export_metadata),
            sublabel  = getString(R.string.settings_backup_config_sublabel_export_metadata),
            checked   = args.getBoolean(ARG_EXPORT_METADATA),
            px24      = px24,
            onChanged = { enabled -> viewModel.setExportMetadataEnabled(volumeUuid, enabled) },
        ))

        // ── History section header — title + Clear button (2D) ───────────────
        root.addView(divider(px24))

        root.addView(buildHistoryHeader(ctx, px4, px16, px24))

        // ══════════════════════════════════════════════════════════════════════
        // ZONE 2 — Scrollable history: RecyclerView fills remaining height
        // ══════════════════════════════════════════════════════════════════════

        val recycler = RecyclerView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,                     // height=0 + weight=1 → takes all remaining space
            ).also { it.weight = 1f }
            layoutManager = LinearLayoutManager(ctx)
            adapter       = backupLogAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        root.addView(recycler)

        // ══════════════════════════════════════════════════════════════════════
        // ZONE 3 — Fixed footer: destructive remove button
        // ══════════════════════════════════════════════════════════════════════

        val footerZone = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        footerZone.addView(divider(px24))

        footerZone.addView(MaterialButton(
            ctx,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = getString(R.string.settings_backup_config_btn_remove)
            setTextColor(ctx.themeColor(R.attr.colorError))
            strokeColor = android.content.res.ColorStateList.valueOf(
                ctx.themeColor(R.attr.colorError)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.setMargins(px24, px16, px24, px24) }
            setOnClickListener { confirmRemove() }
        })

        root.addView(footerZone)

        return root
    }

    // ── Scheduled section (2C) ────────────────────────────────────────────────

    /**
     * Builds the scheduled backup row + collapsible interval picker as a
     * self-contained [LinearLayout].
     *
     * Behaviour:
     *  - Switch OFF: plain row with static sublabel, chevron hidden, picker hidden.
     *  - Switch ON:  chevron visible; sublabel shows selected interval. Tapping
     *    the label/chevron area (not the Switch widget) toggles expand/collapse.
     *    The picker auto-expands the first time scheduling is enabled.
     *
     * The Switch widget consumes its own touch events so tapping it only fires
     * [Switch.setOnCheckedChangeListener], never the row's [View.setOnClickListener].
     */
    private fun buildScheduledSection(
        ctx: android.content.Context,
        args: Bundle,
        px8: Int,
        px12: Int,
        px24: Int,
    ): LinearLayout {
        val intervalLabels = ctx.resources.getStringArray(R.array.settings_backup_interval_labels)

        // Resolve a display label for a given interval value.
        fun labelForHours(hours: Int): String {
            val idx = INTERVAL_HOURS.indexOfFirst { it == hours }.takeIf { it >= 0 } ?: 3
            return intervalLabels[idx]
        }

        var scheduledEnabled      = args.getBoolean(ARG_SCHEDULED)
        var intervalExpanded      = false   // start open if already enabled
        var currentSelectedHours  = args.getInt(ARG_INTERVAL_HOURS)

        // ── Sublabel: static hint when off; selected interval name when on ────
        val tvSublabel = TextView(ctx).apply {
            text = if (scheduledEnabled) labelForHours(currentSelectedHours)
            else getString(R.string.settings_backup_config_sublabel_scheduled)
            textSize = 12f
            setTextColor(ctx.themeColor(R.attr.colorTextSecondary))
            setPadding(0, 2, 0, 0)
        }

        // ── Chevron: only visible when scheduling is enabled ──────────────────
        val tvChevron = android.widget.ImageView(ctx).apply {
            setImageResource(
                if (intervalExpanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right
            )
            setColorFilter(ctx.themeColor(R.attr.colorTextSecondary))
            val iconPx = (20 * ctx.resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(iconPx, iconPx).also {
                it.marginStart = px8
                it.marginEnd   = px8
            }
            visibility = if (scheduledEnabled) View.VISIBLE else View.GONE
        }

        // ── Interval picker — hidden when scheduling is off or collapsed ──────
        val intervalGroup = buildIntervalGroup(ctx, currentSelectedHours, px24) { hours ->
            currentSelectedHours = hours
            tvSublabel.text = labelForHours(hours)
            viewModel.setBackupIntervalHours(volumeUuid, hours)
        }
        intervalGroup.visibility =
            if (scheduledEnabled && intervalExpanded) View.VISIBLE else View.GONE

        // ── Expand/collapse toggle ────────────────────────────────────────────
        fun toggleExpanded() {
            intervalExpanded = !intervalExpanded
            tvChevron.setImageResource(
                if (intervalExpanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right
            )
            intervalGroup.visibility = if (intervalExpanded) View.VISIBLE else View.GONE
        }

        // ── Row layout ────────────────────────────────────────────────────────
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(px24, px12, px24, px12)
            isClickable = scheduledEnabled
            isFocusable = scheduledEnabled
            if (scheduledEnabled) setOnClickListener { toggleExpanded() }
        }

        val textBlock = LinearLayout(ctx).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
        }
        textBlock.addView(TextView(ctx).apply {
            text = getString(R.string.settings_backup_config_label_scheduled)
            textSize = 14f
            setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
        })
        textBlock.addView(tvSublabel)

        @Suppress("UseSwitchCompatOrMaterialCode")
        val switch = Switch(ctx).apply {
            isChecked = scheduledEnabled
            setOnCheckedChangeListener { _, isChecked ->
                scheduledEnabled = isChecked

                // Update sublabel: show interval name when on, static hint when off.
                tvSublabel.text = if (isChecked) labelForHours(currentSelectedHours)
                else getString(R.string.settings_backup_config_sublabel_scheduled)

                // Show/hide chevron.
                tvChevron.visibility = if (isChecked) View.VISIBLE else View.GONE

                if (isChecked) {
                    // Auto-expand when first enabling so the user can see the options.
                    intervalExpanded    = true
                    tvChevron.setImageResource(R.drawable.ic_chevron_down)
                    intervalGroup.visibility = View.VISIBLE
                } else {
                    intervalGroup.visibility = View.GONE
                }

                // The row is only tappable (for expand/collapse) when scheduling is on.
                row.isClickable = isChecked
                row.isFocusable = isChecked
                if (isChecked) row.setOnClickListener { toggleExpanded() }
                else           row.setOnClickListener(null)

                viewModel.setBackupScheduledEnabled(volumeUuid, isChecked)
            }
        }

        row.addView(textBlock)
        row.addView(tvChevron)
        row.addView(switch)

        // Wrap row + picker in a container so they can be added to headerZone together.
        return LinearLayout(ctx).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            addView(row)
            addView(intervalGroup)
        }
    }

    // ── History header with Clear button (2D) ─────────────────────────────────

    /**
     * Builds the "Backup History" section header row.
     *
     * Left: bold section label.
     * Right: "Clear" text button — disabled while a backup for this volume is
     * actively running, to avoid clearing a log row the worker is still writing.
     *
     * The active-backup guard is read from [MainViewModel.backupUiState] at the
     * moment the button is tapped; a [Toast] explains the block to the user.
     * A [MaterialAlertDialogBuilder] confirmation precedes the actual deletion.
     */
    private fun buildHistoryHeader(
        ctx: android.content.Context,
        px4: Int,
        px16: Int,
        px24: Int,
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setPadding(px24, px16, px24, px4)
        }

        row.addView(TextView(ctx).apply {
            text = getString(R.string.backup_log_history_title)
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
            letterSpacing = 0.06f
            layoutParams  = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
        })

        row.addView(TextView(ctx).apply {
            text     = getString(R.string.backup_log_clear_action)
            textSize = 13f
            setTextColor(ctx.themeColor(R.attr.colorError))
            setOnClickListener { onClearVolumeLogsClicked(ctx) }
        })

        return row
    }

    private fun onClearVolumeLogsClicked(ctx: android.content.Context) {
        val isRunning = viewModel.backupUiState.value.activeJobs
            .any { it.log.volumeUuid == volumeUuid }
        if (isRunning) {
            Toast.makeText(
                ctx,
                getString(R.string.backup_log_clear_active_warning),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.backup_log_clear_volume_title))
            .setMessage(getString(R.string.backup_log_clear_volume_message))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.backup_log_clear_action)) { _, _ ->
                viewModel.clearBackupLogsForVolume(volumeUuid)
            }
            .show()
    }

    // ── Flow observation ──────────────────────────────────────────────────────

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.getBackupLogsForVolume(volumeUuid).collect { logs ->
                    backupLogAdapter.submitList(logs)
                }
            }
        }
    }

    // ── Remove confirmation ───────────────────────────────────────────────────

    /**
     * Shows an OK/Cancel confirmation before removing the backup target.
     * Removal is a destructive, irreversible operation from the app's
     * perspective (the SAF permission is released and scheduled work is
     * cancelled), so we always require explicit confirmation.
     */
    private fun confirmRemove() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_backup_config_remove_confirm_title))
            .setMessage(getString(R.string.settings_backup_config_remove_confirm_message))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.settings_backup_config_remove_confirm_action)) { _, _ ->
                viewModel.removeBackupTarget(volumeUuid)
                dismissAllowingStateLoss()
            }
            .show()
    }

    // ── RecyclerView adapter ──────────────────────────────────────────────────

    /**
     * Simple [ListAdapter] for the per-volume backup log list.
     *
     * Uses [DiffUtil] via [ListAdapter] so that in-progress log rows update
     * live (the worker calls [app.soundtree.data.dao.BackupLogDao.updateStats]
     * incrementally) without flickering the entire list on each emission.
     *
     * Rows without a [BackupLogEntity.status] (i.e. currently running) are
     * rendered with the "Running…" chip by [bindLog] automatically.
     *
     * showVolumeLabel = false because the volume is already implicit — this
     * dialog is scoped to a single volume.
     */
    private inner class BackupLogAdapter :
        ListAdapter<BackupLogEntity, BackupLogAdapter.VH>(LOG_DIFF) {

        inner class VH(val binding: ItemBackupLogRowBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemBackupLogRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            ))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val log = getItem(position)
            holder.binding.bindLog(
                log             = log,
                showVolumeLabel = false,
                context         = requireContext(),
            )
            holder.binding.root.setOnClickListener {
                BackupLogDetailDialog.newInstance(log)
                    .show(parentFragmentManager, BackupLogDetailDialog.TAG)
            }
        }
    }

    // ── View helpers ──────────────────────────────────────────────────────────

    private fun switchRow(
        ctx: android.content.Context,
        label: String,
        sublabel: String,
        checked: Boolean,
        px24: Int,
        onChanged: (Boolean) -> Unit,
    ): LinearLayout {
        val dm   = ctx.resources.displayMetrics
        val px12 = (12 * dm.density).toInt()

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(px24, px12, px24, px12)
        }

        val textBlock = LinearLayout(ctx).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
        }
        textBlock.addView(TextView(ctx).apply {
            text = label
            textSize = 14f
            setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
        })
        textBlock.addView(TextView(ctx).apply {
            text = sublabel
            textSize = 12f
            setTextColor(ctx.themeColor(R.attr.colorTextSecondary))
            setPadding(0, 2, 0, 0)
        })

        @Suppress("UseSwitchCompatOrMaterialCode")
        val switch = Switch(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }

        row.addView(textBlock)
        row.addView(switch)
        return row
    }

    /**
     * Builds a [RadioGroup] with one option per [INTERVAL_HOURS] entry.
     * [currentHours] pre-selects the matching option (falls back to 24 h if
     * no match is found).
     */
    private fun buildIntervalGroup(
        ctx: android.content.Context,
        currentHours: Int,
        px24: Int,
        onSelected: (Int) -> Unit,
    ): RadioGroup {
        val dm   = ctx.resources.displayMetrics
        val px12 = (12 * dm.density).toInt()
        val intervalLabels = ctx.resources.getStringArray(R.array.settings_backup_interval_labels)

        return RadioGroup(ctx).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(px24, 0, px24, px12)

            INTERVAL_HOURS.forEachIndexed { i, hours ->
                addView(RadioButton(ctx).apply {
                    id        = View.generateViewId()
                    text      = intervalLabels[i]
                    tag       = hours
                    textSize  = 13f
                    setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
                    isChecked = (hours == currentHours)
                            || (i == 3 && currentHours !in INTERVAL_HOURS)  // fallback to Daily
                })
            }

            setOnCheckedChangeListener { group, checkedId ->
                val hours = group.findViewById<RadioButton>(checkedId)?.tag as? Int
                    ?: return@setOnCheckedChangeListener
                onSelected(hours)
            }
        }
    }

    private fun divider(px24: Int): View {
        val ctx = requireContext()
        val px1 = ctx.resources.displayMetrics.density.toInt().coerceAtLeast(1)
        return View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px1,
            ).also { it.setMargins(px24, 0, px24, 0) }
            setBackgroundColor(ctx.themeColor(R.attr.colorSurfaceElevated))
        }
    }
}