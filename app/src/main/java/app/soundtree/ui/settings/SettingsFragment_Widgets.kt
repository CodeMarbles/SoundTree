package app.soundtree.ui.settings

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.soundtree.R
import app.soundtree.ui.PlayerBrowseDestination
import app.soundtree.ui.PlayerWidgetVisibility
import app.soundtree.ui.RecorderWidgetVisibility
import app.soundtree.ui.setAlwaysShowPlayerPill
import app.soundtree.ui.setAlwaysShowRecorderPill
import app.soundtree.ui.setHidePlayerOnListenTab
import app.soundtree.ui.setHideRecorderOnRecordTab
import app.soundtree.ui.setPlayerBrowseDestination
import app.soundtree.ui.setPlayerStartCollapsed
import app.soundtree.ui.setPlayerWidgetVisibility
import app.soundtree.ui.setRecorderStartCollapsed
import app.soundtree.ui.setRecorderWidgetVisibility
import kotlinx.coroutines.launch

// ── Recording widget ──────────────────────────────────────────────────────────

internal fun SettingsFragment.setupRecordingWidgetSection() {
    val toggleGroup = binding.groupWidgets.toggleRecorderVisibility

    val btnToMode = mapOf(
        R.id.btnRecorderVisNever          to RecorderWidgetVisibility.NEVER,
        R.id.btnRecorderVisWhileRecording to RecorderWidgetVisibility.WHILE_RECORDING,
        R.id.btnRecorderVisAlways         to RecorderWidgetVisibility.ALWAYS
    )
    val modeToBtn = btnToMode.entries.associate { (k, v) -> v to k }

    fun applyMode(mode: RecorderWidgetVisibility) {
        toggleGroup.check(modeToBtn[mode] ?: R.id.btnRecorderVisWhileRecording)
        val dependentEnabled = mode != RecorderWidgetVisibility.NEVER
        binding.groupWidgets.rowHideRecorderOnRecordTab.alpha    = if (dependentEnabled) 1f else 0.4f
        binding.groupWidgets.switchHideRecorderOnRecordTab.isEnabled = dependentEnabled
        binding.groupWidgets.rowRecorderStartCollapsed.alpha     = if (dependentEnabled) 1f else 0.4f
        binding.groupWidgets.switchRecorderStartCollapsed.isEnabled  = dependentEnabled
    }

    applyMode(viewModel.recorderWidgetVisibility.value)
    binding.groupWidgets.switchHideRecorderOnRecordTab.isChecked  = viewModel.hideRecorderOnRecordTab.value
    binding.groupWidgets.switchRecorderStartCollapsed.isChecked   = viewModel.recorderStartCollapsed.value
    binding.groupWidgets.switchAlwaysShowRecorderPill.isChecked   = viewModel.alwaysShowRecorderPill.value

    toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
        if (!isChecked) return@addOnButtonCheckedListener
        val mode = btnToMode[checkedId] ?: return@addOnButtonCheckedListener
        viewModel.setRecorderWidgetVisibility(mode)
        applyMode(mode)
    }

    binding.groupWidgets.switchHideRecorderOnRecordTab.setOnCheckedChangeListener { _, checked ->
        viewModel.setHideRecorderOnRecordTab(checked)
    }

    binding.groupWidgets.switchAlwaysShowRecorderPill.setOnCheckedChangeListener { _, checked ->
        viewModel.setAlwaysShowRecorderPill(checked)
    }

    binding.groupWidgets.switchRecorderStartCollapsed.setOnCheckedChangeListener { _, checked ->
        viewModel.setRecorderStartCollapsed(checked)
    }

    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { viewModel.recorderWidgetVisibility.collect { applyMode(it) } }
            launch { viewModel.hideRecorderOnRecordTab.collect  { binding.groupWidgets.switchHideRecorderOnRecordTab.isChecked  = it } }
            launch { viewModel.alwaysShowRecorderPill.collect   { binding.groupWidgets.switchAlwaysShowRecorderPill.isChecked   = it } }
        }
    }
}

// ── Playback widget ───────────────────────────────────────────────────────────

internal fun SettingsFragment.setupPlaybackWidgetSection() {
    val playerToggleGroup = binding.groupWidgets.togglePlayerVisibility

    val playerBtnToMode = mapOf(
        R.id.btnPlayerVisNever        to PlayerWidgetVisibility.NEVER,
        R.id.btnPlayerVisWhilePlaying to PlayerWidgetVisibility.WHILE_PLAYING,
        R.id.btnPlayerVisAlways       to PlayerWidgetVisibility.ALWAYS
    )
    val playerModeToBtn = playerBtnToMode.entries.associate { (k, v) -> v to k }

    fun applyPlayerMode(mode: PlayerWidgetVisibility) {
        playerToggleGroup.check(playerModeToBtn[mode] ?: R.id.btnPlayerVisWhilePlaying)
        val dependentEnabled = mode != PlayerWidgetVisibility.NEVER
        binding.groupWidgets.rowHidePlayerOnListenTab.alpha    = if (dependentEnabled) 1f else 0.4f
        binding.groupWidgets.switchHidePlayerOnListenTab.isEnabled = dependentEnabled
        binding.groupWidgets.rowPlayerStartCollapsed.alpha     = if (dependentEnabled) 1f else 0.4f
        binding.groupWidgets.switchPlayerStartCollapsed.isEnabled  = dependentEnabled
    }

    applyPlayerMode(viewModel.playerWidgetVisibility.value)
    binding.groupWidgets.switchHidePlayerOnListenTab.isChecked  = viewModel.hidePlayerOnListenTab.value
    binding.groupWidgets.switchPlayerStartCollapsed.isChecked   = viewModel.playerStartCollapsed.value
    binding.groupWidgets.switchAlwaysShowPlayerPill.isChecked   = viewModel.alwaysShowPlayerPill.value

    playerToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
        if (!isChecked) return@addOnButtonCheckedListener
        val mode = playerBtnToMode[checkedId] ?: return@addOnButtonCheckedListener
        viewModel.setPlayerWidgetVisibility(mode)
        applyPlayerMode(mode)
    }

    binding.groupWidgets.switchHidePlayerOnListenTab.setOnCheckedChangeListener { _, checked ->
        viewModel.setHidePlayerOnListenTab(checked)
    }

    binding.groupWidgets.switchAlwaysShowPlayerPill.setOnCheckedChangeListener { _, checked ->
        viewModel.setAlwaysShowPlayerPill(checked)
    }

    binding.groupWidgets.switchPlayerStartCollapsed.setOnCheckedChangeListener { _, checked ->
        viewModel.setPlayerStartCollapsed(checked)
    }

    // ── Browse destination (used when nothing is selected) ────────────────────
    val destBtnToMode = mapOf(
        R.id.btnPlayerBrowseAll    to PlayerBrowseDestination.ALL_RECORDINGS,
        R.id.btnPlayerBrowseTopics to PlayerBrowseDestination.TOPICS
    )
    val destModeToBtn = destBtnToMode.entries.associate { (k, v) -> v to k }

    binding.groupWidgets.togglePlayerBrowseDest.check(
        destModeToBtn[viewModel.playerBrowseDestination.value] ?: R.id.btnPlayerBrowseAll
    )
    binding.groupWidgets.togglePlayerBrowseDest.addOnButtonCheckedListener { _, checkedId, isChecked ->
        if (!isChecked) return@addOnButtonCheckedListener
        destBtnToMode[checkedId]?.let { viewModel.setPlayerBrowseDestination(it) }
    }

    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { viewModel.playerWidgetVisibility.collect { mode -> applyPlayerMode(mode) } }
            launch { viewModel.hidePlayerOnListenTab.collect  { hide -> binding.groupWidgets.switchHidePlayerOnListenTab.isChecked = hide } }
            launch { viewModel.alwaysShowPlayerPill.collect   { show -> binding.groupWidgets.switchAlwaysShowPlayerPill.isChecked  = show } }
        }
    }
}