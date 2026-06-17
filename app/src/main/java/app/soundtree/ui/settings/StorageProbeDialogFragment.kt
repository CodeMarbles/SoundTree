package app.soundtree.ui.settings

import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import app.soundtree.R
import app.soundtree.util.themeColor
import java.io.File

/**
 * Developer diagnostic dialog that probes available storage volumes via
 * three independent strategies and displays raw output for each.
 *
 * Strategies:
 *   A. getExternalFilesDirs(null)          — the current production path
 *   B. StorageManager.getStorageVolumes()  — OS volume list (no File paths)
 *   C. /proc/mounts parsing                — kernel mount table
 *
 * Gated behind the Developer Options toggle; not shown in normal use.
 */
class StorageProbeDialogFragment : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "storage_probe"
        fun newInstance() = StorageProbeDialogFragment()
    }

    override fun getTheme(): Int = R.style.Theme_SoundTree_BottomSheet

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        // Build the layout entirely in code — no separate XML needed for a
        // one-off diagnostic dialog. Keeps the change self-contained.
        val ctx = requireContext()

        val root = NestedScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 64)
        }

        // Title
        container.addView(TextView(ctx).apply {
            text = getString(R.string.settings_dev_storage_probe)
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
            setPadding(0, 0, 0, 32)
        })

        // Run all three probes and add a labeled section for each
        addProbeSection(container, "A · getExternalFilesDirs(null)", probeExternalFilesDirs())
        addProbeSection(container, "B · StorageManager.getStorageVolumes()", probeStorageVolumes())
        addProbeSection(container, "C · /proc/mounts", probeProcMounts())

        // Button row: Copy | Share | Close
        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 32 }
        }

        buttonRow.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.common_btn_copy)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = 8 }
            setOnClickListener {
                val cm = requireContext().getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("storage_probe", buildFullReport()))
                android.widget.Toast.makeText(requireContext(), R.string.common_toast_copied, android.widget.Toast.LENGTH_SHORT).show()
            }
        })

        buttonRow.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.common_btn_share)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = 8 }
            setOnClickListener {
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "SoundTree Storage Probe Report")
                    putExtra(android.content.Intent.EXTRA_TEXT, buildFullReport())
                }
                startActivity(android.content.Intent.createChooser(intent, getString(R.string.settings_dev_storage_probe_share_chooser)))
            }
        })

        buttonRow.addView(MaterialButton(ctx).apply {
            text = getString(R.string.common_btn_close)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { dismissAllowingStateLoss() }
        })

        container.addView(buttonRow)

        root.addView(container)
        return root
    }

    // ── Probe A: getExternalFilesDirs ────────────────────────────────────────

    private fun probeExternalFilesDirs(): String {
        val dirs = requireContext().getExternalFilesDirs(null)
        if (dirs.isEmpty()) return "(returned empty array)"
        return buildString {
            dirs.forEachIndexed { i, dir ->
                appendLine("[${i}] ${dir?.absolutePath ?: "null"}")
                if (dir != null) {
                    appendLine("    exists   : ${dir.exists()}")
                    appendLine("    canRead  : ${dir.canRead()}")
                    appendLine("    canWrite : ${dir.canWrite()}")
                    runCatching { StatFs(dir.path) }.onSuccess { sf ->
                        appendLine("    total    : ${formatBytes(sf.totalBytes)}")
                        appendLine("    free     : ${formatBytes(sf.availableBytes)}")
                    }.onFailure {
                        appendLine("    StatFs   : failed (${it.message})")
                    }
                }
            }
        }.trimEnd()
    }

    // ── Probe B: StorageManager.getStorageVolumes ────────────────────────────

    private fun probeStorageVolumes(): String {
        val sm = requireContext().getSystemService(StorageManager::class.java)
        val volumes = sm.storageVolumes
        if (volumes.isEmpty()) return "(returned empty list)"
        return buildString {
            volumes.forEachIndexed { i, sv ->
                appendLine("[${i}] ${sv.getDescription(requireContext())}")
                appendLine("    uuid        : ${sv.uuid ?: "(primary/null)"}")
                appendLine("    state       : ${sv.state}")
                appendLine("    isPrimary   : ${sv.isPrimary}")
                appendLine("    isRemovable : ${sv.isRemovable}")
                appendLine("    isEmulated  : ${sv.isEmulated}")
                // Reconstruct the conventional path for informational purposes
                val guessedPath = if (sv.uuid == null) {
                    Environment.getExternalStorageDirectory().absolutePath
                } else {
                    "/storage/${sv.uuid}"
                }
                val guessedDir = File(guessedPath)
                appendLine("    guessed path: $guessedPath")
                appendLine("    path exists : ${guessedDir.exists()}")
                appendLine("    path canRead: ${guessedDir.canRead()}")
            }
        }.trimEnd()
    }

    // ── Probe C: /proc/mounts ────────────────────────────────────────────────

    private fun probeProcMounts(): String {
        val mounts = File("/proc/mounts")
        if (!mounts.exists() || !mounts.canRead()) return "(not accessible)"
        return runCatching {
            mounts.readLines()
                .filter { line ->
                    // Filter to lines plausibly related to storage:
                    // /storage, /mnt/media_rw, /sdcard, /data (internal)
                    // Exclude noise: tmpfs, proc, sysfs, cgroup, etc.
                    val path = line.split(" ").getOrElse(1) { "" }
                    path.startsWith("/storage") ||
                            path.startsWith("/mnt/media_rw") ||
                            path.startsWith("/sdcard") ||
                            path == "/data"
                }
                .joinToString("\n")
                .ifBlank { "(no matching mount points found)" }
        }.getOrElse { "(read failed: ${it.message})" }
    }

    // ── Layout helpers ───────────────────────────────────────────────────────

    private fun addProbeSection(parent: LinearLayout, title: String, content: String) {
        val ctx = requireContext()

        // Section label
        parent.addView(TextView(ctx).apply {
            text = title
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
            setPadding(0, 32, 0, 8)
        })

        // Monospace output box
        parent.addView(TextView(ctx).apply {
            text = content
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)   // lets the user long-press to copy
            setBackgroundColor(ctx.themeColor(R.attr.colorSurfaceBase))
            setTextColor(ctx.themeColor(R.attr.colorTextSecondary))
            setPadding(24, 20, 24, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 4 }
        })
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.0f MB".format(bytes / 1_048_576.0)
        else                    -> "%.0f KB".format(bytes / 1_024.0)
    }

    // ── Share / Copy buttons ─────────────────────────────────────────────────────

    private fun buildFullReport(): String = buildString {
        appendLine("SoundTree Storage Probe Report")
        appendLine("Device  : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("Android : ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("Package : ${requireContext().packageName}")
        appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        appendLine()
        appendLine("════════════════════════════════")
        appendLine("A · getExternalFilesDirs(null)")
        appendLine("════════════════════════════════")
        appendLine(probeExternalFilesDirs())
        appendLine()
        appendLine("════════════════════════════════")
        appendLine("B · StorageManager.getStorageVolumes()")
        appendLine("════════════════════════════════")
        appendLine(probeStorageVolumes())
        appendLine()
        appendLine("════════════════════════════════")
        appendLine("C · /proc/mounts")
        appendLine("════════════════════════════════")
        appendLine(probeProcMounts())
    }
}