package com.treecast.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import com.google.android.material.slider.Slider
import com.treecast.app.R
import com.treecast.app.databinding.FragmentSettingsBinding
import com.treecast.app.ui.MainViewModel
import com.treecast.app.ui.PlayerWidgetVisibility
import com.treecast.app.ui.ProcessingStatus
import com.treecast.app.ui.RecorderWidgetVisibility
import com.treecast.app.ui.recovery.OrphanRecoveryDialogFragment
import com.treecast.app.util.AppVolume
import com.treecast.app.util.OrphanRecording
import com.treecast.app.util.themeColor
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

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
        setupHeader()
        setupTheme()
        setupWaveformStyleSettings()
        setupLayoutSection()
        setupRecordingWidgetSection()
        setupPlaybackSettings()
        setupStorageSection()
        setupRecordingRecoverySection()
        setupProcessingSection()
        setupDevOptionsSection()
        loadStats()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

   override fun onResume() {
       super.onResume()
       viewModel.refreshStorageVolumes()   // refresh free-space numbers
   }

    private fun setupHeader() {
        binding.tvAppIdentity.text = getString(R.string.app_identity, getString(R.string.app_name), getString(R.string.app_emoji))
    }

    private fun setupRecordingRecoverySection() {
        binding.btnReviewOrphans.setOnClickListener {
            OrphanRecoveryDialogFragment
                .newInstance(viewModel.orphanRecordings.value)
                .show(parentFragmentManager, "orphan_recovery")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.orphanRecordings.collect { orphans ->
                    renderOrphanSummary(orphans)
                }
            }
        }
    }

    private fun renderOrphanSummary(orphans: List<OrphanRecording>) {
        val recoverable   = orphans.filter { it.isPlayable }
        val unrecoverable = orphans.filter { !it.isPlayable }
        binding.tvOrphanRecoverableSummary.text = formatOrphanSummary(recoverable)
        binding.tvOrphanCorruptSummary.text     = formatOrphanSummary(unrecoverable)
    }

    private fun formatOrphanSummary(orphans: List<OrphanRecording>): String {
        if (orphans.isEmpty()) return "None"
        val count      = orphans.size
        val totalBytes = orphans.sumOf { it.file.length() }
        val label      = if (count == 1) "1 recording" else "$count recordings"
        return "$label · ${AppVolume.formatBytes(totalBytes)}"
    }

    private fun setupProcessingSection() {
        // Button: regenerate all waveforms from scratch.
        binding.btnReprocessWaveforms.setOnClickListener {
            viewModel.reprocessAllWaveforms()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe the processing status flow and render on every emission.
                launch {
                    viewModel.processingStatus.collect { status ->
                        renderProcessingStatus(status)
                    }
                }

                // Safety-net: tick the ViewModel every 3 s while this fragment is
                // visible so the combine chain re-evaluates even if WorkManager
                // misses emitting the terminal-state change for the last job.
                launch {
                    while (true) {
                        kotlinx.coroutines.delay(3_000L)
                        viewModel.tickProcessingRefresh()
                    }
                }
            }
        }
    }

    private fun renderProcessingStatus(status: ProcessingStatus) {
        val hasActive  = status.active != null
        val hasPending = status.pending.isNotEmpty()
        val hasRecent  = status.recent.isNotEmpty()
        val isIdle     = !hasActive && !hasPending && !hasRecent

        binding.processingSpinner.visibility = if (hasActive) View.VISIBLE else View.GONE
        binding.tvProcessingIdle.visibility  = if (isIdle) View.VISIBLE else View.GONE

        // ── Active job ────────────────────────────────────────────────
        binding.rowActiveJob.visibility = if (hasActive) View.VISIBLE else View.GONE
        status.active?.let { binding.tvActiveJobTitle.text = viewModel.labelForJob(it) }

        // ── Pending jobs ──────────────────────────────────────────────
        binding.containerPending.visibility = if (hasPending) View.VISIBLE else View.GONE
        if (hasPending) {
            binding.listPendingJobs.removeAllViews()
            status.pending.forEach { job ->
                addJobRow(binding.listPendingJobs, viewModel.labelForJob(job), isDone = false, failed = false)
            }
        }

        // ── Recent jobs ───────────────────────────────────────────────
        binding.containerRecent.visibility = if (hasRecent) View.VISIBLE else View.GONE
        if (hasRecent) {
            binding.listRecentJobs.removeAllViews()
            status.recent.forEach { job ->
                val failed    = job.state == WorkInfo.State.FAILED
                val timeLabel = job.completedAt?.let { formatCompletionTime(it) } ?: ""
                addJobRow(binding.listRecentJobs, viewModel.labelForJob(job), isDone = true, failed = failed, timeLabel = timeLabel)
            }
        }
    }

    private fun formatCompletionTime(epochMs: Long): String {
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(epochMs))
    }

    private fun addJobRow(
        container: LinearLayout,
        label: String,
        isDone: Boolean,
        failed: Boolean,
        timeLabel: String = ""
    ) {
        val density = resources.displayMetrics.density
        val hPad = (16 * density).toInt()
        val vPad = (6 * density).toInt()

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(hPad, vPad, hPad, vPad)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val tvLabel = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = label
            textSize = 13f
            setTextColor(requireContext().themeColor(R.attr.colorTextPrimary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val tvTime = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = (8 * density).toInt() }
            text = timeLabel
            textSize = 11f
            setTextColor(requireContext().themeColor(R.attr.colorTextSecondary))
            visibility = if (isDone && timeLabel.isNotEmpty()) View.VISIBLE else View.GONE
        }

        val tvStatus = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = when {
                !isDone -> "⏳"
                failed  -> "✗"
                else    -> "✓"
            }
            textSize = 13f
            setTextColor(
                requireContext().themeColor(
                    if (failed) R.attr.colorTextSecondary else R.attr.colorAccent
                )
            )
        }

        row.addView(tvLabel)
        row.addView(tvTime)
        row.addView(tvStatus)
        container.addView(row)
    }

    private fun setupDevOptionsSection() {
        binding.switchFutureMode.isChecked = viewModel.futureMode.value
        binding.switchFutureMode.setOnCheckedChangeListener { _, checked ->
            viewModel.setFutureMode(checked)
        }
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

    private fun setupRecordingWidgetSection() {

        // ── Recorder Widget visibility toggle group ───────────────────────
        val toggleGroup = binding.toggleRecorderVisibility

        // Map each button id → enum value (and back)
        val btnToMode = mapOf(
            R.id.btnRecorderVisNever          to RecorderWidgetVisibility.NEVER,
            R.id.btnRecorderVisWhileRecording to RecorderWidgetVisibility.WHILE_RECORDING,
            R.id.btnRecorderVisAlways         to RecorderWidgetVisibility.ALWAYS
        )
        val modeToBtn = btnToMode.entries.associate { (k, v) -> v to k }

        fun applyMode(mode: RecorderWidgetVisibility) {
            // Check the right button
            toggleGroup.check(modeToBtn[mode] ?: R.id.btnRecorderVisWhileRecording)
            // Dependent row: enabled only when not NEVER
            val dependentEnabled = mode != RecorderWidgetVisibility.NEVER
            binding.rowHideRecorderOnRecordTab.alpha = if (dependentEnabled) 1f else 0.4f
            binding.switchHideRecorderOnRecordTab.isEnabled = dependentEnabled
        }

        // Initialise from ViewModel
        applyMode(viewModel.recorderWidgetVisibility.value)
        binding.switchHideRecorderOnRecordTab.isChecked =
            viewModel.hideRecorderOnRecordTab.value

        // User taps a button
        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = btnToMode[checkedId] ?: return@addOnButtonCheckedListener
            viewModel.setRecorderWidgetVisibility(mode)
            applyMode(mode)
        }

        // Dependent toggle
        binding.switchHideRecorderOnRecordTab.setOnCheckedChangeListener { _, checked ->
            viewModel.setHideRecorderOnRecordTab(checked)
        }

        // ── Always show recorder shortcut ────────────────────────────────────
        binding.switchAlwaysShowRecorderPill.isChecked = viewModel.alwaysShowRecorderPill.value
        binding.switchAlwaysShowRecorderPill.setOnCheckedChangeListener { _, checked ->
            viewModel.setAlwaysShowRecorderPill(checked)
        }

        // ── Mark nudge seconds ────────────────────────────────────────────
        fun Float.toNudgeDisplay() =
            if (this == this.toLong().toFloat()) "${this.toInt()}s" else "${this}s"

        binding.tvMarkNudgeSecs.text = viewModel.markNudgeSecs.value.toNudgeDisplay()

        binding.btnMarkNudgeMinus.setOnClickListener {
            val newVal = (viewModel.markNudgeSecs.value - 1f).coerceAtLeast(1f)
            viewModel.setMarkNudgeSecs(newVal)
            binding.tvMarkNudgeSecs.text = newVal.toNudgeDisplay()
        }
        binding.btnMarkNudgePlus.setOnClickListener {
            val newVal = (viewModel.markNudgeSecs.value + 1f).coerceAtMost(30f)
            viewModel.setMarkNudgeSecs(newVal)
            binding.tvMarkNudgeSecs.text = newVal.toNudgeDisplay()
        }

        // ── Keep controls in sync if another screen changes prefs ─────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.recorderWidgetVisibility.collect { mode ->
                        applyMode(mode)
                    }
                }
                launch {
                    viewModel.hideRecorderOnRecordTab.collect { hide ->
                        binding.switchHideRecorderOnRecordTab.isChecked = hide
                    }
                }
                launch {
                    viewModel.alwaysShowRecorderPill.collect { show ->
                        binding.switchAlwaysShowRecorderPill.isChecked = show
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

    private fun setupWaveformStyleSettings() {

        // ── View refs ─────────────────────────────────────────────────────────
        val btnStyleStandard   = binding.btnWaveformStyleStandard
        val btnStyleSky        = binding.btnWaveformStyleSky
        val btnStyleSkyLights  = binding.btnWaveformStyleSkyLights
        val rowSubOptions      = binding.rowWaveformSubOptions
        val switchInvert       = binding.switchInvertWaveformTheme
        val rowInvert          = binding.rowInvertWaveformTheme
        val sliderAlpha        = binding.sliderWaveformBgAlpha
        val rowAlpha           = binding.rowWaveformBgAlpha
        val switchRuler        = binding.switchWaveformExtendsUnderRuler
        val switchUnplayed     = binding.switchWaveformUnplayedOnly

        // ── Helpers ───────────────────────────────────────────────────────────

        // Map button view → style key string for clean dispatch
        val btnToKey = mapOf(
            btnStyleStandard  to MainViewModel.STYLE_STANDARD,
            btnStyleSky       to MainViewModel.STYLE_SKY,
            btnStyleSkyLights to MainViewModel.STYLE_SKY_LIGHTS,
        )

        /** Update the three-button selector highlight to match [activeKey]. */
        fun applyStyleButtonVisuals(activeKey: String) {
            val activeText = requireContext().themeColor(R.attr.colorTextPrimary)
            val activeBackground = requireContext().themeColor(R.attr.colorSurfaceElevated)
            val inactiveText = requireContext().themeColor(R.attr.colorTextSecondary)

            btnToKey.forEach { (btn, key) ->
                val isActive = key == activeKey
                btn.setTextColor(if (isActive) activeText else inactiveText)
                btn.setTypeface(null, if (isActive) android.graphics.Typeface.BOLD
                else android.graphics.Typeface.NORMAL)
                btn.setBackgroundColor(if (isActive) activeBackground
                else android.graphics.Color.TRANSPARENT)
            }
        }

        /** Dim and disable the sub-options block when Standard is selected. */
        fun applySubOptionState(styleKey: String) {
            val themed = styleKey != MainViewModel.STYLE_STANDARD
            rowSubOptions.alpha = if (themed) 1f else 0.38f
            switchInvert.isEnabled = themed
            sliderAlpha.isEnabled  = themed
            switchRuler.isEnabled  = themed
            switchUnplayed.isEnabled = themed
            // Invert row itself mirrors the same dimming
            rowInvert.alpha = if (themed) 1f else 0.38f
            rowAlpha.alpha  = if (themed) 1f else 0.38f
        }

        // ── Seed from ViewModel ───────────────────────────────────────────────
        val initialKey    = viewModel.waveformStyleKey.value
        val initialConfig = viewModel.waveformDisplayConfig.value

        applyStyleButtonVisuals(initialKey)
        applySubOptionState(initialKey)

        switchInvert.isChecked  = viewModel.invertWaveformTheme.value
        // Slider is 0–100 (integer-step); config stores 0f–1f
        sliderAlpha.value = (initialConfig.backgroundAlpha * 100f).roundToInt().toFloat().coerceIn(0f, 100f)
        switchRuler.isChecked   = initialConfig.extendsUnderRuler
        switchUnplayed.isChecked = initialConfig.unplayedOnly

        // ── Observe — keep UI in sync if prefs change from another surface ────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.waveformStyleKey.collect { key ->
                        applyStyleButtonVisuals(key)
                        applySubOptionState(key)
                    }
                }
                launch {
                    viewModel.invertWaveformTheme.collect { inverted ->
                        if (switchInvert.isChecked != inverted) switchInvert.isChecked = inverted
                    }
                }
                launch {
                    viewModel.waveformDisplayConfig.collect { cfg ->
                        val sliderTarget = (cfg.backgroundAlpha * 100f).roundToInt().toFloat().coerceIn(0f, 100f)
                        if (sliderAlpha.value != sliderTarget) sliderAlpha.value = sliderTarget
                        if (switchRuler.isChecked    != cfg.extendsUnderRuler) switchRuler.isChecked    = cfg.extendsUnderRuler
                        if (switchUnplayed.isChecked != cfg.unplayedOnly)       switchUnplayed.isChecked = cfg.unplayedOnly
                    }
                }
            }
        }

        // ── User interaction ──────────────────────────────────────────────────
        btnToKey.forEach { (btn, key) ->
            btn.setOnClickListener {
                viewModel.setWaveformStyleKey(key)
            }
        }

        switchInvert.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setInvertWaveformTheme(isChecked)
        }

        // Slider: commit to ViewModel only on touch-up to avoid rapid pref writes
        sliderAlpha.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                viewModel.setBgAlpha(slider.value / 100f)
            }
        })

        switchRuler.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setBgExtendsUnderRuler(isChecked)
        }

        switchUnplayed.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setBgUnplayedOnly(isChecked)
        }
    }

    private fun setupPlaybackSettings() {

        // ── Player widget visibility (3-state) ───────────────────────────────
        val playerToggleGroup = binding.togglePlayerVisibility

        val playerBtnToMode = mapOf(
            R.id.btnPlayerVisNever       to PlayerWidgetVisibility.NEVER,
            R.id.btnPlayerVisWhilePlaying to PlayerWidgetVisibility.WHILE_PLAYING,
            R.id.btnPlayerVisAlways      to PlayerWidgetVisibility.ALWAYS
        )
        val playerModeToBtn = playerBtnToMode.entries.associate { (k, v) -> v to k }

        fun applyPlayerMode(mode: PlayerWidgetVisibility) {
            playerToggleGroup.check(playerModeToBtn[mode] ?: R.id.btnPlayerVisWhilePlaying)
            // "Hide on Listen Tab" only makes sense when the widget can appear,
            // i.e. not when NEVER is selected.
            val dependentEnabled = mode != PlayerWidgetVisibility.NEVER
            binding.rowHidePlayerOnListenTab.alpha = if (dependentEnabled) 1f else 0.4f
            binding.switchHidePlayerOnListenTab.isEnabled = dependentEnabled
        }

        applyPlayerMode(viewModel.playerWidgetVisibility.value)
        binding.switchHidePlayerOnListenTab.isChecked = viewModel.hidePlayerOnListenTab.value
        binding.switchAlwaysShowPlayerPill.isChecked  = viewModel.alwaysShowPlayerPill.value

        playerToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = playerBtnToMode[checkedId] ?: return@addOnButtonCheckedListener
            viewModel.setPlayerWidgetVisibility(mode)
            applyPlayerMode(mode)
        }

        binding.switchHidePlayerOnListenTab.setOnCheckedChangeListener { _, checked ->
            viewModel.setHidePlayerOnListenTab(checked)
        }

        binding.switchAlwaysShowPlayerPill.setOnCheckedChangeListener { _, checked ->
            viewModel.setAlwaysShowPlayerPill(checked)
        }

        // ── Switch to Listen on play ──────────────────────────────────────────
        binding.switchAutoNavigate.isChecked = viewModel.autoNavigateToListen.value
        binding.switchAutoNavigate.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoNavigateToListen(isChecked)
        }

        // ── Jump to Library on save ───────────────────────────────────────────
        binding.switchJumpToLibrary.isChecked = viewModel.jumpToLibraryOnSave.value
        binding.switchJumpToLibrary.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setJumpToLibraryOnSave(isChecked)
        }

        // ── Scrub Back ────────────────────────────────────────────────────────
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

        // ── Scrub Forward ─────────────────────────────────────────────────────
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

        // ── Mark Rewind Threshold ─────────────────────────────────────────────
        fun Float.toDisplayString() =
            if (this == this.toLong().toFloat()) "${this.toInt()}s" else "${this}s"

        binding.tvMarkRewindSecs.text = viewModel.markRewindThresholdSecs.value.toDisplayString()
        binding.btnMarkRewindMinus.setOnClickListener {
            val newVal = (viewModel.markRewindThresholdSecs.value - 0.5f).coerceAtLeast(0.5f)
            viewModel.setMarkRewindThresholdSecs(newVal)
            binding.tvMarkRewindSecs.text = newVal.toDisplayString()
        }
        binding.btnMarkRewindPlus.setOnClickListener {
            val newVal = (viewModel.markRewindThresholdSecs.value + 0.5f).coerceAtMost(5.0f)
            viewModel.setMarkRewindThresholdSecs(newVal)
            binding.tvMarkRewindSecs.text = newVal.toDisplayString()
        }

        // ── Keep controls in sync with external pref changes ─────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.playerWidgetVisibility.collect { mode -> applyPlayerMode(mode) }
                }
                launch {
                    viewModel.hidePlayerOnListenTab.collect { hide ->
                        binding.switchHidePlayerOnListenTab.isChecked = hide
                    }
                }
                launch {
                    viewModel.alwaysShowPlayerPill.collect { show ->
                        binding.switchAlwaysShowPlayerPill.isChecked = show
                    }
                }
            }
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