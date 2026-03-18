package com.treecast.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.treecast.app.R
import com.treecast.app.databinding.ActivityMainBinding
import com.treecast.app.service.RecordingService
import com.treecast.app.ui.common.TopicPickerBottomSheet
import com.treecast.app.ui.library.LibraryFragment
import com.treecast.app.ui.listen.ListenFragment
import com.treecast.app.ui.record.RecordFragment
import com.treecast.app.ui.settings.SettingsFragment
import com.treecast.app.ui.workspace.WorkspaceFragment
import com.treecast.app.util.Icons
import com.treecast.app.util.StorageEjectReceiver
import com.treecast.app.util.themeColor
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QUICK_RECORD = "quick_record"

        // Extras set by RecordingService when a save is triggered from the
        // notification action button. MainActivity uses these in onNewIntent
        // (app already running) and onCreate (cold start after notification
        // save, unlikely but handled) to navigate to the saved recording.
        const val EXTRA_SAVED_RECORDING_ID = "saved_recording_id"
        const val EXTRA_SAVED_TOPIC_ID     = "saved_topic_id"

        // TODO: On app startup, scan the recordings directory for .m4a files
        //  that have no corresponding row in the recordings table (orphaned by
        //  a mid-save process death). For each orphan found, offer the user a
        //  prompt to import it (auto-generating a title from the file timestamp)
        //  or delete it. This guards against the rare case where the process is
        //  killed between RecordingService finalising the audio file and the
        //  Room insert completing. See SplashActivity for the startup hook point.

        const val PAGE_SETTINGS = 0
        const val PAGE_RECORD   = 1
        const val PAGE_LIBRARY  = 2
        const val PAGE_LISTEN   = 3
        const val PAGE_WORKSPACE = 4
    }

    private lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()

    private val pageTitles = mapOf(
        PAGE_SETTINGS to "Settings",
        PAGE_RECORD   to "Record",
        PAGE_LIBRARY  to "Library",
        PAGE_LISTEN   to "Listen",
        PAGE_WORKSPACE to "Workspace"
    )

    private var storageEjectReceiver: StorageEjectReceiver? = null

    // ── Back-stack navigation ─────────────────────────────────────────
    //
    // navHistory records every page the user visits, in order.
    // Back presses walk it in reverse; when only one entry remains
    // (or it's empty) we fall through to finish().
    //
    // isNavigatingBack suppresses history recording during the
    // programmatic setCurrentItem() call that back navigation triggers.
    private val navHistory = ArrayDeque<Int>()
    private var isNavigatingBack = false

    // Direct reference to the Library fragment so we can ask it to
    // handle sub-page back presses (Uncategorized → Tree View) before
    // we pop our own back stack.
    private lateinit var libraryFragment: LibraryFragment

    // Direct reference to the Record fragment so we can
    private lateinit var recordFragment: RecordFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.onAppOpen()

        setupViewPager()
        observeTopTitle()
        setupBottomNav()

        setupMiniPlayer()
        setupMiniPlayerMinimize()
        setupMiniRecorder()
        setupMiniRecorderMinimize()

        setupTabChangeOverrideReset()
        setupBackNavigation()

        observeLockState()

        applyLayoutOrder()
        observeLayoutOrder()

        when {
            // A save completed in the notification while the app was away —
            // navigate directly to the saved recording.
            intent.hasExtra(EXTRA_SAVED_RECORDING_ID) -> handleNotificationSaveIntent(intent)

            // Normal quick-record launch from SplashActivity.
            intent.getBooleanExtra(EXTRA_QUICK_RECORD, false) ->
                binding.viewPager.currentItem = PAGE_RECORD
        }
    }

    override fun onStart() {
        super.onStart()
        storageEjectReceiver = StorageEjectReceiver {
            // Called on the main thread — safe to update ViewModel directly.
            viewModel.refreshStorageVolumes()
        }.also { receiver ->
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                // Storage broadcasts require a data scheme to be delivered.
                addDataScheme("file")
            }
            registerReceiver(receiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        storageEjectReceiver?.let { unregisterReceiver(it) }
        storageEjectReceiver = null
    }

    /**
     * Called when the app is already running and a new intent arrives — the
     * primary path for notification-save navigation since the back stack is
     * almost always alive when the user is actively recording.
     *
     * [FLAG_ACTIVITY_SINGLE_TOP] on the intent from [RecordingService] ensures
     * this is called rather than a second instance of MainActivity being created.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.hasExtra(EXTRA_SAVED_RECORDING_ID) == true) {
            handleNotificationSaveIntent(intent)
        }
    }

    /**
     * Navigates to the Library and selects the recording identified by the
     * extras in [intent]. Mirrors the post-save navigation that
     * [RecordFragment.stopAndSave] performs when the app is in the foreground.
     *
     * Only called when [EXTRA_SAVED_RECORDING_ID] is present; the topic extra
     * is optional (null means the recording landed in Inbox).
     */
    private fun handleNotificationSaveIntent(intent: Intent) {
        val recordingId = intent.getLongExtra(EXTRA_SAVED_RECORDING_ID, -1L)
        if (recordingId == -1L) return

        val topicId = if (intent.hasExtra(EXTRA_SAVED_TOPIC_ID))
            intent.getLongExtra(EXTRA_SAVED_TOPIC_ID, -1L).takeIf { it != -1L }
        else null

        viewModel.selectRecording(recordingId)
        navigateToLibraryForRecording(topicId)
    }

    // ── Layout order ──────────────────────────────────────────────────

    /**
     * Rebuilds the root vertical stack from the saved element order.
     *
     * Called once in onCreate() (synchronously, before first draw) and again
     * whenever the user taps Apply in Settings.
     *
     * Each LayoutElement maps to a View already inflated from activity_main.xml:
     *   TITLE_BAR     → binding.titleBarContainer  (topBar + its divider, wrapped)
     *   CONTENT       → binding.viewPager
     *   MINI_PLAYER   → binding.miniPlayer.root
     *   MINI_RECORDER → binding.miniRecorder.root
     *   NAV           → binding.bottomNav
     *
     * The CONTENT view always gets weight=1 / height=0dp so it fills remaining
     * space regardless of position.  All other views keep their fixed heights.
     */
    private fun applyLayoutOrder() {
        val order         = viewModel.layoutOrder.value
        val showTitle     = viewModel.showTitleBar.value
        val anyPillActive = viewModel.playerPillMinimized.value || viewModel.recorderPillMinimized.value
        val pillOnlyMode  = !showTitle && anyPillActive

        val viewMap = mapOf(
            LayoutElement.TITLE_BAR    to binding.titleBarContainer,
            LayoutElement.CONTENT      to binding.viewPager,
            LayoutElement.MINI_PLAYER  to binding.miniPlayer.root,
            LayoutElement.MINI_RECORDER to binding.miniRecorder.root,
            LayoutElement.NAV          to binding.bottomNav
        )

        binding.rootStack.removeAllViews()

        val dp = resources.displayMetrics.density

        for (element in order) {
            val view = viewMap[element] ?: continue
            if (element == LayoutElement.TITLE_BAR && !showTitle && !anyPillActive) continue
            if (element == LayoutElement.CONTENT) {
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
                binding.rootStack.addView(view, lp)
            } else {
                val heightPx = when (element) {
                    LayoutElement.MINI_PLAYER   -> (108 * dp).toInt()
                    LayoutElement.MINI_RECORDER -> (108 * dp).toInt()
                    LayoutElement.NAV           -> (64 * dp).toInt()
                    LayoutElement.TITLE_BAR -> if (pillOnlyMode) LinearLayout.LayoutParams.WRAP_CONTENT
                    else               (53 * dp).toInt()
                    else -> android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                }
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, heightPx
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
     * Observes [MainViewModel.layoutOrder] and [MainViewModel.showTitleBar]
     * together, re-applying the layout whenever the user commits a change.
     *
     * Uses [combine] so a single Apply that updates both simultaneously only
     * triggers one re-layout pass.
     */
    private fun observeLayoutOrder() {
        lifecycleScope.launch {
            combine(
                viewModel.layoutOrder,
                viewModel.showTitleBar,
                viewModel.playerPillMinimized,
                viewModel.recorderPillMinimized
            ) { order, showTitle, _, _ ->
                order to showTitle
            }.collect {
                applyLayoutOrder()
            }
        }
    }

    /**
     * Positions the mini-player accent line so it always borders the edge
     * that faces the Content view — top edge if Content is below, bottom
     * edge if Content is above.
     */
    private fun updateMiniPlayerAccentLine(order: List<LayoutElement>) {
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
            // Miniplayer is ABOVE content → accent should be on the BOTTOM edge
            // (the edge facing the content below it).
            cs.clear(accentLine.id, ConstraintSet.TOP)
            cs.connect(accentLine.id, ConstraintSet.BOTTOM,
                ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            // Content fills the space above the accent line.
            cs.connect(miniContent.id, ConstraintSet.TOP,
                ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            cs.connect(miniContent.id, ConstraintSet.BOTTOM,
                accentLine.id, ConstraintSet.TOP)
        } else {
            // Miniplayer is BELOW content → accent should be on the TOP edge.
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
     * Positions the red accent line on the edge facing the Content view.
     */
    private fun updateMiniRecorderAccentLine(order: List<LayoutElement>) {
        val recIdx     = order.indexOf(LayoutElement.MINI_RECORDER)
        val contentIdx = order.indexOf(LayoutElement.CONTENT)
        if (recIdx == -1 || contentIdx == -1) return

        val contentIsBelow = contentIdx > recIdx

        val recRoot     = binding.miniRecorder.root as? ConstraintLayout ?: return
        val accentLine  = binding.miniRecorder.recAccentLine
        val recContent  = binding.miniRecorder.recContent

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
     * (the destination of the pill)
     *
     * Chevron points UP  when the title bar is ABOVE the widget.
     * Chevron points DOWN when the title bar is BELOW the widget.
     */
    private fun updatePillChevronDirections(order: List<LayoutElement>) {
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

    // ── ViewPager ─────────────────────────────────────────────────────
    private fun setupViewPager() {
        libraryFragment = LibraryFragment()
        recordFragment = RecordFragment()

        val adapter = MainPagerAdapter(this).apply {
            addFragment(SettingsFragment(), "Settings")
            addFragment(recordFragment,   "Record")
            addFragment(libraryFragment,    "Library")
            addFragment(ListenFragment(),   "Listen")
            addFragment(WorkspaceFragment(),   "Workspace")
        }
        binding.viewPager.adapter = adapter
        binding.viewPager.offscreenPageLimit = 5
        binding.viewPager.setCurrentItem(PAGE_RECORD, false)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val recording = viewModel.recordingState.value != RecordingService.State.IDLE
                updateBottomNavSelection(position, recording)
                if (position != PAGE_LIBRARY) {
                    viewModel.setTopTitle(pageTitles[position] ?: "")
                } else {
                    // LibraryFragment drives the title, but its inner page-change
                    // callback won't fire on a swipe-in from another tab — nudge it.
                    libraryFragment.refreshTopTitle()
                }

                // ── History recording ──────────────────────────────────
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

    // ── Back navigation ───────────────────────────────────────────────
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Give the Library fragment first crack at consuming the press
                // so it can pop its own sub-page stack (e.g. Inbox → Tree View).
                if (binding.viewPager.currentItem == PAGE_LIBRARY &&
                    libraryFragment.handleBackPress()) return

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

    // ── Bottom nav ────────────────────────────────────────────────────
    private fun setupBottomNav() {
        binding.navSettings.setOnClickListener { navigateTo(PAGE_SETTINGS) }
        binding.navRecord.setOnClickListener   { navigateTo(PAGE_RECORD)   }
        binding.navLibrary.setOnClickListener  { navigateTo(PAGE_LIBRARY)  }
        binding.navListen.setOnClickListener   { navigateTo(PAGE_LISTEN)   }
        binding.navWorkspace.setOnClickListener { navigateTo(PAGE_WORKSPACE) }

        val recording = viewModel.recordingState.value != RecordingService.State.IDLE
        updateBottomNavSelection(viewModel.currentPage.value, recording)

        // Re-draw nav whenever recording state changes so the Record
        // pill border appears/disappears independently of tab selection.
        lifecycleScope.launch {
            viewModel.recordingState.collect { state ->
                val recording = state != RecordingService.State.IDLE
                updateBottomNavSelection(viewModel.currentPage.value, recording)
            }
        }

        lifecycleScope.launch {
            viewModel.futureMode.collect { enabled ->
                binding.navWorkspace.visibility = if (enabled) View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateBottomNavSelection(
        position:  Int,
        isRecording: Boolean = false
    ) {
        val onActive   = themeColor(R.attr.colorTextPrimary)
        val dim        = themeColor(R.attr.colorTextSecondary)
        val radius     = 20f * resources.displayMetrics.density
        val strokePx   = (2.5f * resources.displayMetrics.density).toInt()
        val recordRed  = themeColor(R.attr.colorRecordActive)

        val overshoot  = android.view.animation.OvershootInterpolator(1.8f)
        val decelerate = android.view.animation.DecelerateInterpolator()

        data class Tab(
            val pill:   android.widget.LinearLayout,
            val icon:   android.widget.ImageView,
            val label:  android.widget.TextView,
            val bgAttr: Int,
            val page:   Int
        )

        listOf(
            Tab(binding.navSettingsPill, binding.ivSettingsIcon, binding.tvSettingsLabel, R.attr.colorNavBgSettings, PAGE_SETTINGS),
            Tab(binding.navRecordPill,   binding.ivRecordIcon,   binding.tvRecordLabel,   R.attr.colorNavBgRecord,   PAGE_RECORD),
            Tab(binding.navLibraryPill,  binding.ivLibraryIcon,  binding.tvLibraryLabel,  R.attr.colorNavBgLibrary,  PAGE_LIBRARY),
            Tab(binding.navListenPill,   binding.ivListenIcon,   binding.tvListenLabel,   R.attr.colorNavBgListen,   PAGE_LISTEN),
            Tab(binding.navWorkspacePill, binding.ivWorkspaceIcon, binding.tvWorkspaceLabel, R.attr.colorNavBgWorkspace, PAGE_WORKSPACE),
        ).forEach { tab ->
            val active      = position == tab.page
            val showBorder  = isRecording && tab.page == PAGE_RECORD
            val targetScale = if (active) 1.13f else 0.93f
            val interp      = if (active) overshoot else decelerate

            // ── Pill background ───────────────────────────────────────────
            tab.pill.background = when {
                active || showBorder -> android.graphics.drawable.GradientDrawable().apply {
                    shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = radius
                    setColor(if (active) themeColor(tab.bgAttr) else android.graphics.Color.TRANSPARENT)
                    if (showBorder) setStroke(strokePx, recordRed)
                }
                else -> null
            }

            // ── Scale animation ───────────────────────────────────────────
            tab.pill.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(200)
                .setInterpolator(interp)
                .start()

            // ── Icon + label tint ─────────────────────────────────────────
            tab.icon.setColorFilter(if (active) onActive else dim)
            tab.icon.alpha  = if (active) 1f else 0.5f
            tab.label.setTextColor(if (active) onActive else dim)
        }
    }

    // ── Public navigation helpers ─────────────────────────────────────

    fun navigateTo(page: Int) {
        binding.viewPager.currentItem = page
    }

    /**
     * Called from RecordFragment after a save completes.
     * Switches to the Library tab and tells LibraryFragment which
     * sub-page to show (Topics if the recording had a topic, Inbox otherwise).
     */
    fun navigateToLibraryForRecording(topicId: Long?) {
        navigateTo(PAGE_LIBRARY)
        libraryFragment.jumpToSubPageForRecording(topicId)
    }

    fun setTopTitle(title: String) {
        viewModel.setTopTitle(title)
    }

    // ── Mini Player ───────────────────────────────────────────────────
    private fun setupMiniPlayer() {
        val p = binding.miniPlayer   // shorthand for binding.miniPlayer

        // ── Navigate to Listen tab on root tap — but NOT when tapping the timeline ──
        p.root.setOnClickListener { navigateTo(PAGE_LISTEN) }
        // (MiniPlayerTimelineView consumes its own touch events, so this fires
        //  only on taps elsewhere in the bar.)

        supportFragmentManager.setFragmentResultListener(
            TopicPickerBottomSheet.REQUEST_KEY + "_mini_player", this
        ) { _, bundle ->
            val topicId = TopicPickerBottomSheet.topicIdFromBundle(bundle)
            viewModel.nowPlaying.value?.recording?.id?.let { recId ->
                viewModel.moveRecording(recId, topicId)
            }
            val topic = viewModel.allTopics.value.firstOrNull { it.id == topicId }
            binding.miniPlayer.tvMiniTopicIcon.text = topic?.icon ?: Icons.INBOX
        }

        // ── Title row ─────────────────────────────────────────────────────
        p.miniTitleRow.setOnClickListener {
            TopicPickerBottomSheet.newInstance(
                selectedTopicId = viewModel.nowPlaying.value?.recording?.topicId,
                requestKey      = TopicPickerBottomSheet.REQUEST_KEY + "_mini_player"
            ).show(supportFragmentManager, "mini_topic_picker")
        }

        // ── Transport controls ────────────────────────────────────────────
        p.btnMiniPlayPause.setOnClickListener  { viewModel.togglePlayPause() }
        p.btnMiniSkipBack.setOnClickListener   { viewModel.skipBack() }
        p.btnMiniSkipForward.setOnClickListener{ viewModel.skipForward() }

        // ── Mark cluster buttons ──────────────────────────────────────────
        p.btnMiniAddMark.setOnClickListener      { viewModel.addMark() }
        p.btnMiniJumpPrev.setOnClickListener     { viewModel.jumpAndSelectPrevMark() }
        p.btnMiniJumpNext.setOnClickListener     { viewModel.jumpAndSelectNextMark() }
        p.btnMiniMarkNudgeBack.setOnClickListener    { viewModel.nudgePlaybackMarkBack() }
        p.btnMiniMarkNudgeForward.setOnClickListener { viewModel.nudgePlaybackMarkForward() }
        p.btnMiniMarkCommit.setOnClickListener       { viewModel.commitPlaybackMarkNudge() }
        p.btnMiniDeleteMark.setOnClickListener       { viewModel.deleteSelectedMark() }

        // ── Timeline: seek on empty-area tap ─────────────────────────────
        p.miniPlayerTimeline.onSeekRequested = seek@{ fraction ->
            val dur = viewModel.nowPlaying.value?.durationMs ?: return@seek
            viewModel.seekTo((fraction * dur).toLong())
        }

        // ── Timeline: mark tap → select mark + seek to it ────────────────
        p.miniPlayerTimeline.onMarkTapped = tap@{ markId ->
            val mark = viewModel.marks.value.firstOrNull { it.id == markId }
                ?: return@tap
            viewModel.selectMark(markId)
            viewModel.seekTo(mark.positionMs)
            // Selecting via tap unlocks nudging (mirrors jump-and-select behaviour)
            viewModel.unlockPlaybackMarkNudge()
        }

        // ── Observe nowPlaying → update title, time, and timeline progress ──
        lifecycleScope.launch {
            viewModel.nowPlaying.collect { state ->
                // UI content updates (unchanged)
                if (state != null) {
                    p.tvMiniTitle.text = state.recording.title

                    // Playing label — reports current state, turns yellow when paused
                    if (state.isPlaying) {
                        p.tvMiniPlayingLabel.text = "NOW PLAYING:"
                        p.tvMiniPlayingLabel.setTextColor(themeColor(R.attr.colorTextSecondary))
                    } else {
                        p.tvMiniPlayingLabel.text = "PAUSED:"
                        p.tvMiniPlayingLabel.setTextColor(themeColor(R.attr.colorRecordPause))
                    }

                    // Populate topic icon
                    val topic = viewModel.allTopics.value
                        .firstOrNull { it.id == state.recording.topicId }
                    p.tvMiniTopicIcon.text = topic?.icon ?: Icons.INBOX

                    p.tvMiniTime.text  = "${formatMs(state.positionMs)} / ${formatMs(state.durationMs)}"
                    p.btnMiniPlayPause.setImageResource(
                        if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    val fraction = if (state.durationMs > 0)
                        state.positionMs.toFloat() / state.durationMs.toFloat() else 0f
                    p.miniPlayerTimeline.setProgress(fraction)
                }
            }
        }

        // ── Observe marks + selectedMarkId → update timeline dots ─────────
        lifecycleScope.launch {
            combine(
                viewModel.marks,
                viewModel.selectedMarkId
            ) { marks, selectedId -> Pair(marks, selectedId) }
                .collect { (marks, selectedId) ->
                    val dur = viewModel.nowPlaying.value?.durationMs ?: 0L
                    val fracs = if (dur > 0) marks.map { it.positionMs.toFloat() / dur } else emptyList()
                    val ids   = marks.map { it.id }
                    p.miniPlayerTimeline.setMarks(fracs, ids)

                    val selectedMark = marks.firstOrNull { it.id == selectedId }
                    p.miniPlayerTimeline.setSelectedMark(selectedId, selectedMark?.positionMs)
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
    }

    // ── Mini Recorder ───────────────────────────────────────────────────
    private fun setupMiniRecorder() {
        val recorderBinding = binding.miniRecorder
        recorderBinding.miniRecTimeline.showLastMarkTimestamp = true

        // ── Background color: 3-state ──────────────────────────────────────
        val idleColor      = themeColor(R.attr.colorMiniRecorderIdle)
        val recordingColor = themeColor(R.attr.colorMiniRecorderActive)   // dark/light red
        val pausedColor = themeColor(R.attr.colorMiniRecorderPaused)      // dark/light yellow

        var lastBgColor = idleColor

        lifecycleScope.launch {
            viewModel.recordingState.collect { state ->
                val targetColor = when (state) {
                    RecordingService.State.IDLE      -> idleColor
                    RecordingService.State.RECORDING -> recordingColor
                    RecordingService.State.PAUSED    -> pausedColor
                }
                android.animation.ValueAnimator.ofArgb(lastBgColor, targetColor).apply {
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

        // ── State label: icon + text + color driven by recording state ───────────
        lifecycleScope.launch {
            viewModel.recordingState.collect { state ->
                val (iconRes, label, color) = when (state) {
                    RecordingService.State.RECORDING ->
                        Triple(R.drawable.ic_record_circle, "RECORDING", themeColor(R.attr.colorRecordActive))
                    RecordingService.State.PAUSED ->
                        Triple(R.drawable.ic_pause, "PAUSED", themeColor(R.attr.colorRecordPause))
                    RecordingService.State.IDLE ->
                        Triple(R.drawable.ic_stop_square, "READY", themeColor(R.attr.colorTextSecondary))
                }
                recorderBinding.ivMiniRecStateIcon.setImageResource(iconRes)
                recorderBinding.ivMiniRecStateIcon.imageTintList =
                    ColorStateList.valueOf(color)
                recorderBinding.tvMiniRecStateLabel.text = label
                recorderBinding.tvMiniRecStateLabel.setTextColor(color)
            }
        }

        // ── Record/Pause button icon + tint ────────────────────────────────
        lifecycleScope.launch {
            viewModel.recordingState.collect { state ->
                when (state) {
                    RecordingService.State.IDLE -> {
                        recorderBinding.btnMiniRecPause.setImageResource(R.drawable.ic_record_circle_mini)
                        recorderBinding.btnMiniRecPause.imageTintList = null   // let the vector's own colours show
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

        // ── Record/Pause button action — actually toggles pause/resume ─────
        recorderBinding.btnMiniRecPause.setOnClickListener {
            when (viewModel.recordingState.value) {
                RecordingService.State.IDLE ->
                    // triggerQuickRecord() handles mic permission + service-binding race.
                    recordFragment.triggerQuickRecord()
                RecordingService.State.RECORDING,
                RecordingService.State.PAUSED ->
                    viewModel.requestToggleRecordingPause()
            }
        }

        // ── Dim / disable non-record controls when IDLE ────────────────────
        //
        // IDLE:               save, mark cluster, and timeline are inert.
        //                     Elapsed text is near-invisible (alpha 0.15).
        // RECORDING / PAUSED: everything is fully live.
        lifecycleScope.launch {
            viewModel.recordingState.collect { state ->
                val active = state != RecordingService.State.IDLE

                // Controls that make no sense before a recording has started
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

        // ── Save button ────────────────────────────────────────────────────
        recorderBinding.btnMiniRecSave.setOnClickListener {
            navigateTo(PAGE_RECORD)
            binding.root.postDelayed({ recordFragment.triggerSaveFromExternal() }, 80L)
        }

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
        lifecycleScope.launch {
            viewModel.recordingState.collect { state ->
                recorderBinding.miniRecTimeline.isRecording =
                    state == RecordingService.State.RECORDING
            }
        }

        // ── Timeline mark tap → select that mark ──────────────────────────
        recorderBinding.miniRecTimeline.onMarkTapped = { index ->
            viewModel.selectRecordingMark(index)
        }
        // Timeline needs to be clickable to receive touch events
        recorderBinding.miniRecTimeline.isClickable = true

        // ── Mark button ────────────────────────────────────────────────────
        recorderBinding.btnMiniMark.setOnClickListener {
            viewModel.requestDropMark()
        }

        // ── Nudge back ─────────────────────────────────────────────────────
        recorderBinding.btnMiniNudgeBack.setOnClickListener {
            viewModel.requestNudgeBack()
        }

        // ── Nudge forward ──────────────────────────────────────────────────
        recorderBinding.btnMiniNudgeForward.setOnClickListener {
            viewModel.requestNudgeForward()
        }

        // ── Commit nudge ───────────────────────────────────────────────────
        recorderBinding.btnMiniNudgeCommit.setOnClickListener {
            viewModel.commitMarkNudge()
        }

        // ── Delete selected recording mark ────────────────────────────────────
        recorderBinding.btnMiniRecDeleteMark.setOnClickListener {
            viewModel.deleteSelectedRecordingMark()
        }

        // ── Enable/disable nudge cluster ───────────────────────────────────
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
                recorderBinding.btnMiniRecDeleteMark.alpha = if (hasSelection) 1f else 0.3f
                recorderBinding.btnMiniRecDeleteMark.imageTintList =
                    ColorStateList.valueOf(if (hasSelection) tealColor else greyColor)

                for (v in listOf(
                    recorderBinding.btnMiniNudgeBack,
                    recorderBinding.btnMiniNudgeForward,
                    recorderBinding.btnMiniNudgeCommit
                )) {
                    v.isEnabled = canNudge
                    v.alpha = if (canNudge) 1f else 0.3f
                    v.imageTintList = ColorStateList.valueOf(if (canNudge) tealColor else greyColor)
                }
            }
        }

        lifecycleScope.launch {
            combine(viewModel.recordingTopicId, viewModel.allTopics) { topicId, topics ->
                topics.firstOrNull { it.id == topicId }?.icon ?: Icons.INBOX
            }.collect { icon ->
                recorderBinding.btnMiniRecTopic.text = icon
            }
        }

        supportFragmentManager.setFragmentResultListener(
            TopicPickerBottomSheet.REQUEST_KEY + "_mini_rec", this
        ) { _, bundle ->
            val topicId = TopicPickerBottomSheet.topicIdFromBundle(bundle)
            viewModel.setRecordingTopicId(topicId)
            val topic = viewModel.allTopics.value.firstOrNull { it.id == topicId }
            recorderBinding.btnMiniRecTopic.text = topic?.icon ?: Icons.INBOX
        }

        recorderBinding.btnMiniRecTopic.setOnClickListener {
            TopicPickerBottomSheet.newInstance(
                selectedTopicId = viewModel.recordingTopicId.value,
                requestKey      = TopicPickerBottomSheet.REQUEST_KEY + "_mini_rec"
            ).show(supportFragmentManager, "mini_rec_topic_picker")
        }

        // ── Tap root (not on buttons) → navigate to Record tab ────────────
        recorderBinding.root.setOnClickListener { navigateTo(PAGE_RECORD) }
    }

    /**
     * Builds a pill-shaped GradientDrawable with the given fill and stroke.
     * cornerRadius = 999dp produces true semicircular ends at any height.
     */
    private fun pillBackground(fillColor: Int, strokeColor: Int): GradientDrawable {
        val dp = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f * dp
            setColor(fillColor)
            setStroke((1.5f * dp).toInt(), strokeColor)
        }
    }

    /**
     * Builds a solid pill-shaped GradientDrawable (no stroke).
     * Used for the minimize buttons on each widget.
     */
    private fun solidPillBackground(fillColor: Int): GradientDrawable {
        val dp = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f * dp
            setColor(fillColor)
        }
    }

    /**
     * Wires the minimize button on the Mini Player and the corresponding
     * pill in the title bar — click to minimize, tap pill to restore.
     *
     * Also drives:
     *  • mini player visibility (full widget hidden when minimized)
     *  • pill visibility + content (shown + updated when minimized)
     *  • title text alpha (dimmed when either pill is overlaying it)
     */
    private fun setupMiniPlayerMinimize() {
        val p    = binding.miniPlayer
        val pill = binding.titlePills

        // ── Close button → stop & clear everything ────────────────────────
        p.btnMiniPlayerClose.setOnClickListener {
            viewModel.stopAndClear()
            // playerPillMinimized is reset inside stopAndClear(); no extra call needed.
        }
        // Close button tint — dim secondary colour so it reads as less prominent
        // than the minimize button
        p.btnMiniPlayerClose.imageTintList =
            ColorStateList.valueOf(themeColor(R.attr.colorTextSecondary))

        // ── Minimize button → set state ───────────────────────────────────
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

        // ── Pill tap ──────────────────────────────────────────────────────
        // • NEVER mode  → navigate to Listen tab (pill acts as a shortcut)
        // • Other modes → restore widget; set override so it survives tab suppression
        pill.pillPlayer.setOnClickListener {
            when {
                // NEVER mode - pill is always a shortcut to the tab
                viewModel.playerWidgetVisibility.value == PlayerWidgetVisibility.NEVER -> {
                    navigateTo(PAGE_LISTEN)
                }
                // PLAYING mode - only show when playing but nothing is loaded
                ((viewModel.playerWidgetVisibility.value == PlayerWidgetVisibility.WHILE_PLAYING) && (viewModel.nowPlaying.value == null)) -> {
                    navigateTo(PAGE_LISTEN)
                }
                // Widget is currently visible → navigate to tab
                isPlayerWidgetVisible() -> {
                    navigateTo(PAGE_LISTEN)
                }
                // Widget is hidden (minimized or suppressed) → show it
                else -> {
                    viewModel.setPlayerHideOverriddenThisVisit(true)
                    viewModel.setPlayerPillMinimized(false)
                }
            }
        }

        // ── Pill visibility ───────────────────────────────────────────────
        //
        // pillVisible = alwaysShow
        //            || ( (hasContent && minimized) && !(hideOnListenTab && onListenTab) )
        //
        // Note: alwaysShow gives full override — pill visible even when suppressed by tab.
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

        // ── Pill content ──────────────────────────────────────────────────
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
                    val icon = topic?.icon ?: Icons.INBOX
                    pill.pillPlayerTopic.text = icon
                    pill.pillPlayerTopic.visibility = View.VISIBLE
                    pill.pillPlayerFilename.text = state.recording.title
                    pill.pillPlayerFilename.isSelected = true
                } else {
                    // Idle pill state — grey icon, no filename
                    pill.ivPillPlayerPlay.setImageResource(R.drawable.ic_stop_square)
                    pill.ivPillPlayerPlay.imageTintList =
                        ColorStateList.valueOf(themeColor(R.attr.colorTextSecondary))
                    pill.pillPlayerTopic.visibility = View.GONE
                    pill.pillPlayerFilename.text = "NO SELECTION"
                    //pill.pillPlayerFilename.isSelected = false   // stop marquee scroll on idle
                }
            }
        }

        // ── Widget visibility ─────────────────────────────────────────────
        //
        // shouldShow = (visibility==ALWAYS) || (hasContent && visibility!=NEVER)
        // suppressed = hideOnListenTab && onListenTab && !overriddenThisVisit
        // widgetVisible = shouldShow && !suppressed && !minimized
        lifecycleScope.launch {
            // Combine nowPlaying + playerWidgetVisibility + override into a
            // single inner flow so we stay within Kotlin's 5-arg combine limit.
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
                val alwaysShow  = visibility == PlayerWidgetVisibility.ALWAYS
                val neverShow   = visibility == PlayerWidgetVisibility.NEVER
                val shouldShow  = alwaysShow || (hasContent && !neverShow)
                val onListenTab = page == PAGE_LISTEN
                val suppressed  = hideOnListenTab && onListenTab && !overridden
                shouldShow && !suppressed && !minimized
            }.collect { visible ->
                p.root.visibility = if (visible) View.VISIBLE else View.GONE
            }
        }

        // ── Idle state display ────────────────────────────────────────────
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
                    p.tvMiniPlayingLabel.text = "NO SELECTION"
                    p.tvMiniTopicIcon.visibility = View.GONE
                    p.tvMiniTitle.text = ""
                    // Grey out the transport row — nothing to control
                    p.btnMiniPlayPause.isEnabled = false
                    p.btnMiniPlayPause.alpha = 0.3f
                    p.btnMiniSkipBack.isEnabled = false
                    p.btnMiniSkipBack.alpha = 0.3f
                    p.btnMiniSkipForward.isEnabled = false
                    p.btnMiniSkipForward.alpha = 0.3f
                    p.miniPlayerTimeline.visibility = View.INVISIBLE
                } else {
                    p.tvMiniPlayingLabel.text = "NOW PLAYING:"
                    p.tvMiniTopicIcon.visibility = View.VISIBLE
                    p.btnMiniPlayPause.isEnabled = true
                    p.btnMiniPlayPause.alpha = 1f
                    p.btnMiniSkipBack.isEnabled = true
                    p.btnMiniSkipBack.alpha = 1f
                    p.btnMiniSkipForward.isEnabled = true
                    p.btnMiniSkipForward.alpha = 1f
                    p.miniPlayerTimeline.visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * Wires the minimize button on the Mini Recorder and its pill.
     */
    private fun setupMiniRecorderMinimize() {
        val rec  = binding.miniRecorder
        val pill = binding.titlePills

        // ── Minimize button → set state ───────────────────────────────────
        rec.btnMiniRecorderMinimize.setOnClickListener {
            viewModel.setRecorderPillMinimized(true)
        }
        rec.btnMiniRecorderMinimize.background =
            solidPillBackground(themeColor(R.attr.colorRecordActive))

        // ── Pill tap ──────────────────────────────────────────────────────
        pill.pillRecorder.setOnClickListener {
            when {
                // NEVER mode - pill always redirects
                viewModel.recorderWidgetVisibility.value == RecorderWidgetVisibility.NEVER -> {
                    navigateTo(PAGE_RECORD)
                }
                // RECORDING mode but no media loaded
                ((viewModel.recorderWidgetVisibility.value == RecorderWidgetVisibility.WHILE_RECORDING) && (viewModel.recordingState.value == RecordingService.State.IDLE)) -> {
                    navigateTo(PAGE_RECORD)
                }
                // mini recorder already visible, second tap nav's to page
                isRecorderWidgetVisible() -> {
                    navigateTo(PAGE_RECORD)
                }
                // otherwise show the mini recorder, override visibility suppression if necessary (not NEVER mode after all)
                else -> {
                    viewModel.setRecorderHideOverriddenThisVisit(true)
                    viewModel.setRecorderPillMinimized(false)
                }
            }
        }

        // ── Pill visibility ───────────────────────────────────────────────
        //
        // pillVisible = alwaysShow
        //            || ( (hasContent && minimized) && !(hideOnRecordTab && onRecordTab) )
        lifecycleScope.launch {
            combine(
                viewModel.recordingState,
                viewModel.recorderPillMinimized,
                viewModel.hideRecorderOnRecordTab,
                viewModel.currentPage,
                viewModel.alwaysShowRecorderPill
            ) { state, minimized, hideOnRecordTab, page, alwaysShow ->
                val hasContent = when (viewModel.recorderWidgetVisibility.value) {
                    RecorderWidgetVisibility.NEVER  -> alwaysShow   // only alwaysShow path matters
                    RecorderWidgetVisibility.WHILE_RECORDING -> state != RecordingService.State.IDLE
                    RecorderWidgetVisibility.ALWAYS -> true
                }
                val onRecordTab = page == PAGE_RECORD
                val tabVisible  = hasContent && minimized && !(hideOnRecordTab && onRecordTab)
                alwaysShow || tabVisible
            }.collect { pillVisible ->
                pill.pillRecorder.visibility = if (pillVisible) View.VISIBLE else View.GONE
                updateTitleTextAlpha()
            }
        }

        // ── Widget visibility ─────────────────────────────────────────────
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

        // ── Pill content observer — unchanged from before ─────────────────
        lifecycleScope.launch {
            viewModel.recordingState.collect { state ->
                val (iconRes, label, textColor) = when (state) {
                    RecordingService.State.IDLE ->
                        Triple(R.drawable.ic_stop_square, "READY", themeColor(R.attr.colorTextSecondary))
                    RecordingService.State.RECORDING ->
                        Triple(R.drawable.ic_record_circle, "REC", themeColor(R.attr.colorRecordActive))
                    RecordingService.State.PAUSED ->
                        Triple(R.drawable.ic_pause, "PAUSED", themeColor(R.attr.colorRecordPause))
                }
                pill.ivPillRecorderDot.visibility =
                    if (state == RecordingService.State.PAUSED) View.VISIBLE else View.GONE
                pill.ivPillRecorderDot.imageTintList =
                    ColorStateList.valueOf(themeColor(R.attr.colorRecordActive))
                pill.ivPillRecorderIcon.setImageResource(iconRes)
                pill.ivPillRecorderIcon.imageTintList = ColorStateList.valueOf(textColor)
                pill.pillRecorderStatus.text = label
                pill.pillRecorderStatus.setTextColor(textColor)

                // Topic Icon
                val topicIcon = viewModel.allTopics.value
                    .firstOrNull { it.id == viewModel.recordingTopicId.value }?.icon
                if (topicIcon != null) {
                    pill.pillRecorderTopic.text = topicIcon
                    pill.pillRecorderTopic.setTextColor(textColor)
                    pill.pillRecorderTopic.visibility = View.VISIBLE
                } else {
                    pill.pillRecorderTopic.visibility = View.GONE
                }

                // Pill Border
                pill.pillRecorder.background = when (state) {
                    RecordingService.State.IDLE ->
                        pillBackground(
                            themeColor(R.attr.colorSurfaceBase),
                            themeColor(R.attr.colorRecordActive)
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
    }

    /**
     * Watches currentPage and resets the per-visit suppress-override flags
     * whenever the user leaves the tab that owns each widget.
     *
     * This means: if the user tapped the player pill to force-show the player
     * on the Listen tab, navigating away resets that override so the widget
     * re-hides on the next visit to that tab.
     */
    private fun setupTabChangeOverrideReset() {
        lifecycleScope.launch {
            var prevPage = viewModel.currentPage.value
            viewModel.currentPage.collect { page ->
                if (page != prevPage) {
                    // ── Reset on EXIT from the suppressed tab ─────────────
                    if (prevPage == PAGE_LISTEN) viewModel.setPlayerHideOverriddenThisVisit(false)
                    if (prevPage == PAGE_RECORD) viewModel.setRecorderHideOverriddenThisVisit(false)

                    // ── Reset on ENTRY to the suppressed tab ──────────────
                    // Catches the case where the override was stale from a
                    // previous visit and the exit reset didn't fire cleanly.
                    if (page == PAGE_LISTEN) viewModel.setPlayerHideOverriddenThisVisit(false)
                    if (page == PAGE_RECORD) viewModel.setRecorderHideOverriddenThisVisit(false)

                    prevPage = page
                }
            }
        }
    }

    /**
     * Dims tvTopTitle when pills are overlaying it, restores full alpha when none are.
     * Called after each pill visibility update.
     */
    private fun updateTitleTextAlpha() {
        val anyPill = binding.titlePills.pillPlayer.visibility  == View.VISIBLE ||
                binding.titlePills.pillRecorder.visibility == View.VISIBLE
        binding.tvTopTitle.animate()
            .alpha(if (anyPill) 0.35f else 1.0f)
            .setDuration(150)
            .start()
    }

    /**
     * Returns true if the Mini Player widget is currently on screen.
     * Used by the pill tap handler to decide navigate-vs-show.
     */
    private fun isPlayerWidgetVisible(): Boolean {
        val vm          = viewModel
        val state       = vm.nowPlaying.value
        val visibility  = vm.playerWidgetVisibility.value
        val hasContent  = state != null
        val alwaysShow  = visibility == PlayerWidgetVisibility.ALWAYS
        val neverShow   = visibility == PlayerWidgetVisibility.NEVER
        val shouldShow  = alwaysShow || (hasContent && !neverShow)
        val suppressed  = vm.hidePlayerOnListenTab.value
                && vm.currentPage.value == PAGE_LISTEN
                && !vm.playerHideOverriddenThisVisit.value
        val minimized   = vm.playerPillMinimized.value
        return shouldShow && !suppressed && !minimized
    }

    /**
     * Returns true if the Mini Recorder widget is currently on screen.
     * Used by the pill tap handler to decide navigate-vs-show.
     */
    private fun isRecorderWidgetVisible(): Boolean {
        val vm         = viewModel
        val state      = vm.recordingState.value
        val visibility = vm.recorderWidgetVisibility.value
        val wantShow   = when (visibility) {
            RecorderWidgetVisibility.NEVER           -> false
            RecorderWidgetVisibility.WHILE_RECORDING -> state != RecordingService.State.IDLE
            RecorderWidgetVisibility.ALWAYS          -> true
        }
        val suppressed = vm.hideRecorderOnRecordTab.value
                && vm.currentPage.value == PAGE_RECORD
                && !vm.recorderHideOverriddenThisVisit.value
        val minimized  = vm.recorderPillMinimized.value
        return wantShow && !suppressed && !minimized
    }

    // ── Top title ─────────────────────────────────────────────────────
    private fun observeTopTitle() {
        lifecycleScope.launch {
            viewModel.topTitle.collect { title ->
                binding.tvTopTitle.text = title
            }
        }
    }

    // ── Lock ──────────────────────────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility") // this warning is from the setOnTouchListener call that doesn't route anywhere (deliberately)
    private fun observeLockState() {
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

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}