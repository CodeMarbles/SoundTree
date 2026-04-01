package com.treecast.app.ui.settings

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.treecast.app.R
import com.treecast.app.databinding.ItemBackupLogRowBinding
import com.treecast.app.ui.MainViewModel
import com.treecast.app.util.themeColor
import kotlinx.coroutines.launch

/**
 * Full all-volume backup history dialog.
 *
 * Displays every [com.treecast.app.data.entities.BackupLogEntity] row
 * (newest first) in a scrollable list. Each row uses [ItemBackupLogRowBinding]
 * via [bindLog] with showVolumeLabel = true. Tapping a row opens
 * [BackupLogDetailDialog].
 *
 * Built entirely programmatically to stay consistent with the project's
 * dialog style (BackupTargetConfigDialog pattern).
 *
 * Launched from [SettingsFragment] via the "View all history" button in
 * the backup section of the Settings card.
 */
class BackupLogHistoryDialog : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.Theme_TreeCast_BottomSheet

    companion object {
        const val TAG = "backup_log_history"
    }

    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx  = requireContext()
        val dm   = resources.displayMetrics
        val px24 = (24 * dm.density).toInt()
        val px16 = (16 * dm.density).toInt()
        val px1  = dm.density.toInt().coerceAtLeast(1)

        // ── Root container ────────────────────────────────────────────────────
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // ── Title ─────────────────────────────────────────────────────────────
        root.addView(TextView(ctx).apply {
            text     = getString(R.string.backup_log_history_title)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
            setPadding(px24, px24, px24, px16)
        })

        // ── Divider ───────────────────────────────────────────────────────────
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px1
            ).also {
                it.marginStart = px24
                it.marginEnd   = px24
            }
            setBackgroundColor(ctx.themeColor(R.attr.colorSurfaceElevated))
        })

        // ── Log list container (inside a ScrollView) ──────────────────────────
        val logContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(
            ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                addView(logContainer)
            }
        )

        // ── Observe and render ────────────────────────────────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.backupLogs.collect { logs ->
                    logContainer.removeAllViews()

                    if (logs.isEmpty()) {
                        logContainer.addView(TextView(ctx).apply {
                            text     = getString(R.string.backup_log_history_empty)
                            textSize = 14f
                            setTextColor(ctx.themeColor(R.attr.colorTextSecondary))
                            gravity  = Gravity.CENTER
                            setPadding(px24, px24 * 2, px24, px24 * 2)
                        })
                        return@collect
                    }

                    logs.forEachIndexed { index, log ->
                        val rowBinding = ItemBackupLogRowBinding.inflate(
                            LayoutInflater.from(ctx), logContainer, false
                        )
                        rowBinding.bindLog(log, showVolumeLabel = true, context = ctx)
                        rowBinding.root.setOnClickListener {
                            BackupLogDetailDialog.newInstance(log)
                                .show(parentFragmentManager, BackupLogDetailDialog.TAG)
                        }
                        logContainer.addView(rowBinding.root)

                        // Thin divider between rows (not after the last one)
                        if (index < logs.lastIndex) {
                            logContainer.addView(View(ctx).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, px1
                                ).also {
                                    it.marginStart = px24
                                    it.marginEnd   = px24
                                }
                                setBackgroundColor(ctx.themeColor(R.attr.colorSurfaceElevated))
                            })
                        }
                    }
                }
            }
        }

        return root
    }
}