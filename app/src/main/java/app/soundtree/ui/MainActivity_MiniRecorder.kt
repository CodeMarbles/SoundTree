package app.soundtree.ui

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.View
import androidx.lifecycle.lifecycleScope
import app.soundtree.R
import app.soundtree.service.RecordingService
import app.soundtree.ui.MainActivity.Companion.PAGE_RECORD
import app.soundtree.ui.common.TopicPickerBottomSheet
import app.soundtree.util.Icons
import app.soundtree.util.themeColor
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// MainActivity_MiniRecorder.kt
//
// Extension functions on MainActivity covering the Mini Recorder widget
// ─────────────────────────────────────────────────────────────────────────────

// ── Mini Recorder — controls + content observers ──────────────────────────────

/**
 * Wires all interactive controls on the Mini Recorder bar and starts the
 * observers that keep its content in sync with [MainViewModel].
 *
 * Call order in [MainActivity.onCreate]:
 *   setupMiniRecorder()         ← this function
 *   setupMiniRecorderMinimize()
 */
internal fun MainActivity.setupMiniRecorder() {
    val recorderBinding = binding.miniRecorder
    recorderBinding.miniRecTimeline.showLastMarkTimestamp = true

    // ── Background color: 3-state animated ───────────────────────────────
    val idleColor      = themeColor(R.attr.colorMiniRecorderIdle)
    val recordingColor = themeColor(R.attr.colorMiniRecorderActive)  // dark/light red
    val pausedColor    = themeColor(R.attr.colorMiniRecorderPaused)  // dark/light yellow
    val idleAccentColor   = themeColor(R.attr.colorPillRecorderIdleStroke)
    val recordAccentColor = themeColor(R.attr.colorRecordActive)
    val pauseAccentColor  = themeColor(R.attr.colorRecordPause)

    var lastBgColor = idleColor

    lifecycleScope.launch {
        viewModel.recordingState.collect { state ->
            val targetColor = when (state) {
                RecordingService.State.IDLE      -> idleColor
                RecordingService.State.RECORDING -> recordingColor
                RecordingService.State.PAUSED    -> pausedColor
            }
            val accentColor = when (state) {
                RecordingService.State.IDLE      -> idleAccentColor
                RecordingService.State.RECORDING -> recordAccentColor
                RecordingService.State.PAUSED    -> pauseAccentColor
            }
            recorderBinding.recAccentLine.setBackgroundColor(accentColor)
            ValueAnimator.ofArgb(lastBgColor, targetColor).apply {
                duration = 300L
                addUpdateListener {
                    val c = it.animatedValue as Int
                    recorderBinding.root.setBackgroundColor(c)
                    lastBgColor = c
                }
                start()
            }
        }
    }

    // ── State label: icon + text + color ──────────────────────────────────
    lifecycleScope.launch {
        viewModel.recordingState.collect { state ->
            val (iconRes, label, color) = when (state) {
                RecordingService.State.IDLE ->
                    Triple(
                        R.drawable.ic_stop_square,
                        getString(R.string.record_pill_status_idle),
                        themeColor(R.attr.colorTextSecondary)
                    )
                RecordingService.State.RECORDING ->
                    Triple(
                        R.drawable.ic_record_circle,
                        getString(R.string.record_pill_status_recording),
                        themeColor(R.attr.colorRecordActive)
                    )
                RecordingService.State.PAUSED ->
                    Triple(
                        R.drawable.ic_pause,
                        getString(R.string.record_pill_status_paused),
                        themeColor(R.attr.colorRecordPause)
                    )
            }
            recorderBinding.ivMiniRecStateIcon.setImageResource(iconRes)
            recorderBinding.ivMiniRecStateIcon.imageTintList = ColorStateList.valueOf(color)
            recorderBinding.tvMiniRecStateLabel.text = label
            recorderBinding.tvMiniRecStateLabel.setTextColor(color)
        }
    }

    // ── Record/Pause button icon + tint ───────────────────────────────────
    lifecycleScope.launch {
        viewModel.recordingState.collect { state ->
            when (state) {
                RecordingService.State.IDLE -> {
                    recorderBinding.btnMiniRecPause.setImageResource(R.drawable.ic_record_circle_mini)
                    recorderBinding.btnMiniRecPause.imageTintList = null  // let vector's own colours show
                }
                RecordingService.State.RECORDING -> {
                    recorderBinding.btnMiniRecPause.setImageResource(R.drawable.ic_pause)
                    recorderBinding.btnMiniRecPause.imageTintList =
                        ColorStateList.valueOf(themeColor(R.attr.colorRecordPause))
                }
                RecordingService.State.PAUSED -> {
                    recorderBinding.btnMiniRecPause.setImageResource(R.drawable.ic_resume_circle)
                    recorderBinding.btnMiniRecPause.imageTintList =
                        ColorStateList.valueOf(themeColor(R.attr.colorRecordActive))
                }
            }
        }
    }

    // ── Record/Pause button action ────────────────────────────────────────
    recorderBinding.btnMiniRecPause.setOnClickListener {
        when (viewModel.recordingState.value) {
            RecordingService.State.IDLE ->
                // triggerQuickRecord() handles mic permission + service-binding race.
                recordFragment?.triggerQuickRecord()
            RecordingService.State.RECORDING,
            RecordingService.State.PAUSED ->
                viewModel.requestToggleRecordingPause()
        }
    }

    // ── Dim / disable non-record controls when IDLE ───────────────────────
    //
    // IDLE:               save, mark cluster, and timeline are inert.
    //                     Elapsed text is near-invisible (alpha 0.15).
    // RECORDING / PAUSED: everything is fully live.
    lifecycleScope.launch {
        viewModel.recordingState.collect { state ->
            val active = state != RecordingService.State.IDLE

            val inertViews = listOf(
                recorderBinding.btnMiniRecSave,
                recorderBinding.btnMiniMark,
                recorderBinding.btnMiniRecDeleteMark,
                recorderBinding.btnMiniNudgeBack,
                recorderBinding.btnMiniNudgeForward,
                recorderBinding.btnMiniNudgeCommit,
                recorderBinding.miniRecTimeline
            )
            for (v in inertViews) {
                v.isEnabled = active
                v.alpha     = if (active) 1f else 0.25f
            }

            // Elapsed timer: nearly invisible when IDLE (it just says 0:00)
            recorderBinding.tvMiniRecElapsed.alpha = when (state) {
                RecordingService.State.IDLE      -> 0.15f
                RecordingService.State.PAUSED    -> 0.6f
                RecordingService.State.RECORDING -> 1f
            }

            // Note: the nudge cluster's own enabled/alpha logic (which
            // accounts for marks + lock state) runs in a separate observer.
            // That observer's alpha writes only fire when active=true because
            // marks will be empty while IDLE, so the 0.25f set above won't
            // be overridden to 1f while the service is stopped.
        }
    }

    // ── Save button ───────────────────────────────────────────────────────
    recorderBinding.btnMiniRecSave.setOnClickListener {
        navigateTo(PAGE_RECORD)
        binding.root.postDelayed({ recordFragment?.triggerSaveFromExternal() }, 80L)
    }

    // ── Timeline: elapsed + marks + selected index ────────────────────────
    lifecycleScope.launch {
        combine(
            viewModel.recordingElapsedMs,
            viewModel.recordingMarks,
            viewModel.selectedRecordingMarkIndex
        ) { elapsed, marks, selectedIdx -> Triple(elapsed, marks, selectedIdx) }
            .collect { (elapsed, marks, selectedIdx) ->
                recorderBinding.miniRecTimeline.update(elapsed, marks, selectedIdx)
                recorderBinding.tvMiniRecElapsed.text = formatMs(elapsed)
            }
    }

    // ── Timeline: live amplitude feed for waveform + shimmer ─────────────
    lifecycleScope.launch {
        viewModel.liveAmplitude.collect { amp ->
            recorderBinding.miniRecTimeline.pushAmplitude(amp)
        }
    }

    // ── Timeline: isRecording flag ────────────────────────────────────────
    lifecycleScope.launch {
        viewModel.recordingState.collect { state ->
            recorderBinding.miniRecTimeline.isRecording =
                state == RecordingService.State.RECORDING
        }
    }

    // ── Timeline: mark tap → select that mark ─────────────────────────────
    recorderBinding.miniRecTimeline.onMarkTapped = { index ->
        viewModel.selectRecordingMark(index)
    }
    // Timeline needs to be clickable to receive touch events
    recorderBinding.miniRecTimeline.isClickable = true

    // ── Mark button ───────────────────────────────────────────────────────
    recorderBinding.btnMiniMark.setOnClickListener {
        viewModel.requestDropMark()
    }

    // ── Nudge back ────────────────────────────────────────────────────────
    recorderBinding.btnMiniNudgeBack.setOnClickListener {
        viewModel.requestNudgeBack()
    }

    // ── Nudge forward ─────────────────────────────────────────────────────
    recorderBinding.btnMiniNudgeForward.setOnClickListener {
        viewModel.requestNudgeForward()
    }

    // ── Commit nudge ──────────────────────────────────────────────────────
    recorderBinding.btnMiniNudgeCommit.setOnClickListener {
        viewModel.commitMarkNudge()
    }

    // ── Delete selected recording mark ────────────────────────────────────
    recorderBinding.btnMiniRecDeleteMark.setOnClickListener {
        viewModel.deleteSelectedRecordingMark()
    }

    // ── Enable/disable nudge cluster ──────────────────────────────────────
    lifecycleScope.launch {
        combine(
            viewModel.recordingMarks,
            viewModel.selectedRecordingMarkIndex,
            viewModel.markNudgeLocked
        ) { marks, selectedIdx, locked ->
            Triple(marks, selectedIdx, locked)
        }.collect { (marks, selectedIdx, locked) ->
            val hasSelection = marks.isNotEmpty() && selectedIdx >= 0
            val canNudge     = hasSelection && !locked

            val tealColor = themeColor(R.attr.colorMarkSelected)
            val greyColor = themeColor(R.attr.colorTextSecondary)

            recorderBinding.btnMiniRecDeleteMark.isEnabled = hasSelection
            recorderBinding.btnMiniRecDeleteMark.alpha     = if (hasSelection) 1f else 0.3f
            recorderBinding.btnMiniRecDeleteMark.imageTintList =
                ColorStateList.valueOf(if (hasSelection) tealColor else greyColor)

            for (v in listOf(
                recorderBinding.btnMiniNudgeBack,
                recorderBinding.btnMiniNudgeForward,
                recorderBinding.btnMiniNudgeCommit
            )) {
                v.isEnabled      = canNudge
                v.alpha          = if (canNudge) 1f else 0.3f
                v.imageTintList  = ColorStateList.valueOf(if (canNudge) tealColor else greyColor)
            }
        }
    }

    // ── Topic icon observer ───────────────────────────────────────────────
    lifecycleScope.launch {
        combine(viewModel.recordingTopicId, viewModel.allTopics) { topicId, topics ->
            topics.firstOrNull { it.id == topicId }?.icon ?: Icons.UNSORTED
        }.collect { icon ->
            recorderBinding.btnMiniRecTopic.text = icon
        }
    }

    // ── Topic picker result ───────────────────────────────────────────────
    supportFragmentManager.setFragmentResultListener(
        TopicPickerBottomSheet.REQUEST_KEY + "_mini_rec", this
    ) { _, bundle ->
        val topicId = TopicPickerBottomSheet.topicIdFromBundle(bundle)
        viewModel.setRecordingTopicId(topicId)
        val topic = viewModel.allTopics.value.firstOrNull { it.id == topicId }
        recorderBinding.btnMiniRecTopic.text = topic?.icon ?: Icons.UNSORTED
    }

    // ── Topic button → show picker ────────────────────────────────────────
    recorderBinding.btnMiniRecTopic.setOnClickListener {
        TopicPickerBottomSheet.newInstance(
            selectedTopicId = viewModel.recordingTopicId.value,
            requestKey      = TopicPickerBottomSheet.REQUEST_KEY + "_mini_rec"
        ).show(supportFragmentManager, "mini_rec_topic_picker")
    }

    // ── Tap root (not on buttons) → navigate to Record tab ───────────────
    recorderBinding.root.setOnClickListener { navigateTo(PAGE_RECORD) }
}

// ── Mini Recorder — minimize / pill ──────────────────────────────────────────

/**
 * Wires the minimize button on the Mini Recorder and its pill.
 *
 * Also drives:
 *  • mini recorder visibility  (widget hidden when minimized or suppressed)
 *  • pill visibility + content (shown and updated when minimized)
 *  • title text alpha          (dimmed when any pill is overlaying it)
 */
internal fun MainActivity.setupMiniRecorderMinimize() {
    val rec  = binding.miniRecorder
    val pill = binding.titlePills

    // ── Minimize button → set state ───────────────────────────────────────
    rec.btnMiniRecorderMinimize.setOnClickListener {
        viewModel.setRecorderPillMinimized(true)
    }
    rec.btnMiniRecorderMinimize.background =
        solidPillBackground(themeColor(R.attr.colorRecordActive))

    // ── Pill tap ──────────────────────────────────────────────────────────
    pill.pillRecorder.setOnClickListener {
        when {
            // NEVER mode — pill always redirects to the tab
            viewModel.recorderWidgetVisibility.value == RecorderWidgetVisibility.NEVER -> {
                navigateTo(PAGE_RECORD)
            }
            // WHILE_RECORDING mode but not currently recording — nothing to show,
            // so navigate to the tab so the user can start a recording.
            viewModel.recorderWidgetVisibility.value == RecorderWidgetVisibility.WHILE_RECORDING &&
                    viewModel.recordingState.value == RecordingService.State.IDLE -> {
                viewModel.setRecorderHideOverriddenThisVisit(true)
                viewModel.setRecorderPillMinimized(false)
            }
            // Widget is already visible → navigate to tab
            isRecorderWidgetVisible() -> {
                navigateTo(PAGE_RECORD)
            }
            // Widget is hidden (suppressed by tab or minimized) → restore it,
            // overriding the tab-suppress if needed.
            else -> {
                viewModel.setRecorderHideOverriddenThisVisit(true)
                viewModel.setRecorderPillMinimized(false)
            }
        }
    }

    // ── Pill visibility ───────────────────────────────────────────────────
    lifecycleScope.launch {
        val innerFlow = combine(
            viewModel.recordingState,
            viewModel.recorderPillMinimized,
            viewModel.recorderWidgetVisibility,
            viewModel.hideRecorderOnRecordTab
        ) { state, minimized, visibility, hideOnRecordTab ->
            // hasContent uses the reactive visibility param, not a stale .value read
            val hasContent = when (visibility) {
                RecorderWidgetVisibility.NEVER           -> false  // alwaysShow overrides below
                RecorderWidgetVisibility.WHILE_RECORDING -> state != RecordingService.State.IDLE
                RecorderWidgetVisibility.ALWAYS          -> true
            }
            Triple(minimized, hideOnRecordTab, hasContent)
        }

        combine(
            innerFlow,
            viewModel.currentPage,
            viewModel.alwaysShowRecorderPill
        ) { (minimized, hideOnRecordTab, hasContent), page, alwaysShow ->
            val onRecordTab = page == PAGE_RECORD
            val tabVisible  = hasContent && minimized && !(hideOnRecordTab && onRecordTab)
            alwaysShow || tabVisible
        }.collect { pillVisible ->
            pill.pillRecorder.visibility = if (pillVisible) View.VISIBLE else View.GONE
            updateTitleTextAlpha()
        }
    }

    // ── Pill content ──────────────────────────────────────────────────────
    lifecycleScope.launch {
        viewModel.recordingState.collect { state ->
            val (iconRes, label, textColor) = when (state) {
                RecordingService.State.IDLE ->
                    Triple(
                        R.drawable.ic_stop_square,
                        getString(R.string.record_pill_status_idle),
                        themeColor(R.attr.colorTextSecondary)
                    )
                RecordingService.State.RECORDING ->
                    Triple(
                        R.drawable.ic_record_circle,
                        getString(R.string.record_pill_status_recording),
                        themeColor(R.attr.colorRecordActive)
                    )
                RecordingService.State.PAUSED ->
                    Triple(
                        R.drawable.ic_pause,
                        getString(R.string.record_pill_status_paused),
                        themeColor(R.attr.colorRecordPause)
                    )
            }

            pill.ivPillRecorderDot.visibility =
                if (state == RecordingService.State.PAUSED) View.VISIBLE else View.GONE
            pill.ivPillRecorderDot.imageTintList =
                ColorStateList.valueOf(themeColor(R.attr.colorRecordActive))
            pill.ivPillRecorderIcon.setImageResource(iconRes)
            pill.ivPillRecorderIcon.imageTintList = ColorStateList.valueOf(textColor)
            pill.pillRecorderStatus.text = label
            pill.pillRecorderStatus.setTextColor(textColor)

            // Topic icon — shown when a topic is assigned to the active recording
            val topicIcon = viewModel.allTopics.value
                .firstOrNull { it.id == viewModel.recordingTopicId.value }?.icon
            if (topicIcon != null) {
                pill.pillRecorderTopic.text = topicIcon
                pill.pillRecorderTopic.setTextColor(textColor)
                pill.pillRecorderTopic.visibility = View.VISIBLE
            } else {
                pill.pillRecorderTopic.visibility = View.GONE
            }

            // Pill border
            pill.pillRecorder.background = when (state) {
                RecordingService.State.IDLE ->
                    pillBackground(
                        themeColor(R.attr.colorSurfaceBase),
                        themeColor(R.attr.colorPillRecorderIdleStroke)
                    )
                RecordingService.State.RECORDING ->
                    pillBackground(
                        themeColor(R.attr.colorMiniRecorderActive),
                        themeColor(R.attr.colorRecordActive)
                    )
                RecordingService.State.PAUSED ->
                    pillBackground(
                        themeColor(R.attr.colorPillRecorderPausedFill),
                        themeColor(R.attr.colorRecordPause)
                    )
            }
        }
    }

    // ── Widget visibility ─────────────────────────────────────────────────
    lifecycleScope.launch {
        val innerFlow = combine(
            viewModel.recordingState,
            viewModel.recorderWidgetVisibility,
            viewModel.recorderHideOverriddenThisVisit
        ) { state, visibility, overridden ->
            Triple(state, visibility, overridden)
        }
        combine(
            innerFlow,
            viewModel.hideRecorderOnRecordTab,
            viewModel.currentPage,
            viewModel.recorderPillMinimized
        ) { (state, visibility, overridden), hideOnRecordTab, page, minimized ->
            val wantShow = when (visibility) {
                RecorderWidgetVisibility.NEVER           -> false
                RecorderWidgetVisibility.WHILE_RECORDING -> state != RecordingService.State.IDLE
                RecorderWidgetVisibility.ALWAYS          -> true
            }
            val onRecordTab = page == PAGE_RECORD
            val suppressed  = hideOnRecordTab && onRecordTab && !overridden
            wantShow && !suppressed && !minimized
        }.collect { visible ->
            rec.root.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }
}

// ── Visibility query ──────────────────────────────────────────────────────────

/**
 * Returns true if the Mini Recorder widget is currently visible on screen.
 * Used by the pill tap handler to decide between navigate-vs-show behaviours.
 *
 * See [isPlayerWidgetVisible] for the full reasoning — the same principle
 * applies here. The combine flow in [setupMiniRecorderMinimize] is the single
 * source of truth for recorder visibility, and this function reads that
 * decision from the view rather than recomputing it independently.
 */
internal fun MainActivity.isRecorderWidgetVisible(): Boolean =
    binding.miniRecorder.root.visibility == View.VISIBLE