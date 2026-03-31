package com.treecast.app.ui.settings

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.button.MaterialButton
import com.treecast.app.R
import com.treecast.app.ui.BackupTargetUiState
import com.treecast.app.ui.MainViewModel
import com.treecast.app.util.themeColor

/**
 * Dialog for configuring an existing backup target.
 *
 * Displays:
 *  - Volume label + mount status
 *  - "Back up now" button (only when the volume is currently mounted)
 *  - On-connect trigger toggle
 *  - Scheduled trigger toggle + interval selector
 *  - Remove target button (destructive)
 *
 * All mutations delegate directly to [MainViewModel] which owns the
 * WorkManager coordination via the repository.
 *
 * Launched from [SettingsFragment] when the user taps the gear icon on a
 * designated backup target row.
 */
class BackupTargetConfigDialog : DialogFragment() {

    override fun getTheme(): Int = R.style.Theme_TreeCast_BottomSheet

    companion object {
        const val TAG = "backup_target_config"

        private const val ARG_VOLUME_UUID     = "volume_uuid"
        private const val ARG_ON_CONNECT      = "on_connect_enabled"
        private const val ARG_SCHEDULED       = "scheduled_enabled"
        private const val ARG_INTERVAL_HOURS  = "interval_hours"
        private const val ARG_IS_MOUNTED      = "is_mounted"
        private const val ARG_DISPLAY_LABEL   = "display_label"

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
                }
            }
    }

    private val viewModel: MainViewModel by activityViewModels()

    // Args convenience
    private val volumeUuid    get() = requireArguments().getString(ARG_VOLUME_UUID)!!
    private val isMounted     get() = requireArguments().getBoolean(ARG_IS_MOUNTED)
    private val displayLabel  get() = requireArguments().getString(ARG_DISPLAY_LABEL)!!

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
            setWindowAnimations(R.style.Animation_TreeCast_SlideUpDown)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx  = requireContext()
        val args = requireArguments()
        val px16 = (16 * ctx.resources.displayMetrics.density).toInt()
        val px24 = (24 * ctx.resources.displayMetrics.density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px24, 0, px24)
            setBackgroundColor(ctx.themeColor(R.attr.colorSurfaceBase))
        }

        // ── Title ─────────────────────────────────────────────────────────────
        root.addView(TextView(ctx).apply {
            text = displayLabel
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
            setPadding(px24, 0, px24, 4)
        })

        root.addView(TextView(ctx).apply {
            text = if (isMounted) getString(R.string.settings_backup_config_status_connected)
            else getString(R.string.settings_backup_status_not_connected)
            textSize = 13f
            setTextColor(
                if (isMounted) ctx.themeColor(R.attr.colorAccent)
                else ctx.themeColor(R.attr.colorTextSecondary)
            )
            setPadding(px24, 0, px24, px16)
        })

        root.addView(divider(px24))

        // ── Back up now ───────────────────────────────────────────────────────
        if (isMounted) {
            root.addView(MaterialButton(ctx).apply {
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
        root.addView(switchRow(
            ctx        = ctx,
            label      = getString(R.string.settings_backup_config_label_on_connect),
            sublabel   = getString(R.string.settings_backup_config_sublabel_on_connect),
            checked    = args.getBoolean(ARG_ON_CONNECT),
            px24       = px24,
            onChanged  = { enabled -> viewModel.setBackupOnConnectEnabled(volumeUuid, enabled) },
        ))

        root.addView(divider(px24))

        // ── Scheduled toggle ──────────────────────────────────────────────────
        var scheduledEnabled = args.getBoolean(ARG_SCHEDULED)
        val currentInterval  = args.getInt(ARG_INTERVAL_HOURS)

        // The interval radio group is shown/hidden based on the scheduled toggle.
        val intervalGroup = buildIntervalGroup(ctx, currentInterval, px24) { hours ->
            viewModel.setBackupIntervalHours(volumeUuid, hours)
        }
        intervalGroup.visibility = if (scheduledEnabled) View.VISIBLE else View.GONE

        root.addView(switchRow(
            ctx       = ctx,
            label     = getString(R.string.settings_backup_config_label_scheduled),
            sublabel  = getString(R.string.settings_backup_config_sublabel_scheduled),
            checked   = scheduledEnabled,
            px24      = px24,
            onChanged = { enabled ->
                scheduledEnabled = enabled
                intervalGroup.visibility = if (enabled) View.VISIBLE else View.GONE
                viewModel.setBackupScheduledEnabled(volumeUuid, enabled)
            },
        ))

        root.addView(intervalGroup)

        root.addView(divider(px24))

        // ── Remove ────────────────────────────────────────────────────────────
        root.addView(MaterialButton(
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
            ).also { it.setMargins(px24, px16, px24, 0) }
            setOnClickListener {
                viewModel.removeBackupTarget(volumeUuid)
                dismissAllowingStateLoss()
            }
        })

        return root
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun switchRow(
        ctx: android.content.Context,
        label: String,
        sublabel: String,
        checked: Boolean,
        px24: Int,
        onChanged: (Boolean) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(px24, (12 * ctx.resources.displayMetrics.density).toInt(),
                px24, (12 * ctx.resources.displayMetrics.density).toInt())
        }

        val textBlock = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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
     * [currentHours] pre-selects the matching option (falls back to 24h if
     * no match is found).
     */
    private fun buildIntervalGroup(
        ctx: android.content.Context,
        currentHours: Int,
        px24: Int,
        onSelected: (Int) -> Unit,
    ): RadioGroup {
        val px12 = (12 * ctx.resources.displayMetrics.density).toInt()
        val intervalLabels = ctx.resources.getStringArray(R.array.settings_backup_interval_labels)

        return RadioGroup(ctx).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(px24, 0, px24, px12)

            INTERVAL_HOURS.forEachIndexed { i, hours ->
                addView(RadioButton(ctx).apply {
                    id       = View.generateViewId()
                    text     = intervalLabels[i]
                    tag      = hours
                    textSize = 13f
                    setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
                    isChecked = (hours == currentHours)
                            || (i == 3 && currentHours !in INTERVAL_HOURS) // fallback to Daily
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
                LinearLayout.LayoutParams.MATCH_PARENT, px1
            ).also { it.setMargins(px24, 0, px24, 0) }
            setBackgroundColor(ctx.themeColor(R.attr.colorSurfaceElevated))
        }
    }
}