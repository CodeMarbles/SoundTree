package app.soundtree.ui

import android.content.res.ColorStateList
import android.view.View
import androidx.lifecycle.lifecycleScope
import app.soundtree.R
import app.soundtree.ui.MainActivity.Companion.PAGE_LISTEN
import app.soundtree.ui.common.TopicPickerBottomSheet
import app.soundtree.util.Icons
import app.soundtree.util.themeColor
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// MainActivity_MiniPlayer.kt
//
// Extension functions on MainActivity covering the Mini Player widget
// ─────────────────────────────────────────────────────────────────────────────

// ── Mini Player — transport + content observers ───────────────────────────────

/**
 * Wires all interactive controls on the Mini Player bar and starts the
 * observers that keep its content in sync with [MainViewModel].
 *
 * Call order in [MainActivity.onCreate]:
 *   setupMiniPlayer()       ← this function
 *   setupMiniPlayerMinimize()
 */
internal fun MainActivity.setupMiniPlayer() {
    val p = binding.miniPlayer

    // ── Navigate to Listen tab on root tap ────────────────────────────────
    // MiniPlayerTimelineView consumes its own touch events, so this fires
    // only on taps elsewhere in the bar.
    p.root.setOnClickListener { navigateTo(PAGE_LISTEN) }

    // ── Topic picker (long-press title row → move recording to topic) ─────
    supportFragmentManager.setFragmentResultListener(
        TopicPickerBottomSheet.REQUEST_KEY + "_mini_player", this
    ) { _, bundle ->
        val topicId = TopicPickerBottomSheet.topicIdFromBundle(bundle)
        viewModel.nowPlaying.value?.recording?.id?.let { recId ->
            viewModel.moveRecording(recId, topicId)
        }
        val topic = viewModel.allTopics.value.firstOrNull { it.id == topicId }
        binding.miniPlayer.tvMiniTopicIcon.text = topic?.icon ?: Icons.UNSORTED
    }

    // ── Title row tap → topic picker ──────────────────────────────────────
    p.miniTitleRow.setOnClickListener {
        TopicPickerBottomSheet.newInstance(
            selectedTopicId = viewModel.nowPlaying.value?.recording?.topicId,
            requestKey      = TopicPickerBottomSheet.REQUEST_KEY + "_mini_player"
        ).show(supportFragmentManager, "mini_topic_picker")
    }

    // ── Transport controls ────────────────────────────────────────────────
    p.btnMiniPlayPause.setOnClickListener   { viewModel.togglePlayPause() }
    p.btnMiniSkipBack.setOnClickListener    { viewModel.skipBack() }
    p.btnMiniSkipForward.setOnClickListener { viewModel.skipForward() }

    // ── Mark cluster buttons ──────────────────────────────────────────────
    p.btnMiniAddMark.setOnClickListener          { viewModel.addMark() }
    p.btnMiniJumpPrev.setOnClickListener         { viewModel.jumpMark(forward = false, select = true) }
    p.btnMiniJumpNext.setOnClickListener         { viewModel.jumpMark(forward = true,  select = true) }
    p.btnMiniMarkNudgeBack.setOnClickListener    { viewModel.nudgePlaybackMarkBack() }
    p.btnMiniMarkNudgeForward.setOnClickListener { viewModel.nudgePlaybackMarkForward() }
    p.btnMiniMarkCommit.setOnClickListener       { viewModel.commitPlaybackMarkNudge() }
    p.btnMiniDeleteMark.setOnClickListener       { viewModel.deleteSelectedMark() }

    // ── Timeline: seek on empty-area tap ──────────────────────────────────
    p.miniPlayerTimeline.onSeekRequested = seek@{ fraction ->
        val dur = viewModel.nowPlaying.value?.durationMs ?: return@seek
        viewModel.seekTo((fraction * dur).toLong())
    }

    // ── Timeline: mark tap → select mark + seek to it ────────────────────
    p.miniPlayerTimeline.onMarkTapped = tap@{ markId ->
        val mark = viewModel.marks.value.firstOrNull { it.id == markId }
            ?: return@tap
        viewModel.selectMark(markId)
        viewModel.seekToMark(mark.positionMs)
        // Selecting via tap unlocks nudging (mirrors jump-and-select behaviour)
        viewModel.unlockPlaybackMarkNudge()
    }

    // ── Observe nowPlaying → update title, time, and timeline progress ────
    lifecycleScope.launch {
        viewModel.nowPlaying.collect { state ->
            if (state != null) {
                p.tvMiniTitle.text = state.recording.title

                // Playing label — shows current state, turns yellow when paused
                if (state.isPlaying) {
                    p.tvMiniPlayingLabel.text = getString(R.string.mini_player_label_now_playing)
                    p.tvMiniPlayingLabel.setTextColor(themeColor(R.attr.colorTextSecondary))
                } else {
                    p.tvMiniPlayingLabel.text = getString(R.string.mini_player_label_paused)
                    p.tvMiniPlayingLabel.setTextColor(themeColor(R.attr.colorRecordPause))
                }

                // Populate topic icon
                val topic = viewModel.allTopics.value
                    .firstOrNull { it.id == state.recording.topicId }
                p.tvMiniTopicIcon.text = topic?.icon ?: Icons.UNSORTED

                p.tvMiniTime.text = "${formatMs(state.positionMs)} / ${formatMs(state.durationMs)}"
                p.btnMiniPlayPause.setImageResource(
                    if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
                val fraction = if (state.durationMs > 0)
                    state.positionMs.toFloat() / state.durationMs.toFloat() else 0f
                p.miniPlayerTimeline.setProgress(fraction)
            }
        }
    }

    // ── Observe marks + selectedMarkId → update timeline dots ─────────────
    lifecycleScope.launch {
        combine(
            viewModel.marks,
            viewModel.selectedMarkId
        ) { marks, selectedId -> Pair(marks, selectedId) }
            .collect { (marks, selectedId) ->
                val dur = viewModel.nowPlaying.value?.durationMs ?: return@collect
                if (dur <= 0) return@collect
                val fracs     = marks.map { it.positionMs.toFloat() / dur }
                val ids       = marks.map { it.id }
                p.miniPlayerTimeline.setMarks(fracs, ids)
                val selectedMs = marks.firstOrNull { it.id == selectedId }?.positionMs
                p.miniPlayerTimeline.setSelectedMark(selectedId, selectedMs)
            }
    }

    // ── Observe selectedMarkId + nudge lock → enable/disable cluster ───
    lifecycleScope.launch {
        combine(
            viewModel.selectedMarkId,
            viewModel.playbackMarkNudgeLocked
        ) { selectedId, locked -> Pair(selectedId, locked) }
            .collect { (selectedId, locked) ->
                val hasSelection = selectedId != null
                val canNudge     = hasSelection && !locked

                val tealColor = themeColor(R.attr.colorMarkSelected)
                val greyColor = themeColor(R.attr.colorTextSecondary)

                p.btnMiniDeleteMark.isEnabled = hasSelection
                p.btnMiniDeleteMark.alpha = if (hasSelection) 1f else 0.3f
                p.btnMiniDeleteMark.imageTintList =
                    ColorStateList.valueOf(if (hasSelection) tealColor else greyColor)

                for (v in listOf(
                    p.btnMiniMarkNudgeBack,
                    p.btnMiniMarkNudgeForward,
                    p.btnMiniMarkCommit
                )) {
                    v.isEnabled = canNudge
                    v.alpha = if (canNudge) 1f else 0.3f
                    v.imageTintList = ColorStateList.valueOf(if (canNudge) tealColor else greyColor)
                }
            }
    }

    // ── Observe mark jump availability → tint jump buttons ───────────────
    lifecycleScope.launch {
        combine(
            viewModel.hasMarkBehind,
            viewModel.hasMarkAhead
        ) { behind, ahead -> behind to ahead }
            .collect { (hasBehind, hasAhead) ->
                val prevColor = themeColor(if (hasBehind) R.attr.colorMarkDefault else R.attr.colorTextPrimary)
                p.btnMiniJumpPrev.imageTintList = ColorStateList.valueOf(prevColor)

                p.btnMiniJumpNext.isEnabled     = hasAhead
                p.btnMiniJumpNext.alpha         = if (hasAhead) 1f else 0.3f
                val nextColor = themeColor(if (hasAhead) R.attr.colorMarkDefault else R.attr.colorTextPrimary)
                p.btnMiniJumpNext.imageTintList = ColorStateList.valueOf(nextColor)
            }
    }

    // ── Idle state display ────────────────────────────────────────────────
    // When the widget is visible but nothing is loaded (ALWAYS mode, no content),
    // swap the title row to show a grey icon + "NO SELECTION" status.
    lifecycleScope.launch {
        combine(
            viewModel.nowPlaying,
            viewModel.playerWidgetVisibility
        ) { state, visibility ->
            state == null && visibility == PlayerWidgetVisibility.ALWAYS
        }.collect { idle ->
            if (idle) {
                p.tvMiniPlayingLabel.text = getString(R.string.mini_player_label_no_selection)
                p.tvMiniTopicIcon.visibility = View.GONE
                p.tvMiniTitle.text = ""
                // Grey out the transport row — nothing to control
                p.btnMiniPlayPause.isEnabled  = false
                p.btnMiniPlayPause.alpha      = 0.3f
                p.btnMiniSkipBack.isEnabled   = false
                p.btnMiniSkipBack.alpha       = 0.3f
                p.btnMiniSkipForward.isEnabled = false
                p.btnMiniSkipForward.alpha    = 0.3f
                p.miniPlayerTimeline.visibility = View.INVISIBLE
            } else {
                p.tvMiniPlayingLabel.text = getString(R.string.mini_player_label_now_playing)
                p.tvMiniTopicIcon.visibility  = View.VISIBLE
                p.btnMiniPlayPause.isEnabled  = true
                p.btnMiniPlayPause.alpha      = 1f
                p.btnMiniSkipBack.isEnabled   = true
                p.btnMiniSkipBack.alpha       = 1f
                p.btnMiniSkipForward.isEnabled = true
                p.btnMiniSkipForward.alpha    = 1f
                p.miniPlayerTimeline.visibility = View.VISIBLE
            }
        }
    }
}

// ── Mini Player — minimize / pill ─────────────────────────────────────────────

/**
 * Wires the minimize button on the Mini Player and the corresponding pill
 * in the title bar — click to minimize, tap pill to restore.
 *
 * Also drives:
 *  • mini player visibility  (widget hidden when minimized or suppressed)
 *  • pill visibility + content (shown and updated when minimized)
 *  • title text alpha         (dimmed when any pill is overlaying it)
 */
internal fun MainActivity.setupMiniPlayerMinimize() {
    val p    = binding.miniPlayer
    val pill = binding.titlePills

    // ── Close button → stop & clear everything ────────────────────────────
    p.btnMiniPlayerClose.setOnClickListener {
        viewModel.stopAndClear()
        // playerPillMinimized is reset inside stopAndClear(); no extra call needed.
    }

    // Close button tint — dim secondary colour so it reads as less prominent
    // than the minimize button.
    p.btnMiniPlayerClose.imageTintList =
        ColorStateList.valueOf(themeColor(R.attr.colorTextSecondary))

    // ── Minimize button → set state ───────────────────────────────────────
    p.btnMiniPlayerMinimize.setOnClickListener {
        viewModel.setPlayerPillMinimized(true)
    }
    p.btnMiniPlayerMinimize.background =
        solidPillBackground(themeColor(R.attr.colorAccent))

    // Player pill background — set once
    pill.pillPlayer.background = pillBackground(
        themeColor(R.attr.colorPillPlayerFill),
        themeColor(R.attr.colorPillPlayerStroke)
    )

    // ── Pill tap ──────────────────────────────────────────────────────────
    //  • NEVER mode  → navigate to Listen tab (pill acts as a shortcut)
    //  • Other modes → restore widget; set override so it survives tab suppression
    pill.pillPlayer.setOnClickListener {
        when {
            // NEVER mode — pill is always a shortcut to the tab
            viewModel.playerWidgetVisibility.value == PlayerWidgetVisibility.NEVER -> {
                navigateTo(PAGE_LISTEN)
            }
            // Widget is currently visible → navigate to tab
            isPlayerWidgetVisible() -> {
                navigateTo(PAGE_LISTEN)
            }
            // Widget is hidden (minimized, suppressed, or no content yet) → show it
            else -> {
                viewModel.setPlayerHideOverriddenThisVisit(true)
                viewModel.setPlayerPillMinimized(false)
            }
        }
    }

    // ── Pill visibility ───────────────────────────────────────────────────
    //
    // pillVisible = alwaysShow
    //            || ( hasContent && minimized && !(hideOnListenTab && onListenTab) )
    //
    // alwaysShow gives full override — pill visible even when suppressed by tab.
    lifecycleScope.launch {
        combine(
            viewModel.nowPlaying,
            viewModel.playerPillMinimized,
            viewModel.hidePlayerOnListenTab,
            viewModel.currentPage,
            viewModel.alwaysShowPlayerPill
        ) { state, minimized, hideOnListenTab, page, alwaysShow ->
            val hasContent  = state != null
            val onListenTab = page == PAGE_LISTEN
            val tabVisible  = hasContent && minimized && !(hideOnListenTab && onListenTab)
            alwaysShow || tabVisible
        }.collect { pillVisible ->
            pill.pillPlayer.visibility = if (pillVisible) View.VISIBLE else View.GONE
            updateTitleTextAlpha()
        }
    }

    // ── Pill content ──────────────────────────────────────────────────────
    // Separate observer so pill content always stays fresh regardless of
    // visibility decisions above.
    lifecycleScope.launch {
        viewModel.nowPlaying.collect { state ->
            if (state != null) {
                if (state.isPlaying) {
                    pill.ivPillPlayerPlay.setImageResource(R.drawable.ic_play)
                    pill.ivPillPlayerPlay.imageTintList =
                        ColorStateList.valueOf(themeColor(R.attr.colorAccent))
                } else {
                    pill.ivPillPlayerPlay.setImageResource(R.drawable.ic_pause)
                    pill.ivPillPlayerPlay.imageTintList =
                        ColorStateList.valueOf(themeColor(R.attr.colorRecordPause))
                }
                val topic = viewModel.allTopics.value
                    .firstOrNull { it.id == state.recording.topicId }
                val icon = topic?.icon ?: Icons.UNSORTED
                pill.pillPlayerTopic.text    = icon
                pill.pillPlayerTopic.visibility = View.VISIBLE
                pill.pillPlayerFilename.text = state.recording.title
                pill.pillPlayerFilename.isSelected = true
            } else {
                // Idle pill state — grey icon, no filename
                pill.ivPillPlayerPlay.setImageResource(R.drawable.ic_stop_square)
                pill.ivPillPlayerPlay.imageTintList =
                    ColorStateList.valueOf(themeColor(R.attr.colorTextSecondary))
                pill.pillPlayerTopic.visibility = View.GONE
                pill.pillPlayerFilename.text = getString(R.string.mini_player_label_no_selection)
            }
        }
    }

    // ── Widget visibility ─────────────────────────────────────────────────
    //
    // shouldShow = (visibility==ALWAYS) || (hasContent && visibility!=NEVER)
    // suppressed = hideOnListenTab && onListenTab && !overriddenThisVisit
    // widgetVisible = shouldShow && !suppressed && !minimized
    lifecycleScope.launch {
        // Combine nowPlaying + playerWidgetVisibility + override into a
        // single inner flow to stay within Kotlin's 5-arg combine limit.
        val innerFlow = combine(
            viewModel.nowPlaying,
            viewModel.playerWidgetVisibility,
            viewModel.playerHideOverriddenThisVisit
        ) { state, visibility, overridden ->
            Triple(state, visibility, overridden)
        }
        combine(
            innerFlow,
            viewModel.hidePlayerOnListenTab,
            viewModel.currentPage,
            viewModel.playerPillMinimized
        ) { (state, visibility, overridden), hideOnListenTab, page, minimized ->
            val hasContent  = state != null
            val shouldShow  = when (visibility) {
                PlayerWidgetVisibility.NEVER         -> false
                PlayerWidgetVisibility.WHILE_PLAYING -> hasContent || overridden
                PlayerWidgetVisibility.ALWAYS        -> true
            }
            val onListenTab = page == PAGE_LISTEN
            val suppressed  = hideOnListenTab && onListenTab && !overridden
            shouldShow && !suppressed && !minimized
        }.collect { visible ->
            p.root.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }
}

// ── Visibility query ──────────────────────────────────────────────────────────

/**
 * Returns true if the Mini Player widget is currently visible on screen.
 * Used by the pill tap handler to decide between navigate-vs-show behaviours.
 *
 * Intentionally delegates to the view state rather than recomputing visibility
 * logic here. The widget's combine flow (in [setupMiniPlayerMinimize]) is the
 * single source of truth for player visibility — it folds together nowPlaying,
 * playerWidgetVisibility, playerHideOverriddenThisVisit, hidePlayerOnListenTab,
 * currentPage, and playerPillMinimized into one authoritative answer that drives
 * p.root.visibility.
 *
 * Duplicating that logic here would create two code paths that could silently
 * diverge. Reading the view state instead means there is only one logic pathway.
 *
 * This is safe because .collect { } updates the view synchronously before any
 * user interaction (i.e. a pill tap) can fire, so the value is always current.
 */
internal fun MainActivity.isPlayerWidgetVisible(): Boolean =
    binding.miniPlayer.root.visibility == View.VISIBLE