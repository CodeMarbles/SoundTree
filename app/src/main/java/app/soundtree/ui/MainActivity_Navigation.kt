package app.soundtree.ui

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import app.soundtree.R
import app.soundtree.service.RecordingService
import app.soundtree.ui.library.LibraryFragment
import app.soundtree.ui.listen.ListenFragment
import app.soundtree.ui.record.RecordFragment
import app.soundtree.ui.settings.SettingsFragment
import app.soundtree.ui.workspace.WorkspaceFragment
import app.soundtree.util.themeColor
import kotlinx.coroutines.launch

import app.soundtree.ui.MainActivity.Companion.PAGE_SETTINGS
import app.soundtree.ui.MainActivity.Companion.PAGE_RECORD
import app.soundtree.ui.MainActivity.Companion.PAGE_LIBRARY
import app.soundtree.ui.MainActivity.Companion.PAGE_LISTEN
import app.soundtree.ui.MainActivity.Companion.PAGE_WORKSPACE

// ─────────────────────────────────────────────────────────────────────────────
// MainActivity_Navigation.kt
//
// Extension functions on MainActivity covering:
//   • ViewPager2 setup and page-change callback
//   • Bottom nav wiring and selection rendering
//   • Back-stack navigation
//   • Tab-change override resets (mini player/recorder hide suppression)
//   • Top title observation
//   • Lock overlay observation
// ─────────────────────────────────────────────────────────────────────────────

// ── ViewPager ─────────────────────────────────────────────────────────────────

internal fun MainActivity.setupViewPager() {

    val adapter = MainPagerAdapter(this).apply {
        addFragment(SettingsFragment(),  getString(R.string.tab_settings))
        addFragment(RecordFragment(),    getString(R.string.tab_record))
        addFragment(LibraryFragment(),   getString(R.string.tab_library))
        addFragment(ListenFragment(),    getString(R.string.tab_listen))
        addFragment(WorkspaceFragment(), getString(R.string.tab_workspace))
    }
    binding.viewPager.adapter          = adapter
    binding.viewPager.isUserInputEnabled = false
    binding.viewPager.offscreenPageLimit = 5

    val startPage = if (isRestoredFromState) viewModel.currentPage.value else PAGE_RECORD
    binding.viewPager.setCurrentItem(startPage, false)

    binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            val recording = viewModel.recordingState.value != RecordingService.State.IDLE
            updateBottomNavSelection(position, recording)

            if (position != PAGE_LIBRARY) {
                viewModel.setTopTitle(pageTitle(position))
            } else {
                // LibraryFragment drives the title, but its inner page-change
                // callback won't fire on a swipe-in from another tab — nudge it.
                libraryFragment?.refreshTopTitle()
            }

            // ── History recording ──────────────────────────────────────────
            viewModel.setCurrentPage(position)

            // Skip recording when we are the ones driving this page
            // change as part of a back-press; only record genuine
            // forward navigation initiated by the user.
            if (isNavigatingBack) {
                isNavigatingBack = false
                return
            }
            // Avoid duplicate consecutive entries (e.g. from a
            // swipe that briefly reports the same page twice).
            if (navHistory.lastOrNull() != position) {
                navHistory.addLast(position)
            }
        }
    })
}

// ── Bottom nav ────────────────────────────────────────────────────────────────

internal fun MainActivity.setupBottomNav() {
    binding.navSettings.setOnClickListener  { navigateTo(PAGE_SETTINGS)  }
    binding.navRecord.setOnClickListener    { navigateTo(PAGE_RECORD)    }
    binding.navLibrary.setOnClickListener   { navigateTo(PAGE_LIBRARY)   }
    binding.navListen.setOnClickListener    { navigateTo(PAGE_LISTEN)    }
    binding.navWorkspace.setOnClickListener { navigateTo(PAGE_WORKSPACE) }

    val recording = viewModel.recordingState.value != RecordingService.State.IDLE
    updateBottomNavSelection(viewModel.currentPage.value, recording)

    // Re-draw nav whenever recording state changes so the Record
    // pill border appears/disappears independently of tab selection.
    lifecycleScope.launch {
        viewModel.recordingState.collect { state ->
            val isRecording = state != RecordingService.State.IDLE
            updateBottomNavSelection(viewModel.currentPage.value, isRecording)
        }
    }

    lifecycleScope.launch {
        viewModel.futureMode.collect { enabled ->
            binding.navWorkspace.visibility = if (enabled) View.VISIBLE else View.GONE
        }
    }
}

/**
 * Redraws all five bottom-nav pills to reflect [position] as the active page.
 *
 * Marked `internal` because it is also called from:
 *   • [MainActivity.navigateTo] (MainActivity.kt)
 *   • The `.post { }` guard in [MainActivity.onCreate] (MainActivity.kt)
 */
internal fun MainActivity.updateBottomNavSelection(
    position:    Int,
    isRecording: Boolean = false
) {
    val onActive  = themeColor(R.attr.colorTextPrimary)
    val dim       = themeColor(R.attr.colorTextSecondary)
    val radius    = 20f * resources.displayMetrics.density
    val strokePx  = (2.5f * resources.displayMetrics.density).toInt()
    val recordRed = themeColor(R.attr.colorRecordActive)

    val overshoot  = OvershootInterpolator(1.8f)
    val decelerate = DecelerateInterpolator()

    data class Tab(
        val pill:   LinearLayout,
        val icon:   ImageView,
        val label:  TextView,
        val bgAttr: Int,
        val page:   Int
    )

    listOf(
        Tab(binding.navSettingsPill,  binding.ivSettingsIcon,  binding.tvSettingsLabel,  R.attr.colorNavBgSettings,  PAGE_SETTINGS),
        Tab(binding.navRecordPill,    binding.ivRecordIcon,    binding.tvRecordLabel,    R.attr.colorNavBgRecord,    PAGE_RECORD),
        Tab(binding.navLibraryPill,   binding.ivLibraryIcon,   binding.tvLibraryLabel,  R.attr.colorNavBgLibrary,   PAGE_LIBRARY),
        Tab(binding.navListenPill,    binding.ivListenIcon,    binding.tvListenLabel,   R.attr.colorNavBgListen,    PAGE_LISTEN),
        Tab(binding.navWorkspacePill, binding.ivWorkspaceIcon, binding.tvWorkspaceLabel, R.attr.colorNavBgWorkspace, PAGE_WORKSPACE),
    ).forEach { tab ->
        val active      = position == tab.page
        val showBorder  = isRecording && tab.page == PAGE_RECORD
        val targetScale = if (active) 1.13f else 0.93f
        val interp      = if (active) overshoot else decelerate

        // ── Pill background ───────────────────────────────────────────────
        tab.pill.background = when {
            active || showBorder -> GradientDrawable().apply {
                shape        = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(if (active) themeColor(tab.bgAttr) else android.graphics.Color.TRANSPARENT)
                if (showBorder) setStroke(strokePx, recordRed)
            }
            else -> null
        }

        // ── Scale animation ───────────────────────────────────────────────
        tab.pill.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(200)
            .setInterpolator(interp)
            .start()

        // ── Icon + label tint ─────────────────────────────────────────────
        tab.icon.setColorFilter(if (active) onActive else dim)
        tab.icon.alpha = if (active) 1f else 0.5f
        tab.label.setTextColor(if (active) onActive else dim)
    }
}

// ── Back navigation ───────────────────────────────────────────────────────────

internal fun MainActivity.setupBackNavigation() {
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            // Give the Library fragment first crack at consuming the press
            // so it can pop its own sub-page stack (e.g. Details → Topics).
            if (binding.viewPager.currentItem == PAGE_LIBRARY &&
                libraryFragment?.handleBackPress() == true ) return

            if (navHistory.size > 1) {
                navHistory.removeLast()
                val target = navHistory.last()
                isNavigatingBack = true
                binding.viewPager.setCurrentItem(target, true)
            } else {
                finish()
            }
        }
    })
}

// ── Tab-change override reset ─────────────────────────────────────────────────

/**
 * Watches [MainViewModel.currentPage] and resets the per-visit
 * suppress-override flags whenever the user leaves the tab that owns
 * each widget.
 *
 * This means: if the user tapped the player pill to force-show the player
 * on the Listen tab, navigating away resets that override so the widget
 * re-hides on the next visit to that tab.
 */
internal fun MainActivity.setupTabChangeOverrideReset() {
    lifecycleScope.launch {
        var prevPage = viewModel.currentPage.value
        viewModel.currentPage.collect { page ->
            if (page != prevPage) {
                // ── Reset on EXIT from the suppressed tab ──────────────────
                if (prevPage == PAGE_LISTEN) viewModel.setPlayerHideOverriddenThisVisit(false)
                if (prevPage == PAGE_RECORD) viewModel.setRecorderHideOverriddenThisVisit(false)

                // ── Reset on ENTRY to the suppressed tab ───────────────────
                // Catches the case where the override was stale from a
                // previous visit and the exit reset didn't fire cleanly.
                if (page == PAGE_LISTEN) viewModel.setPlayerHideOverriddenThisVisit(false)
                if (page == PAGE_RECORD) viewModel.setRecorderHideOverriddenThisVisit(false)

                prevPage = page
            }
        }
    }
}

// ── Top title ─────────────────────────────────────────────────────────────────

internal fun MainActivity.observeTopTitle() {
    lifecycleScope.launch {
        viewModel.topTitle.collect { title ->
            binding.tvTopTitle.text = title
        }
    }
}

// ── Lock overlay ──────────────────────────────────────────────────────────────

@SuppressLint("ClickableViewAccessibility") // setOnTouchListener deliberately discards events
internal fun MainActivity.observeLockState() {
    lifecycleScope.launch {
        viewModel.isLocked.collect { locked ->
            binding.lockOverlay.visibility = if (locked) View.VISIBLE else View.GONE
        }
    }

    // Consume all touches on the overlay so nothing beneath it is accidentally
    // triggered while locked — but do NOT unlock on a bare tap.
    binding.lockOverlay.setOnTouchListener { _, _ -> true }

    // The only path to unlock is the explicit UNLOCK button.
    binding.btnUnlock.setOnClickListener {
        viewModel.setLocked(false)
    }
}

// ── Private helpers ───────────────────────────────────────────────────────────

private fun MainActivity.pageTitle(page: Int): String = when (page) {
    PAGE_SETTINGS  -> getString(R.string.tab_settings)
    PAGE_RECORD    -> getString(R.string.tab_record)
    PAGE_LIBRARY   -> getString(R.string.tab_library)
    PAGE_LISTEN    -> getString(R.string.tab_listen)
    PAGE_WORKSPACE -> getString(R.string.tab_workspace)
    else           -> ""
}