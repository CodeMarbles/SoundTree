package app.soundtree.ui.settings

import android.graphics.Typeface
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.soundtree.R
import app.soundtree.ui.setLayoutOrder
import app.soundtree.ui.setShowTitleBar
import app.soundtree.ui.setThemeMode
import app.soundtree.util.themeColor
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

// ── Appearance ────────────────────────────────────────────────────────────────

internal fun SettingsFragment.setupTheme() {
    fun updateToggleVisuals(selected: String) {
        val activeText   = requireContext().themeColor(R.attr.colorTextPrimary)
        val activeBg     = requireContext().themeColor(R.attr.colorSurfaceElevated)
        val inactiveText = requireContext().themeColor(R.attr.colorTextSecondary)
        listOf(
            binding.groupAppearance.btnThemeSystem to "system",
            binding.groupAppearance.btnThemeLight  to "light",
            binding.groupAppearance.btnThemeDark   to "dark"
        ).forEach { (btn, mode) ->
            val isActive = mode == selected
            btn.setTextColor(if (isActive) activeText else inactiveText)
            btn.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
            btn.setBackgroundColor(if (isActive) activeBg else android.graphics.Color.TRANSPARENT)
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

    binding.groupAppearance.btnThemeSystem.setOnClickListener { select("system") }
    binding.groupAppearance.btnThemeLight.setOnClickListener  { select("light")  }
    binding.groupAppearance.btnThemeDark.setOnClickListener   { select("dark")   }
}

internal fun SettingsFragment.setupLayoutSection() {
    val widget  = binding.groupAppearance.layoutReorderWidget
    val btnEdit = binding.groupAppearance.btnEditLayout
    val toggle  = binding.groupAppearance.switchShowTitleBar

    // ── Initialise widget from current ViewModel state ────────────────────────
    widget.setOrder(viewModel.layoutOrder.value)
    widget.showTitleBar = viewModel.showTitleBar.value
    toggle.isChecked    = viewModel.showTitleBar.value

    // ── Title bar toggle ──────────────────────────────────────────────────────
    toggle.setOnCheckedChangeListener { _, isChecked ->
        widget.showTitleBar = isChecked

        // If we aren't in edit mode show the title bar immediately
        if (!widget.isInEditMode) {
            viewModel.setShowTitleBar(isChecked)
            viewModel.setLayoutOrder(widget.getOrder())
        }
    }

    // ── Edit / Apply button ───────────────────────────────────────────────────
    fun enterEditMode() {
        widget.setEditing(true)
        btnEdit.text = getString(R.string.settings_btn_layout_apply)
    }

    fun applyAndLock() {
        viewModel.setLayoutOrder(widget.getOrder())
        viewModel.setShowTitleBar(widget.showTitleBar)
        widget.setEditing(false)
        btnEdit.text = getString(R.string.settings_btn_layout_edit)
    }

    btnEdit.setOnClickListener {
        if (widget.editing) applyAndLock() else enterEditMode()
    }

    // ── Keep widget in sync if another screen changes prefs ───────────────────
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