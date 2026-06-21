package app.soundtree.ui.settings

// ─────────────────────────────────────────────────────────────────────────────
// BackupSetupDialog.kt
//
// A staging bottom-sheet that mediates between the user and the SAF directory
// picker for all "add a new backup destination" flows.
//
// ## Modes
//
// ONE_TIME — launched from the "Back Up to Folder…" button in the Storage tab.
//   • Source summary: total recordings + approximate size from the DB.
//   • No volume-space context (the user is picking any arbitrary SAF path).
//   • Creates a BackupTargetEntity with both triggers disabled (manual-only).
//   • Immediately enqueues a one-time WorkManager backup on confirm.
//   • Footer note: tells the user the destination is saved and configurable.
//
// ADD_VOLUME_DIRECTORY — launched from "Add another directory" inside
//   BackupTargetConfigDialog when the volume is mounted.
//   • Destination context: free space on the volume + estimated backup size.
//   • Shows a space-warning chip when estimated size exceeds free bytes.
//   • Creates a BackupTargetEntity with default triggers (on-connect + 24h).
//   • Does NOT immediately run a backup — the user can kick one off from
//     the gear dialog as usual.
//
// ## SAF launcher ownership
//
// The ActivityResultLauncher is registered on this dialog fragment, removing
// the need for SettingsFragment to own `pendingBackupVolumeUuid` or its own
// openDocumentTree launcher for the backup flow.
//
// ## Flow
//
//   Button tap → dialog opens (shows source/dest summary)
//               → "Select Folder" → SAF picker → directory row updates
//               → "Back Up" / "Add Directory" enabled
//               → confirm → permission persisted → target inserted → backup
//                 enqueued (ONE_TIME only) → toast → dismiss
// ─────────────────────────────────────────────────────────────────────────────

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import app.soundtree.R
import app.soundtree.storage.AppVolume
import app.soundtree.storage.StorageVolumeHelper
import app.soundtree.ui.MainViewModel
import app.soundtree.ui.addBackupTarget
import app.soundtree.ui.addOneTimeBackupTarget
import app.soundtree.util.backupDirDisplayPath
import app.soundtree.util.themeColor
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

class BackupSetupDialog : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.Theme_SoundTree_BottomSheet

    // ── Mode ──────────────────────────────────────────────────────────────────

    /**
     * Controls which information panel is shown and what happens on confirm.
     *
     * ONE_TIME        — "Back Up to Folder…": manual-only target, immediate backup.
     * ADD_VOLUME_DIR  — "Add another directory": recurring-enabled target, no
     *                   immediate backup (the user triggers via gear dialog).
     */
    enum class Mode { ONE_TIME, ADD_VOLUME_DIR }

    companion object {
        const val TAG = "backup_setup_dialog"

        private const val ARG_MODE         = "mode"
        private const val ARG_VOLUME_UUID  = "volume_uuid"   // ADD_VOLUME_DIR only; nullable
        private const val ARG_VOLUME_LABEL = "volume_label"  // ADD_VOLUME_DIR only; nullable
        private const val ARG_FREE_BYTES   = "free_bytes"    // ADD_VOLUME_DIR only; -1 = unknown
        private const val ARG_SOURCE_BYTES = "source_bytes"  // both modes; -1 = unknown

        /** One-time SAF backup — no volume association. */
        fun newInstanceOneTime(sourceTotalBytes: Long): BackupSetupDialog =
            BackupSetupDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, Mode.ONE_TIME.name)
                    putLong(ARG_SOURCE_BYTES, sourceTotalBytes)
                }
            }

        /**
         * Add an additional backup directory for a volume that already has at
         * least one target configured.
         *
         * [volume] is the mounted AppVolume — free space and label come from it.
         * [sourceTotalBytes] is the DB-derived total bytes of recordings on the
         * source (all volumes summed, since the backup copies everything).
         */
        fun newInstanceAddVolumeDir(
            volume: AppVolume,
            sourceTotalBytes: Long,
        ): BackupSetupDialog =
            BackupSetupDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE,         Mode.ADD_VOLUME_DIR.name)
                    putString(ARG_VOLUME_UUID,  volume.uuid)
                    putString(ARG_VOLUME_LABEL, volume.label)
                    putLong(ARG_FREE_BYTES,     volume.freeBytes)
                    putLong(ARG_SOURCE_BYTES,   sourceTotalBytes)
                }
            }
    }

    // ── Arg convenience ───────────────────────────────────────────────────────

    private val mode: Mode
        get() = Mode.valueOf(requireArguments().getString(ARG_MODE)!!)
    private val volumeUuid: String?
        get() = requireArguments().getString(ARG_VOLUME_UUID)
    private val volumeLabel: String?
        get() = requireArguments().getString(ARG_VOLUME_LABEL)
    private val freeBytes: Long
        get() = requireArguments().getLong(ARG_FREE_BYTES, -1L)
    private val sourceTotalBytes: Long
        get() = requireArguments().getLong(ARG_SOURCE_BYTES, -1L)

    // ── State ─────────────────────────────────────────────────────────────────

    /** URI chosen by the user via the SAF picker; null until picker returns. */
    private var selectedUri: Uri? = null

    /**
     * Whether to export JSON metadata sidecars during this backup.
     * Defaults to true — erring on the side of recoverability.
     * Persisted to [BackupTargetEntity.exportMetadataEnabled] on confirm.
     * Only used in [Mode.ONE_TIME]; ADD_VOLUME_DIR uses the gear dialog flow.
     */
    private var exportMetadata: Boolean = true

    // ── ViewModel ─────────────────────────────────────────────────────────────

    private val viewModel: MainViewModel by activityViewModels()

    // ── SAF launcher ──────────────────────────────────────────────────────────

    /**
     * SAF directory picker, registered on this dialog fragment so that
     * SettingsFragment no longer needs to own the backup-add launcher or
     * the pendingBackupVolumeUuid field.
     *
     * Initial URI hint: for ADD_VOLUME_DIR the picker opens at the volume root;
     * for ONE_TIME it opens at the system default (no hint).
     *
     * On a successful result the permission is persisted immediately and the
     * dialog UI updates to show the chosen path. A null result (user cancelled)
     * is silently ignored.
     */
    private val openDocumentTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        selectedUri = uri
        updateDirRow(uri)
        updateConfirmButton()
    }

    // ── View refs (set in onCreateView) ───────────────────────────────────────

    private lateinit var tvDirPath:       TextView
    private lateinit var tvDirPlaceholder: TextView
    private lateinit var btnSelectFolder: MaterialButton
    private lateinit var btnConfirm:      MaterialButton
    private lateinit var btnCancel:       MaterialButton
    private lateinit var containerInfo:   LinearLayout
    private lateinit var switchExportMetadata: com.google.android.material.switchmaterial.SwitchMaterial

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = buildLayout()

    override fun onStart() {
        super.onStart()
        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)
            ?.behavior?.apply {
                state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
    }

    // ── Layout construction ───────────────────────────────────────────────────

    /**
     * Builds the dialog programmatically, consistent with the rest of the
     * settings dialogs in this package.
     *
     * Structure:
     *   Title
     *   ── info panel (mode-specific) ──
     *   Folder selector row (placeholder → path after pick)
     *   [Space warning chip — ADD_VOLUME_DIR only, shown after pick if needed]
     *   Footer note (ONE_TIME only)
     *   ── buttons ──
     *   [Back Up / Add Directory]  [Cancel]
     */
    private fun buildLayout(): View {
        val ctx   = requireContext()
        val dp    = ctx.resources.displayMetrics.density
        val px16  = (16 * dp).toInt()
        val px12  = (12 * dp).toInt()
        val px8   = (8  * dp).toInt()
        val px24  = (24 * dp).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px16, 0, px24)
        }

        // ── Title ─────────────────────────────────────────────────────────────
        root.addView(TextView(ctx).apply {
            text = getString(
                if (mode == Mode.ONE_TIME) R.string.backup_setup_title_one_time
                else R.string.backup_setup_title_add_dir
            )
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
            setPadding(px24, px8, px24, px16)
        })

        // ── Divider ───────────────────────────────────────────────────────────
        root.addView(divider(ctx, px24))

        // ── Mode-specific info panel ──────────────────────────────────────────
        containerInfo = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px24, px16, px24, 0)
        }
        buildInfoPanel(containerInfo, dp)
        root.addView(containerInfo)

        // ── Folder selector row ───────────────────────────────────────────────
        root.addView(buildFolderRow(ctx, dp, px24, px16, px12, px8))

        // ── Space warning (ADD_VOLUME_DIR, shown after pick if space is tight) ─
        // Inserted lazily as a child of root at the known index when needed.
        // We attach a tag to find/create it without holding a separate field ref.
        // (See updateDirRow for the insertion logic.)

        // ── Footer note (ONE_TIME only) ───────────────────────────────────────
        if (mode == Mode.ONE_TIME) {
            root.addView(TextView(ctx).apply {
                text = getString(R.string.backup_setup_note_saved)
                textSize = 12f
                setTextColor(ctx.themeColor(R.attr.colorTextSecondary))
                setPadding(px24, px8, px24, 0)
            })

            // ── Export metadata toggle (ONE_TIME only) ────────────────────────
            // Shown here rather than in the gear dialog so users who want a
            // complete backup don't have to take an extra step. Default on.
            root.addView(buildExportMetadataRow(ctx, dp, px24, px16))
        }

        // ── Buttons ───────────────────────────────────────────────────────────
        root.addView(buildButtonRow(ctx, dp, px24, px16))

        return root
    }

    /**
     * Builds the mode-specific information panel shown above the folder row.
     *
     * ONE_TIME: total recording count + estimated size from the DB.
     * ADD_VOLUME_DIR: destination volume label + free space + estimated backup
     *   size, so the user can judge feasibility before picking a folder.
     */
    private fun buildInfoPanel(container: LinearLayout, dp: Float) {
        val ctx  = requireContext()
        val px4  = (4 * dp).toInt()
        val px2  = (2 * dp).toInt()

        when (mode) {
            Mode.ONE_TIME -> {
                // Row: "Source: X recordings · ≈ Y MB"
                val sourceDesc = if (sourceTotalBytes > 0L)
                    getString(
                        R.string.backup_setup_source_summary,
                        AppVolume.formatBytes(sourceTotalBytes),
                    )
                else
                    getString(R.string.backup_setup_source_summary_unknown)

                container.addView(TextView(ctx).apply {
                    text = sourceDesc
                    textSize = 13f
                    setTextColor(ctx.themeColor(R.attr.colorTextSecondary))
                    setPadding(0, 0, 0, px4)
                })
            }

            Mode.ADD_VOLUME_DIR -> {
                // Volume name + free space
                val label = volumeLabel ?: getString(R.string.backup_setup_unknown_volume)
                container.addView(TextView(ctx).apply {
                    text = label
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
                    setPadding(0, 0, 0, px2)
                })
                if (freeBytes > 0L) {
                    container.addView(TextView(ctx).apply {
                        text = getString(
                            R.string.backup_setup_dest_free,
                            AppVolume.formatBytes(freeBytes),
                        )
                        textSize = 13f
                        setTextColor(ctx.themeColor(R.attr.colorTextSecondary))
                        setPadding(0, 0, 0, px2)
                    })
                }
                if (sourceTotalBytes > 0L) {
                    container.addView(TextView(ctx).apply {
                        text = getString(
                            R.string.backup_setup_est_size,
                            AppVolume.formatBytes(sourceTotalBytes),
                        )
                        textSize = 13f
                        setTextColor(ctx.themeColor(R.attr.colorTextSecondary))
                        setPadding(0, 0, 0, 0)
                    })
                }
            }
        }
    }

    /**
     * Builds the folder selector row, which transitions from a placeholder
     * state (before SAF pick) to a path-display state (after SAF pick).
     *
     * The "Select Folder" button is always present; after a folder is chosen
     * it shrinks to "Change" (text changes, same button) and the path TextView
     * becomes visible.
     */
    private fun buildFolderRow(
        ctx: android.content.Context,
        dp: Float,
        px24: Int, px16: Int, px12: Int, px8: Int,
    ): LinearLayout {
        val px4 = (4 * dp).toInt()

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px24, px16, px24, px8)
        }

        // Placeholder label (visible before pick)
        tvDirPlaceholder = TextView(ctx).apply {
            text = getString(R.string.backup_setup_dir_placeholder)
            textSize = 13f
            setTextColor(ctx.themeColor(R.attr.colorTextSecondary))
            setPadding(0, 0, 0, px4)
        }
        col.addView(tvDirPlaceholder)

        // Chosen-path label (hidden until SAF returns)
        tvDirPath = TextView(ctx).apply {
            textSize = 13f
            setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
            setPadding(0, 0, 0, px4)
            visibility = View.GONE
        }
        col.addView(tvDirPath)

        // "Select Folder" / "Change" button
        btnSelectFolder = MaterialButton(
            ctx,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = getString(R.string.backup_setup_btn_select_folder)
            textSize = 13f
            insetTop    = 0
            insetBottom = 0
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            layoutParams = lp
            setOnClickListener { launchPicker() }
        }
        col.addView(btnSelectFolder)

        return col
    }

    /** Builds the [Back Up / Add Directory] + [Cancel] button row. */
    private fun buildButtonRow(
        ctx: android.content.Context,
        dp: Float,
        px24: Int,
        px16: Int,
    ): LinearLayout {
        val px8 = (8 * dp).toInt()

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.END
            setPadding(px24, px16, px24, 0)
        }

        btnCancel = MaterialButton(
            ctx,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = getString(android.R.string.cancel)
            insetTop    = 0
            insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.marginEnd = px8 }
            setOnClickListener { dismissAllowingStateLoss() }
        }
        row.addView(btnCancel)

        btnConfirm = MaterialButton(ctx).apply {
            text = getString(
                if (mode == Mode.ONE_TIME) R.string.backup_setup_btn_back_up
                else R.string.backup_setup_btn_add_dir
            )
            insetTop    = 0
            insetBottom = 0
            isEnabled   = false   // enabled once a folder is selected
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { onConfirm() }
        }
        row.addView(btnConfirm)

        return row
    }

    /**
     * Builds the "Export recording metadata" toggle row for [Mode.ONE_TIME].
     *
     * Mirrors the layout of the same toggle in [BackupTargetConfigDialog] so
     * the two surfaces feel consistent. The switch updates [exportMetadata]
     * directly; no callback needed since [onConfirm] reads the field.
     */
    private fun buildExportMetadataRow(
        ctx: android.content.Context,
        dp: Float,
        px24: Int,
        px16: Int,
    ): LinearLayout {
        val px2 = (2 * dp).toInt()

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(px24, px16, px24, 0)
        }

        val textCol = LinearLayout(ctx).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        textCol.addView(TextView(ctx).apply {
            text = getString(R.string.settings_backup_config_label_export_metadata)
            textSize = 14f
            setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
        })
        textCol.addView(TextView(ctx).apply {
            text = getString(R.string.settings_backup_config_sublabel_export_metadata)
            textSize = 12f
            setTextColor(ctx.themeColor(R.attr.colorTextSecondary))
            setPadding(0, px2, 0, 0)
        })

        switchExportMetadata = com.google.android.material.switchmaterial.SwitchMaterial(ctx).apply {
            isChecked = exportMetadata
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.marginStart = px16 }
            setOnCheckedChangeListener { _, checked -> exportMetadata = checked }
        }

        row.addView(textCol)
        row.addView(switchExportMetadata)
        return row
    }

    // ── Picker launch ─────────────────────────────────────────────────────────

    /**
     * Launches the SAF picker. For ADD_VOLUME_DIR the initial URI hint is the
     * volume root, so the picker opens there rather than at a generic location.
     * For ONE_TIME there is no hint and the picker opens at the system default.
     */
    private fun launchPicker() {
        val hint: Uri? = when {
            mode == Mode.ADD_VOLUME_DIR && volumeUuid != null ->
                buildVolumeRootUri(volumeUuid!!)
            else -> null
        }
        openDocumentTree.launch(hint)
    }

    // ── UI update after pick ──────────────────────────────────────────────────

    /**
     * Transitions the folder row from placeholder → chosen-path state and
     * (for ADD_VOLUME_DIR) inserts or updates a space-warning chip if the
     * estimated backup size exceeds available free space.
     */
    private fun updateDirRow(uri: Uri) {
        val ctx  = requireContext()
        val path = backupDirDisplayPath(uri.toString())
            ?: uri.toString()

        tvDirPlaceholder.visibility = View.GONE
        tvDirPath.text       = path
        tvDirPath.visibility = View.VISIBLE
        btnSelectFolder.text = getString(R.string.backup_setup_btn_change_folder)

        // Space warning for ADD_VOLUME_DIR
        if (mode == Mode.ADD_VOLUME_DIR && freeBytes > 0L && sourceTotalBytes > 0L) {
            updateSpaceWarning()
        }
    }

    /**
     * Shows a warning TextView below the folder row if the estimated backup
     * size exceeds the destination's free space. Removes it when not needed.
     *
     * The warning view is identified by the tag [R.string.backup_setup_space_warning]
     * so it can be found and updated without a field reference, keeping the
     * field count in this class minimal.
     */
    private fun updateSpaceWarning() {
        val ctx = requireContext()
        val root = view as? LinearLayout ?: return
        val warningTag = getString(R.string.backup_setup_space_warning)

        // Remove any existing warning first
        val existing = root.findViewWithTag<TextView>(warningTag)
        if (existing != null) root.removeView(existing)

        if (sourceTotalBytes > freeBytes) {
            val dp   = ctx.resources.displayMetrics.density
            val px24 = (24 * dp).toInt()
            val px8  = (8  * dp).toInt()
            val tv   = TextView(ctx).apply {
                tag      = warningTag
                text     = getString(
                    R.string.backup_setup_space_warning,
                    AppVolume.formatBytes(sourceTotalBytes),
                    AppVolume.formatBytes(freeBytes),
                )
                textSize = 12f
                setTextColor(ctx.themeColor(R.attr.colorError))
                setPadding(px24, 0, px24, px8)
            }
            // Insert just before the button row (last child)
            root.addView(tv, root.childCount - 1)
        }
    }

    /** Enables the confirm button once a folder has been selected. */
    private fun updateConfirmButton() {
        btnConfirm.isEnabled = selectedUri != null
    }

    // ── Confirm action ────────────────────────────────────────────────────────

    /**
     * Called when the user taps "Back Up" (ONE_TIME) or "Add Directory"
     * (ADD_VOLUME_DIR).
     *
     * ONE_TIME:
     *   1. Inserts a manual-only BackupTargetEntity (triggers disabled).
     *   2. Enqueues a one-time WorkManager backup immediately.
     *   3. Shows a "Backup queued" toast and dismisses.
     *
     * ADD_VOLUME_DIR:
     *   1. Inserts a BackupTargetEntity with default triggers (on-connect + 24h).
     *   2. Shows a "Directory added" toast and dismisses.
     *   (The user kicks off a backup from the gear dialog if they want one now.)
     */
    private fun onConfirm() {
        val uri = selectedUri ?: return
        val uriString = uri.toString()

        when (mode) {
            Mode.ONE_TIME -> {
                viewModel.addOneTimeBackupTarget(uriString, exportMetadata = exportMetadata)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.backup_setup_toast_queued),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            Mode.ADD_VOLUME_DIR -> {
                viewModel.addBackupTarget(volumeUuid, uriString)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.backup_setup_toast_dir_added),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        dismissAllowingStateLoss()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun divider(ctx: android.content.Context, horizontalPadding: Int): View =
        View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1,
            ).also {
                it.marginStart = horizontalPadding
                it.marginEnd   = horizontalPadding
            }
            setBackgroundColor(ctx.themeColor(R.attr.colorSurfaceElevated))
        }

    /**
     * Builds a SAF volume-root URI for the given [volumeUuid] so the picker
     * opens directly on that volume rather than the system default.
     *
     * Mirrors [SettingsFragment_Helpers.buildVolumeRootUri] — kept local so
     * this dialog has no dependency on SettingsFragment internals.
     */
    private fun buildVolumeRootUri(volumeUuid: String): Uri? {
        if (volumeUuid == StorageVolumeHelper.UUID_PRIMARY) return null
        return android.provider.DocumentsContract.buildRootUri(
            "com.android.externalstorage.documents",
            volumeUuid,
        )
    }
}