package com.treecast.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.treecast.app.R
import com.treecast.app.databinding.FragmentSettingsBinding
import com.treecast.app.ui.MainViewModel
import com.treecast.app.util.AppVolume
import com.treecast.app.util.themeColor
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTheme()
        setupPlaybackSettings()
        setupStorageSection()
        loadStats()
        setupLayoutSection()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

   override fun onResume() {
       super.onResume()
       viewModel.refreshStorageVolumes()   // refresh free-space numbers
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
            btnEdit.text = getString(R.string.layout_btn_apply)
        }

        fun applyAndLock() {
            viewModel.setLayoutOrder(widget.getOrder())
            viewModel.setShowTitleBar(widget.showTitleBar)
            widget.setEditing(false)
            btnEdit.text = getString(R.string.layout_btn_edit)
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

    private fun setupTheme() {
        fun updateToggleVisuals(selected: String) {
            val activeText   = requireContext().themeColor(R.attr.colorTextPrimary)
            val inactiveText = requireContext().themeColor(R.attr.colorTextSecondary)
            val activeBg     = requireContext().themeColor(R.attr.colorSurfaceElevated)

            listOf(
                binding.btnThemeSystem to "system",
                binding.btnThemeLight  to "light",
                binding.btnThemeDark   to "dark"
            ).forEach { (btn, mode) ->
                val isActive = mode == selected
                btn.setTextColor(if (isActive) activeText else inactiveText)
                btn.setTypeface(null, if (isActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                btn.setBackgroundColor(
                    if (isActive) activeBg else android.graphics.Color.TRANSPARENT
                )
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

    private fun setupPlaybackSettings() {
        // ── Jump to Library on save toggle ───────────────────────────────
        binding.switchJumpToLibrary.isChecked = viewModel.jumpToLibraryOnSave.value
        binding.switchJumpToLibrary.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setJumpToLibraryOnSave(isChecked)
        }

        // ── Auto-navigate toggle ───────────────────────────────────────
        binding.switchAutoNavigate.isChecked = viewModel.autoNavigateToListen.value
        binding.switchAutoNavigate.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoNavigateToListen(isChecked)
        }

        // ── Scrub Back ────────────────────────────────────────────────
        // Initialise display from ViewModel
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

        // ── Scrub Forward ─────────────────────────────────────────────
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
            val placeholder = android.widget.TextView(requireContext()).apply {
                text = "Detecting storage…"
                setTextColor(requireContext().themeColor(R.attr.colorTextSecondary))
                textSize = 13f
                setPadding(64, 12, 64, 12)
            }
            container.addView(placeholder)
            return
        }

        volumes.forEach { volume ->
            val usedBytes = usageMap[volume.uuid] ?: 0L
            val isSelected = volume.uuid == selectedUuid

            // Root row
            val row = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(64, 20, 64, 20)
                isClickable = true
                isFocusable = true
                background = with(android.util.TypedValue()) {
                    requireContext().theme.resolveAttribute(
                        android.R.attr.selectableItemBackground, this, true
                    )
                    android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                        .also { /* use ripple from background attr via setBackgroundResource */ }
                }
                setBackgroundResource(android.R.attr.selectableItemBackground.let {
                    android.util.TypedValue().also { tv ->
                        requireContext().theme.resolveAttribute(it, tv, true)
                    }.resourceId
                })
            }

            // Radio button (decorative — row click toggles it)
            val radio = RadioButton(requireContext()).apply {
                isChecked = isSelected
                isClickable = false
                isFocusable = false
            }

            // Text block: label + usage annotation
            val textBlock = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }

            val tvLabel = android.widget.TextView(requireContext()).apply {
                text = if (volume.isMounted) volume.label else "${volume.label} (unavailable)"
                setTextColor(
                    if (volume.isMounted)
                        requireContext().themeColor(R.attr.colorTextPrimary)
                    else
                        requireContext().themeColor(R.attr.colorTextSecondary)
                )
                textSize = 14f
                if (isSelected) setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val tvUsage = android.widget.TextView(requireContext()).apply {
                val usedLabel = AppVolume.formatBytes(usedBytes)
                text = if (volume.isMounted) {
                    "$usedLabel used  ·  ${volume.freeLabel()}"
                } else {
                    "$usedLabel used  ·  offline"
                }
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
                row.setOnClickListener {
                    viewModel.setDefaultStorageUuid(volume.uuid)
                }
            }

            container.addView(row)

            // Divider between rows (not after last)
            if (volume != volumes.last()) {
                val divider = View(requireContext()).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).also { lp ->
                        lp.marginStart = 64
                        lp.marginEnd = 64
                    }
                    setBackgroundColor(requireContext().themeColor(R.attr.colorSurfaceElevated))
                }
                container.addView(divider)
            }
        }
    }

    /** Updates the "Total used by TreeCast" summary line. */
    private fun renderTotalUsed(usageMap: Map<String, Long>) {
        val totalBytes = usageMap.values.sum()
        binding.tvTotalUsed.text = if (totalBytes == 0L) "—"
        else AppVolume.formatBytes(totalBytes)
    }

    private fun loadStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Total recorded time + last session (one-shot suspends)
                launch {
                    val totalMs = viewModel.getTotalRecordingTime()
                    binding.tvTotalRecordedTime.text = if (totalMs > 0) formatGap(totalMs) else "—"

                    val lastSession = viewModel.getLastClosedSession()
                    binding.tvLastOpened.text = if (lastSession != null) {
                        "${formatGap(System.currentTimeMillis() - lastSession.openedAt)} ago"
                    } else {
                        "First use"
                    }
                }

                // Listened count + total recordings — reactive, updates if user
                // listens to something while Settings is open
                launch {
                    viewModel.allRecordings.collect { recordings ->
                        val listened = recordings.count { it.isListened }
                        val total    = recordings.size
                        binding.tvListenedCount.text = "$listened / $total"
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
                        binding.tvStatsTotalStorage.text = if (totalBytes > 0)
                            AppVolume.formatBytes(totalBytes) else "—"
                    }
                }
            }
        }
    }

    private fun formatGap(ms: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return when {
            hours >= 24 -> "${hours / 24}d ${hours % 24}h"
            hours > 0   -> "${hours}h ${minutes}m"
            else        -> "${minutes}m"
        }
    }
}