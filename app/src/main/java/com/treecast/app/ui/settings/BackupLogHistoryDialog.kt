package com.treecast.app.ui.settings

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.treecast.app.R
import com.treecast.app.data.entities.BackupLogEntity
import com.treecast.app.databinding.ItemBackupLogRowBinding
import com.treecast.app.ui.MainViewModel
import com.treecast.app.util.themeColor
import kotlinx.coroutines.launch

/**
 * Full all-volume backup history dialog.
 *
 * Displays every [BackupLogEntity] row (newest first) in a [RecyclerView].
 * Each row uses [ItemBackupLogRowBinding] via [bindLog] with
 * showVolumeLabel = true. Tapping a row opens [BackupLogDetailDialog].
 *
 * A "Clear all" text button sits right-aligned in the title row and is
 * suppressed while any backup is actively running (2E).
 *
 * Structure:
 *   ┌──────────────────────────────────────┐
 *   │  [drag handle]                       │
 *   │  Backup History        [Clear all]   │ ← fixed header
 *   ├──────────────────────────────────────┤
 *   │  [log row] …                         │ ← RecyclerView, fills height
 *   └──────────────────────────────────────┘
 *
 * Launched from [SettingsFragment] via the "View all history" button in
 * the backup section of the Settings card.
 */
class BackupLogHistoryDialog : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.Theme_TreeCast_BottomSheet

    companion object {
        const val TAG = "backup_log_history"

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
    private val backupLogAdapter = BackupLogAdapter()

    // ── Bottom-sheet behaviour ────────────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state         = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    // ── View construction ─────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx  = requireContext()
        val dm   = resources.displayMetrics
        val px8  = (8  * dm.density).toInt()
        val px12 = (12 * dm.density).toInt()
        val px16 = (16 * dm.density).toInt()
        val px24 = (24 * dm.density).toInt()
        val px1  = dm.density.toInt().coerceAtLeast(1)

        // ── Root ──────────────────────────────────────────────────────────────
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(ctx.themeColor(R.attr.colorSurfaceBase))
        }

        // ── Drag handle ───────────────────────────────────────────────────────
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                (32 * dm.density).toInt(),
                (4  * dm.density).toInt(),
            ).also {
                it.gravity      = Gravity.CENTER_HORIZONTAL
                it.topMargin    = px12
                it.bottomMargin = px8
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 2 * dm.density
                setColor(ctx.themeColor(R.attr.colorTextSecondary))
                alpha = 80
            }
        })

        // ── Title row: "Backup History" + "Clear all" button ─────────────────
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(px24, px16, px24, px16)
        }
        titleRow.addView(TextView(ctx).apply {
            text = getString(R.string.backup_log_history_title)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
        })
        titleRow.addView(TextView(ctx).apply {
            text     = getString(R.string.backup_log_clear_all_action)
            textSize = 13f
            setTextColor(ctx.themeColor(R.attr.colorError))
            setOnClickListener { onClearAllClicked(ctx) }
        })
        root.addView(titleRow)

        // ── Divider ───────────────────────────────────────────────────────────
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px1,
            ).also { it.setMargins(px24, 0, px24, 0) }
            setBackgroundColor(ctx.themeColor(R.attr.colorSurfaceElevated))
        })

        // ── RecyclerView — fills remaining height ─────────────────────────────
        root.addView(RecyclerView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0,
            ).also { it.weight = 1f }
            layoutManager  = LinearLayoutManager(ctx)
            adapter        = backupLogAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
        })

        return root
    }

    // ── Flow observation ──────────────────────────────────────────────────────

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.backupLogs.collect { logs ->
                    backupLogAdapter.submitList(logs)
                }
            }
        }
    }

    // ── Clear all handler (2E) ────────────────────────────────────────────────

    private fun onClearAllClicked(ctx: android.content.Context) {
        if (viewModel.backupUiState.value.isAnyRunning) {
            Toast.makeText(
                ctx,
                getString(R.string.backup_log_clear_active_warning),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.backup_log_clear_all_title))
            .setMessage(getString(R.string.backup_log_clear_all_message))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.backup_log_clear_action)) { _, _ ->
                viewModel.clearAllBackupLogs()
            }
            .show()
    }

    // ── RecyclerView adapter ──────────────────────────────────────────────────

    /**
     * [ListAdapter] for the all-volume backup log list.
     * showVolumeLabel = true because entries from multiple volumes are shown.
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
                showVolumeLabel = true,
                context         = requireContext(),
            )
            holder.binding.root.setOnClickListener {
                BackupLogDetailDialog.newInstance(log)
                    .show(parentFragmentManager, BackupLogDetailDialog.TAG)
            }
        }
    }
}