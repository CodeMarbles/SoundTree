package app.soundtree.ui.settings

import android.graphics.Typeface
import android.view.View
import app.soundtree.R
import app.soundtree.ui.MainViewModel
import app.soundtree.ui.setStartupLibraryTab
import app.soundtree.ui.setStartupTab
import app.soundtree.util.themeColor

// ─────────────────────────────────────────────────────────────────────────────
// SettingsFragment_Startup.kt
//
// Behavior tab — "Startup" card: default app tab + default Library sub-tab.
// ─────────────────────────────────────────────────────────────────────────────

internal fun SettingsFragment.setupStartupTab() {

    fun updateTabToggleVisuals(selected: String) {
        val activeText   = requireContext().themeColor(R.attr.colorTextPrimary)
        val activeBg     = requireContext().themeColor(R.attr.colorSurfaceElevated)
        val inactiveText = requireContext().themeColor(R.attr.colorTextSecondary)
        listOf(
            binding.groupStartup.btnStartupTabRecord  to MainViewModel.STARTUP_TAB_RECORD,
            binding.groupStartup.btnStartupTabLibrary to MainViewModel.STARTUP_TAB_LIBRARY,
        ).forEach { (btn, tab) ->
            val isActive = tab == selected
            btn.setTextColor(if (isActive) activeText else inactiveText)
            btn.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
            btn.setBackgroundColor(if (isActive) activeBg else android.graphics.Color.TRANSPARENT)
        }
        val showLibraryRow = selected == MainViewModel.STARTUP_TAB_LIBRARY
        binding.groupStartup.rowStartupLibraryTab.visibility = if (showLibraryRow) View.VISIBLE else View.GONE
        binding.groupStartup.dividerBeforeStartupLibraryTab.visibility = if (showLibraryRow) View.VISIBLE else View.GONE
    }

    fun updateLibraryTabToggleVisuals(selected: String) {
        val activeText   = requireContext().themeColor(R.attr.colorTextPrimary)
        val activeBg     = requireContext().themeColor(R.attr.colorSurfaceElevated)
        val inactiveText = requireContext().themeColor(R.attr.colorTextSecondary)
        listOf(
            binding.groupStartup.btnStartupLibraryTabAll      to MainViewModel.STARTUP_LIBRARY_TAB_ALL,
            binding.groupStartup.btnStartupLibraryTabUnsorted to MainViewModel.STARTUP_LIBRARY_TAB_UNSORTED,
            binding.groupStartup.btnStartupLibraryTabTopics   to MainViewModel.STARTUP_LIBRARY_TAB_TOPICS,
        ).forEach { (btn, tab) ->
            val isActive = tab == selected
            btn.setTextColor(if (isActive) activeText else inactiveText)
            btn.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
            btn.setBackgroundColor(if (isActive) activeBg else android.graphics.Color.TRANSPARENT)
        }
    }

    updateTabToggleVisuals(viewModel.startupTab.value)
    updateLibraryTabToggleVisuals(viewModel.startupLibraryTab.value)

    binding.groupStartup.btnStartupTabRecord.setOnClickListener {
        viewModel.setStartupTab(MainViewModel.STARTUP_TAB_RECORD)
        updateTabToggleVisuals(MainViewModel.STARTUP_TAB_RECORD)
    }
    binding.groupStartup.btnStartupTabLibrary.setOnClickListener {
        viewModel.setStartupTab(MainViewModel.STARTUP_TAB_LIBRARY)
        updateTabToggleVisuals(MainViewModel.STARTUP_TAB_LIBRARY)
    }

    binding.groupStartup.btnStartupLibraryTabAll.setOnClickListener {
        viewModel.setStartupLibraryTab(MainViewModel.STARTUP_LIBRARY_TAB_ALL)
        updateLibraryTabToggleVisuals(MainViewModel.STARTUP_LIBRARY_TAB_ALL)
    }
    binding.groupStartup.btnStartupLibraryTabUnsorted.setOnClickListener {
        viewModel.setStartupLibraryTab(MainViewModel.STARTUP_LIBRARY_TAB_UNSORTED)
        updateLibraryTabToggleVisuals(MainViewModel.STARTUP_LIBRARY_TAB_UNSORTED)
    }
    binding.groupStartup.btnStartupLibraryTabTopics.setOnClickListener {
        viewModel.setStartupLibraryTab(MainViewModel.STARTUP_LIBRARY_TAB_TOPICS)
        updateLibraryTabToggleVisuals(MainViewModel.STARTUP_LIBRARY_TAB_TOPICS)
    }
}