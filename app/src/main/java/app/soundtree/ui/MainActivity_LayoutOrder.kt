package app.soundtree.ui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.lifecycleScope
import app.soundtree.R
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// MainActivity_LayoutOrder.kt
//
// Extension functions on MainActivity covering the layout order system
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Rebuilds the root vertical stack from the saved element order.
 *
 * Called once in [MainActivity.onCreate] (synchronously, before first draw)
 * and again whenever the user taps Apply in Settings via [observeLayoutOrder].
 *
 * Each [LayoutElement] maps to a View already inflated from activity_main.xml:
 *   TITLE_BAR     → binding.titleBarContainer  (topBar + its divider, wrapped)
 *   CONTENT       → binding.viewPager
 *   MINI_PLAYER   → binding.miniPlayer.root
 *   MINI_RECORDER → binding.miniRecorder.root
 *   NAV           → binding.bottomNav
 *
 * The CONTENT view always gets weight=1 / height=0dp so it fills remaining
 * space regardless of position. All other views keep their fixed heights.
 */
internal fun MainActivity.applyLayoutOrder() {
    val order         = viewModel.layoutOrder.value
    val showTitle     = viewModel.showTitleBar.value
    val anyPillActive = viewModel.playerPillMinimized.value    || viewModel.recorderPillMinimized.value ||
            viewModel.alwaysShowPlayerPill.value   || viewModel.alwaysShowRecorderPill.value
    val stripActive   = viewModel.backupStripState.value !is BackupStripState.Hidden
    val pillOnlyMode  = !showTitle && (anyPillActive || stripActive)

    val viewMap = mapOf(
        LayoutElement.TITLE_BAR     to binding.titleBarContainer,
        LayoutElement.CONTENT       to binding.viewPager,
        LayoutElement.MINI_PLAYER   to binding.miniPlayer.root,
        LayoutElement.MINI_RECORDER to binding.miniRecorder.root,
        LayoutElement.NAV           to binding.bottomNav
    )

    binding.rootStack.removeAllViews()

    val dp = resources.displayMetrics.density

    for (element in order) {
        val view = viewMap[element] ?: continue
        if (element == LayoutElement.TITLE_BAR && !showTitle && !anyPillActive && !stripActive) continue
        if (element == LayoutElement.CONTENT) {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            binding.rootStack.addView(view, lp)
        } else {
            val heightPx = when (element) {
                LayoutElement.MINI_PLAYER   -> (108 * dp).toInt()
                LayoutElement.MINI_RECORDER -> (108 * dp).toInt()
                LayoutElement.NAV           -> (64 * dp).toInt()
                LayoutElement.TITLE_BAR ->
                    if (pillOnlyMode || stripActive) LinearLayout.LayoutParams.WRAP_CONTENT
                    else (53 * dp).toInt()
                else -> LinearLayout.LayoutParams.WRAP_CONTENT
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, heightPx
            )
            binding.rootStack.addView(view, lp)
        }
    }

    // Pill-only mode: when the title bar is disabled but a pill is active,
    // shrink the inner topBar to just fit the pill and hide the title text.
    val topBarLp = binding.topBar.layoutParams
    topBarLp.height = if (pillOnlyMode) ViewGroup.LayoutParams.WRAP_CONTENT else (52 * dp).toInt()
    binding.topBar.layoutParams = topBarLp
    binding.topBar.setPadding(0, if (pillOnlyMode) 1 else 0, 0, 0)
    binding.tvTopTitle.visibility = if (pillOnlyMode) View.GONE else View.VISIBLE

    updateMiniPlayerAccentLine(order)
    updateMiniRecorderAccentLine(order)
    updatePillChevronDirections(order)
}

/**
 * Observes [MainViewModel.layoutOrder], [MainViewModel.showTitleBar], and the
 * four pill-state flows together, re-applying the layout whenever any of them
 * changes.
 *
 * Uses [combine] so a single Apply that updates multiple flows simultaneously
 * only triggers one re-layout pass.
 */
internal fun MainActivity.observeLayoutOrder() {
    lifecycleScope.launch {
        val innerFlow = combine(
            viewModel.layoutOrder,
            viewModel.showTitleBar,
            viewModel.playerPillMinimized,
            viewModel.recorderPillMinimized
        ) { _, _, _, _ -> }

        combine(
            innerFlow,
            viewModel.alwaysShowPlayerPill,
            viewModel.alwaysShowRecorderPill,
            viewModel.backupStripState
        ) { _, _, _, _ -> }
            .collect { applyLayoutOrder() }
    }
}

/**
 * Positions the mini-player accent line so it always borders the edge
 * that faces the Content view — bottom edge if Content is below, top edge
 * if Content is above.
 */
private fun MainActivity.updateMiniPlayerAccentLine(order: List<LayoutElement>) {
    val miniIdx    = order.indexOf(LayoutElement.MINI_PLAYER)
    val contentIdx = order.indexOf(LayoutElement.CONTENT)
    if (miniIdx == -1 || contentIdx == -1) return

    val contentIsBelow = contentIdx > miniIdx

    val miniPlayerRoot = binding.miniPlayer.root as? ConstraintLayout ?: return
    val accentLine     = binding.miniPlayer.accentLine
    val miniContent    = binding.miniPlayer.miniContent

    val cs = ConstraintSet()
    cs.clone(miniPlayerRoot)

    if (contentIsBelow) {
        // Miniplayer is ABOVE content → accent on the BOTTOM edge
        cs.clear(accentLine.id, ConstraintSet.TOP)
        cs.connect(accentLine.id, ConstraintSet.BOTTOM,
            ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        // Content fills the space above the accent line.
        cs.connect(miniContent.id, ConstraintSet.TOP,
            ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        cs.connect(miniContent.id, ConstraintSet.BOTTOM,
            accentLine.id, ConstraintSet.TOP)
    } else {
        // Miniplayer is BELOW content → accent on the TOP edge
        cs.clear(accentLine.id, ConstraintSet.BOTTOM)
        cs.connect(accentLine.id, ConstraintSet.TOP,
            ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        // Content fills the space below the accent line.
        cs.connect(miniContent.id, ConstraintSet.TOP,
            accentLine.id, ConstraintSet.BOTTOM)
        cs.connect(miniContent.id, ConstraintSet.BOTTOM,
            ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
    }

    cs.applyTo(miniPlayerRoot)
}

/**
 * Mirrors [updateMiniPlayerAccentLine] for the Mini Recorder.
 * Positions the accent line on the edge facing the Content view.
 */
private fun MainActivity.updateMiniRecorderAccentLine(order: List<LayoutElement>) {
    val recIdx     = order.indexOf(LayoutElement.MINI_RECORDER)
    val contentIdx = order.indexOf(LayoutElement.CONTENT)
    if (recIdx == -1 || contentIdx == -1) return

    val contentIsBelow = contentIdx > recIdx

    val recRoot    = binding.miniRecorder.root as? ConstraintLayout ?: return
    val accentLine = binding.miniRecorder.recAccentLine
    val recContent = binding.miniRecorder.recContent

    val cs = ConstraintSet()
    cs.clone(recRoot)

    if (contentIsBelow) {
        // Recorder ABOVE content → accent on BOTTOM edge
        cs.clear(accentLine.id, ConstraintSet.TOP)
        cs.connect(accentLine.id, ConstraintSet.BOTTOM,
            ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        cs.connect(recContent.id, ConstraintSet.TOP,
            ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        cs.connect(recContent.id, ConstraintSet.BOTTOM,
            accentLine.id, ConstraintSet.TOP)
    } else {
        // Recorder BELOW content → accent on TOP edge
        cs.clear(accentLine.id, ConstraintSet.BOTTOM)
        cs.connect(accentLine.id, ConstraintSet.TOP,
            ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        cs.connect(recContent.id, ConstraintSet.TOP,
            accentLine.id, ConstraintSet.BOTTOM)
        cs.connect(recContent.id, ConstraintSet.BOTTOM,
            ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
    }

    cs.applyTo(recRoot)
}

/**
 * Sets each mini-widget's minimize chevron to point toward the title bar
 * (the destination of the minimized pill).
 *
 * Chevron points UP   when the title bar is ABOVE the widget.
 * Chevron points DOWN when the title bar is BELOW the widget.
 */
private fun MainActivity.updatePillChevronDirections(order: List<LayoutElement>) {
    val titleIdx    = order.indexOf(LayoutElement.TITLE_BAR)
    val playerIdx   = order.indexOf(LayoutElement.MINI_PLAYER)
    val recorderIdx = order.indexOf(LayoutElement.MINI_RECORDER)

    fun chevronRes(widgetIdx: Int): Int {
        if (titleIdx == -1 || widgetIdx == -1) return R.drawable.ic_chevron_up
        return if (titleIdx < widgetIdx) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
    }
    binding.miniPlayer.btnMiniPlayerMinimize.setImageResource(chevronRes(playerIdx))
    binding.miniRecorder.btnMiniRecorderMinimize.setImageResource(chevronRes(recorderIdx))
}

/**
 * Dims [tvTopTitle] when either pill is overlaying it, restores full alpha
 * when neither is visible.
 *
 * Marked internal because it is also called after each pill visibility update
 * from [MainActivityMiniPlayer] and [MainActivityMiniRecorder].
 */
internal fun MainActivity.updateTitleTextAlpha() {
    val anyPill = binding.titlePills.pillPlayer.visibility   == View.VISIBLE ||
            binding.titlePills.pillRecorder.visibility == View.VISIBLE
    binding.tvTopTitle.animate()
        .alpha(if (anyPill) 0.35f else 1.0f)
        .setDuration(150)
        .start()
}