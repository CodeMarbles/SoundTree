package com.treecast.app.ui

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.treecast.app.R
import com.treecast.app.data.entities.BackupLogEntity
import com.treecast.app.ui.MainActivity.Companion.PAGE_SETTINGS
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// MainActivityBackupStrip.kt
//
// Extension functions on MainActivity covering the title-bar backup status strip:
//   • setupBackupStatusStrip() — wires the strip to backupStripState Flow,
//                                drives Running/Completed content, animations,
//                                auto-dismiss timers, and tap-to-navigate
//   • slideStripDown()        — animates the strip into view
//   • slideStripUp()          — animates the strip out of view
//
// Instance state kept in MainActivity.kt:
//   stripAutoDismissHandler   — Handler for the auto-dismiss postDelayed call
//   stripCurrentDismissLogId  — guards against re-starting the timer for an
//                               already-scheduled dismiss
// Both must be `internal` for this file to access them.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Observes [MainViewModel.backupStripState] and drives the title-bar status
 * strip: slide-down animation on appearance, content binding for Running and
 * Completed states, auto-dismiss timers, and tap-to-navigate behaviour.
 */
internal fun MainActivity.setupBackupStatusStrip() {
    val strip       = binding.backupStatusStrip
    val stripHeight = resources.getDimensionPixelSize(R.dimen.backup_strip_height)
    val context = this

    // Start off-screen so the first slide-down looks correct.
    strip.root.translationY = -stripHeight.toFloat()

    // Pulsing animator for the border while a job is running.
    val pulseAnimator = ValueAnimator.ofFloat(1f, 0.35f, 1f).apply {
        duration     = 1_400
        repeatCount  = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { anim ->
            strip.backupStripBorder.alpha = anim.animatedValue as Float
        }
    }

    var lastState: BackupStripState = BackupStripState.Hidden

    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.backupStripState.collect { state ->
                val wasHidden = lastState is BackupStripState.Hidden
                lastState = state

                when (state) {
                    is BackupStripState.Hidden -> {
                        if (strip.root.visibility == View.VISIBLE) {
                            slideStripUp(strip.root, stripHeight, pulseAnimator)
                        }
                    }

                    is BackupStripState.Running -> {
                        val log = state.primary.log

                        // Content
                        strip.tvStripStatus.text =
                            getString(R.string.backup_strip_running, log.volumeLabel)
                        strip.tvStripBadge.visibility =
                            if (state.extraCount > 0) View.VISIBLE else View.GONE
                        strip.tvStripBadge.text =
                            getString(R.string.backup_strip_extra_jobs, state.extraCount)

                        // Progress bar
                        strip.progressStrip.visibility = View.VISIBLE
                        val estimatedTotal = viewModel.backupLogs.value
                            .firstOrNull { it.volumeUuid == log.volumeUuid && it.status != null }
                            ?.totalBytesOnDestination ?: 0L
                        if (estimatedTotal > 0 && log.bytesCopied > 0) {
                            val prog = ((log.bytesCopied.toFloat() / estimatedTotal) * 10_000)
                                .toInt().coerceIn(0, 10_000)
                            strip.progressStrip.isIndeterminate = false
                            strip.progressStrip.setProgress(prog, true)
                        } else {
                            strip.progressStrip.isIndeterminate = true
                        }

                        // Border: pulsing blue
                        strip.backupStripBorder.setBackgroundColor(
                            ContextCompat.getColor(context, R.color.backup_strip_running)
                        )
                        if (!pulseAnimator.isRunning) pulseAnimator.start()

                        if (wasHidden) slideStripDown(strip.root, stripHeight)

                        // Cancel any pending auto-dismiss for the previous completion.
                        stripAutoDismissHandler.removeCallbacksAndMessages(null)
                    }

                    is BackupStripState.Completed -> {
                        pulseAnimator.cancel()
                        strip.backupStripBorder.alpha = 1f
                        strip.progressStrip.visibility = View.GONE

                        // Content
                        val log = state.log
                        strip.tvStripStatus.text = when (log.status) {
                            BackupLogEntity.BackupStatus.SUCCESS ->
                                getString(R.string.backup_strip_success, log.filesCopied)
                            BackupLogEntity.BackupStatus.PARTIAL ->
                                getString(R.string.backup_strip_partial, log.filesFailed)
                            else ->
                                getString(R.string.backup_strip_failed)
                        }
                        strip.tvStripBadge.visibility = View.GONE

                        // Border colour reflects outcome
                        val borderColor = when (log.status) {
                            BackupLogEntity.BackupStatus.SUCCESS ->
                                ContextCompat.getColor(context, R.color.backup_strip_success)
                            BackupLogEntity.BackupStatus.PARTIAL ->
                                ContextCompat.getColor(context, R.color.backup_strip_partial)
                            else ->
                                ContextCompat.getColor(context, R.color.backup_strip_failed)
                        }
                        strip.backupStripBorder.setBackgroundColor(borderColor)

                        if (wasHidden) slideStripDown(strip.root, stripHeight)

                        // Auto-dismiss timer
                        val dismissDelay = state.autoDismissMs
                        stripAutoDismissHandler.removeCallbacksAndMessages(null)
                        if (dismissDelay != null && stripCurrentDismissLogId != log.id) {
                            stripCurrentDismissLogId = log.id
                            stripAutoDismissHandler.postDelayed({
                                viewModel.dismissBackupStrip(log.id)
                            }, dismissDelay)
                        }
                    }
                }
            }
        }
    }

    // Tap → navigate to Settings → Storage; dismiss a completed strip on tap
    strip.root.setOnClickListener {
        binding.viewPager.setCurrentItem(PAGE_SETTINGS, false)
        viewModel.requestNavigateToStorageTab()
        (lastState as? BackupStripState.Completed)?.let {
            stripAutoDismissHandler.removeCallbacksAndMessages(null)
            viewModel.dismissBackupStrip(it.log.id)
        }
    }
}

// ── Slide animations ──────────────────────────────────────────────────────────

private fun MainActivity.slideStripDown(view: View, stripHeight: Int) {
    view.translationY = -stripHeight.toFloat()
    view.visibility   = View.VISIBLE
    view.animate()
        .translationY(0f)
        .setDuration(200)
        .setInterpolator(DecelerateInterpolator())
        .start()
}

private fun MainActivity.slideStripUp(view: View, stripHeight: Int, pulseAnimator: ValueAnimator) {
    pulseAnimator.cancel()
    view.animate()
        .translationY(-stripHeight.toFloat())
        .setDuration(150)
        .setInterpolator(AccelerateInterpolator())
        .withEndAction { view.visibility = View.GONE }
        .start()
}