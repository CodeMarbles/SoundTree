package app.soundtree.ui.settings

import android.graphics.Typeface
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.soundtree.R
import app.soundtree.ui.MainViewModel
import app.soundtree.ui.setBgAlpha
import app.soundtree.ui.setBgExtendsUnderRuler
import app.soundtree.ui.setBgUnplayedOnly
import app.soundtree.ui.setInvertWaveformTheme
import app.soundtree.ui.setPlayheadVisEnabled
import app.soundtree.ui.setPlayheadVisIntensity
import app.soundtree.ui.setWaveformStyleKey
import app.soundtree.util.themeColor
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ── Waveform style ────────────────────────────────────────────────────────────

internal fun SettingsFragment.setupWaveformStyleSettings() {

    // ── View refs ─────────────────────────────────────────────────────────────
    val btnStyleStandard  = binding.groupWaveform.btnWaveformStyleStandard
    val btnStyleSky       = binding.groupWaveform.btnWaveformStyleSky
    val btnStyleSkyLights = binding.groupWaveform.btnWaveformStyleSkyLights
    val rowSubOptions     = binding.groupWaveform.rowWaveformSubOptions
    val switchInvert      = binding.groupWaveform.switchInvertWaveformTheme
    val rowInvert         = binding.groupWaveform.rowInvertWaveformTheme
    val sliderAlpha       = binding.groupWaveform.sliderWaveformBgAlpha
    val rowAlpha          = binding.groupWaveform.rowWaveformBgAlpha
    val switchRuler       = binding.groupWaveform.switchWaveformExtendsUnderRuler
    val switchUnplayed    = binding.groupWaveform.switchWaveformUnplayedOnly

    val btnToKey = mapOf(
        btnStyleStandard  to MainViewModel.STYLE_STANDARD,
        btnStyleSky       to MainViewModel.STYLE_SKY,
        btnStyleSkyLights to MainViewModel.STYLE_SKY_LIGHTS,
    )

    fun applyStyleButtonVisuals(activeKey: String) {
        val activeText   = requireContext().themeColor(R.attr.colorTextPrimary)
        val activeBg     = requireContext().themeColor(R.attr.colorSurfaceElevated)
        val inactiveText = requireContext().themeColor(R.attr.colorTextSecondary)
        btnToKey.forEach { (btn, key) ->
            val isActive = key == activeKey
            btn.setTextColor(if (isActive) activeText else inactiveText)
            btn.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
            btn.setBackgroundColor(if (isActive) activeBg else android.graphics.Color.TRANSPARENT)
        }
    }

    /** Dim and disable the sub-options block when Standard is selected. */
    fun applySubOptionState(styleKey: String) {
        val themed = styleKey != MainViewModel.STYLE_STANDARD
        rowSubOptions.alpha      = if (themed) 1f else 0.38f
        rowInvert.alpha          = if (themed) 1f else 0.38f
        rowAlpha.alpha           = if (themed) 1f else 0.38f
        switchInvert.isEnabled   = themed
        sliderAlpha.isEnabled    = themed
        switchRuler.isEnabled    = themed
        switchUnplayed.isEnabled = themed
    }

    // ── Seed from ViewModel ───────────────────────────────────────────────────
    val initialKey    = viewModel.waveformStyleKey.value
    val initialConfig = viewModel.waveformDisplayConfig.value

    applyStyleButtonVisuals(initialKey)
    applySubOptionState(initialKey)

    switchInvert.isChecked   = viewModel.invertWaveformTheme.value
    sliderAlpha.value        = (initialConfig.backgroundAlpha * 100f).roundToInt().toFloat().coerceIn(0f, 100f)
    switchRuler.isChecked    = initialConfig.extendsUnderRuler
    switchUnplayed.isChecked = initialConfig.unplayedOnly

    // ── Observe ───────────────────────────────────────────────────────────────
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
                    if (switchUnplayed.isChecked != cfg.unplayedOnly)      switchUnplayed.isChecked = cfg.unplayedOnly
                }
            }
        }
    }

    // ── User interaction ──────────────────────────────────────────────────────
    btnToKey.forEach { (btn, key) ->
        btn.setOnClickListener { viewModel.setWaveformStyleKey(key) }
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

// ── Playhead visualisation ────────────────────────────────────────────────────

internal fun SettingsFragment.setupPlayheadVis() {
    val switch       = binding.groupWaveform.switchPlayheadVisEnabled
    val rowIntensity = binding.groupWaveform.rowPlayheadVisIntensity
    val slider       = binding.groupWaveform.sliderPlayheadVisIntensity

    // Initial state
    switch.isChecked        = viewModel.playheadVisEnabled.value
    slider.value            = viewModel.playheadVisIntensity.value
    rowIntensity.visibility = if (switch.isChecked) View.VISIBLE else View.GONE

    switch.setOnCheckedChangeListener { _, checked ->
        viewModel.setPlayheadVisEnabled(checked)
        rowIntensity.visibility = if (checked) View.VISIBLE else View.GONE
    }

    slider.addOnChangeListener { _, value, fromUser ->
        if (fromUser) viewModel.setPlayheadVisIntensity(value)
    }
}