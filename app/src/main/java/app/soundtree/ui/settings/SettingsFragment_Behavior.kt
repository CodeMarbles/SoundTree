package app.soundtree.ui.settings

// ─────────────────────────────────────────────────────────────────────────────
// SettingsFragment_Behavior.kt
//
// Frequent Topics picker settings wiring.
// ─────────────────────────────────────────────────────────────────────────────

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.soundtree.ui.FREQUENT_TOPICS_LIMIT_MAX
import app.soundtree.ui.frequentTopicsEnabled
import app.soundtree.ui.frequentTopicsLimit
import app.soundtree.ui.frequentTopicsShowLabels
import app.soundtree.ui.frequentTopicsShowLineage
import app.soundtree.ui.setFrequentTopicsEnabled
import app.soundtree.ui.setFrequentTopicsLimit
import app.soundtree.ui.setFrequentTopicsShowLabels
import app.soundtree.ui.setFrequentTopicsShowLineage
import kotlinx.coroutines.launch

internal fun SettingsFragment.setupFrequentTopicsSection() {
    val g = binding.groupTopicPicker

    val switchEnabled     = g.switchFrequentTopicsEnabled
    val rowLimit          = g.rowFrequentTopicsLimit
    val btnMinus          = g.btnFrequentTopicsLimitMinus
    val tvLimit           = g.tvFrequentTopicsLimit
    val btnPlus           = g.btnFrequentTopicsLimitPlus
    val switchLineage     = g.switchFrequentTopicsShowLineage
    val rowLabels         = g.rowFrequentTopicsShowLabels
    val switchLabels      = g.switchFrequentTopicsShowLabels

    val DISABLED_ALPHA = 0.38f

    // ── Helper: sync stepper and child-row enabled state ─────────────────────

    fun renderLimit(limit: Int) {
        tvLimit.text = limit.toString()
        btnMinus.isEnabled = limit > 1
        btnPlus.isEnabled  = limit < FREQUENT_TOPICS_LIMIT_MAX
        btnMinus.alpha     = if (limit > 1) 1f else DISABLED_ALPHA
        btnPlus.alpha      = if (limit < FREQUENT_TOPICS_LIMIT_MAX) 1f else DISABLED_ALPHA
    }

    fun applyLineageDependentState(lineageOn: Boolean) {
        rowLabels.alpha     = if (lineageOn) 1f else DISABLED_ALPHA
        rowLabels.isEnabled = lineageOn
        // Propagate to children so the switch thumb itself is also non-interactive.
        switchLabels.isEnabled = lineageOn
    }

    fun applyMasterState(enabled: Boolean) {
        val alpha = if (enabled) 1f else DISABLED_ALPHA
        rowLimit.alpha      = alpha; rowLimit.isEnabled      = enabled
        btnMinus.isEnabled  = enabled && viewModel.frequentTopicsLimit.value > 1
        btnPlus.isEnabled   = enabled && viewModel.frequentTopicsLimit.value < FREQUENT_TOPICS_LIMIT_MAX
        switchLineage.isEnabled = enabled
        rowLabels.isEnabled     = enabled && viewModel.frequentTopicsShowLineage.value
        switchLabels.isEnabled  = enabled && viewModel.frequentTopicsShowLineage.value
        rowLabels.alpha     = if (enabled && viewModel.frequentTopicsShowLineage.value) 1f else DISABLED_ALPHA
    }

    // ── Seed UI from current ViewModel state ──────────────────────────────────

    switchEnabled.isChecked  = viewModel.frequentTopicsEnabled.value
    renderLimit(viewModel.frequentTopicsLimit.value)
    switchLineage.isChecked  = viewModel.frequentTopicsShowLineage.value
    switchLabels.isChecked   = viewModel.frequentTopicsShowLabels.value

    applyMasterState(viewModel.frequentTopicsEnabled.value)
    applyLineageDependentState(viewModel.frequentTopicsShowLineage.value)

    // ── Listeners ─────────────────────────────────────────────────────────────

    switchEnabled.setOnCheckedChangeListener { _, checked ->
        viewModel.setFrequentTopicsEnabled(checked)
        applyMasterState(checked)
    }

    btnMinus.setOnClickListener {
        val next = viewModel.frequentTopicsLimit.value - 1
        viewModel.setFrequentTopicsLimit(next)
        renderLimit(viewModel.frequentTopicsLimit.value)
    }

    btnPlus.setOnClickListener {
        val next = viewModel.frequentTopicsLimit.value + 1
        viewModel.setFrequentTopicsLimit(next)
        renderLimit(viewModel.frequentTopicsLimit.value)
    }

    switchLineage.setOnCheckedChangeListener { _, checked ->
        viewModel.setFrequentTopicsShowLineage(checked)
        applyLineageDependentState(checked)
        // If lineage is turned off the VM setter already clears labels;
        // sync the switch thumb without re-firing the listener.
        if (!checked) {
            switchLabels.setOnCheckedChangeListener(null)
            switchLabels.isChecked = false
            switchLabels.setOnCheckedChangeListener { _, on ->
                viewModel.setFrequentTopicsShowLabels(on)
            }
        }
    }

    switchLabels.setOnCheckedChangeListener { _, checked ->
        viewModel.setFrequentTopicsShowLabels(checked)
    }

    // ── Observe for external changes (e.g. prefs restored from backup) ────────

    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                viewModel.frequentTopicsEnabled.collect { enabled ->
                    if (switchEnabled.isChecked != enabled) switchEnabled.isChecked = enabled
                    applyMasterState(enabled)
                }
            }
            launch {
                viewModel.frequentTopicsLimit.collect { limit ->
                    renderLimit(limit)
                }
            }
            launch {
                viewModel.frequentTopicsShowLineage.collect { show ->
                    if (switchLineage.isChecked != show) switchLineage.isChecked = show
                    applyLineageDependentState(show)
                }
            }
            launch {
                viewModel.frequentTopicsShowLabels.collect { show ->
                    if (switchLabels.isChecked != show) switchLabels.isChecked = show
                }
            }
        }
    }
}