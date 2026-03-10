package com.treecast.app.ui

import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
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
import com.treecast.app.ui.library.LibraryFragment
import com.treecast.app.ui.listen.ListenFragment
import com.treecast.app.ui.record.RecordFragment
import com.treecast.app.ui.settings.SettingsFragment
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
    }

    private lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()

    private val pageTitles = mapOf(
        PAGE_SETTINGS to "Settings",
        PAGE_RECORD   to "Record",
        PAGE_LIBRARY  to "Library",
        PAGE_LISTEN   to "Listen"
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
        setupBottomNav()
        setupMiniPlayer()
        setupMiniRecorder()
        setupBackNavigation()
        observeLockState()
        observeTopTitle()
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
        val order     = viewModel.layoutOrder.value
        val showTitle = viewModel.showTitleBar.value

        val viewMap = mapOf(
            LayoutElement.TITLE_BAR    to binding.titleBarContainer,
            LayoutElement.CONTENT      to binding.viewPager,
            LayoutElement.MINI_PLAYER  to binding.miniPlayer.root,
            LayoutElement.MINI_RECORDER to binding.miniRecorder.root,   // ← new
            LayoutElement.NAV          to binding.bottomNav
        )

        binding.rootStack.removeAllViews()

        val dp = resources.displayMetrics.density

        for (element in order) {
            val view = viewMap[element] ?: continue
            if (element == LayoutElement.TITLE_BAR && !showTitle) continue
            if (element == LayoutElement.CONTENT) {
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
                binding.rootStack.addView(view, lp)
            } else {
                val heightPx = when (element) {
                    LayoutElement.MINI_PLAYER   -> (80 * dp).toInt()
                    LayoutElement.MINI_RECORDER -> (96 * dp).toInt()
                    LayoutElement.NAV           -> (64 * dp).toInt()
                    LayoutElement.TITLE_BAR     -> (53 * dp).toInt()
                    else -> android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                }
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, heightPx
                )
                binding.rootStack.addView(view, lp)
            }
        }

        updateMiniPlayerAccentLine(order)
        updateMiniRecorderAccentLine(order)  // ← new
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
            combine(viewModel.layoutOrder, viewModel.showTitleBar) { order, showTitle ->
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

    // ── ViewPager ─────────────────────────────────────────────────────
    private fun setupViewPager() {
        libraryFragment = LibraryFragment()
        recordFragment = RecordFragment()

        val adapter = MainPagerAdapter(this).apply {
            addFragment(SettingsFragment(), "Settings")
            addFragment(recordFragment,   "Record")
            addFragment(libraryFragment,    "Library")
            addFragment(ListenFragment(),   "Listen")
        }
        binding.viewPager.adapter = adapter
        binding.viewPager.offscreenPageLimit = 4
        binding.viewPager.setCurrentItem(PAGE_RECORD, false)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateBottomNavSelection(position)
                if (position != PAGE_LIBRARY) {
                    viewModel.setTopTitle(pageTitles[position] ?: "")
                }
                // When on Library, LibraryFragment drives the top title itself.

                // ── History recording ──────────────────────────────────
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
        updateBottomNavSelection(PAGE_RECORD)
    }

    private fun updateBottomNavSelection(position: Int) {
        val accent = themeColor(R.attr.colorAccent)
        val dim    = themeColor(R.attr.colorTextSecondary)

        fun icon(iv: android.widget.ImageView, label: android.widget.TextView?, active: Boolean) {
            iv.setColorFilter(if (active) accent else dim)
            iv.alpha = if (active) 1f else 0.5f
            label?.setTextColor(if (active) accent else dim)
        }

        icon(binding.ivSettingsIcon, binding.tvSettingsLabel, position == PAGE_SETTINGS)
        icon(binding.ivRecordIcon,   binding.tvRecordLabel,   position == PAGE_RECORD)
        icon(binding.ivLibraryIcon,  binding.tvLibraryLabel,  position == PAGE_LIBRARY)
        icon(binding.ivListenIcon,   binding.tvListenLabel,   position == PAGE_LISTEN)
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
        lifecycleScope.launch {
            viewModel.nowPlaying.collect { state ->
                binding.miniPlayer.root.visibility = if (state != null) View.VISIBLE else View.GONE
                if (state != null) {
                    binding.miniPlayer.tvMiniTitle.text = state.recording.title
                    val frac = if (state.durationMs > 0)
                        (state.positionMs * 1000 / state.durationMs).toInt() else 0
                    binding.miniPlayer.miniProgressBar.setProgress(frac)   // ← was .progress =
                    binding.miniPlayer.tvMiniTime.text =
                        "${formatMs(state.positionMs)} / ${formatMs(state.durationMs)}"
                    binding.miniPlayer.btnMiniPlayPause.setImageResource(
                        if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )
                }
            }
        }

        // Feed mark dot positions to the progress view
        lifecycleScope.launch {
            viewModel.marks.collect { marks ->
                val dur = viewModel.nowPlaying.value?.durationMs ?: return@collect
                if (dur <= 0) return@collect
                val fracs = marks.map { it.positionMs.toFloat() / dur.toFloat() }
                binding.miniPlayer.miniProgressBar.setMarkFractions(fracs)
            }
        }

        binding.miniPlayer.root.setOnClickListener { navigateTo(PAGE_LISTEN) }
        binding.miniPlayer.btnMiniPlayPause.setOnClickListener { viewModel.togglePlayPause() }
        binding.miniPlayer.btnMiniSkipBack.setOnClickListener { viewModel.skipBack() }
        binding.miniPlayer.btnMiniSkipForward.setOnClickListener { viewModel.skipForward() }
        binding.miniPlayer.btnMiniJumpPrev.setOnClickListener   { viewModel.jumpToPrevMark() }
        binding.miniPlayer.btnMiniJumpNext.setOnClickListener   { viewModel.jumpToNextMark() }
        binding.miniPlayer.btnMiniAddMark.setOnClickListener    { viewModel.addMark() }
    }

    // ── Mini Recorder ───────────────────────────────────────────────────
    private fun setupMiniRecorder() {
        val recorderBinding = binding.miniRecorder

        // ── Visibility ────────────────────────────────────────────────────
        lifecycleScope.launch {
            combine(
                viewModel.recordingState,
                viewModel.showMiniRecorder
            ) { state, alwaysShow -> state to alwaysShow }
                .collect { (state, alwaysShow) ->
                    val isActive = state != RecordingService.State.IDLE
                    recorderBinding.root.visibility =
                        if (isActive || alwaysShow) View.VISIBLE else View.GONE
                }
        }

        // ── Background color: 3-state ──────────────────────────────────────
        val surfaceColor = themeColor(R.attr.colorSurfaceBase)
        val recordingColor = themeColor(R.attr.colorMiniRecorderActive)   // dark/light red
        val pausedColor = themeColor(R.attr.colorMiniRecorderPaused)      // dark/light yellow

        var lastBgColor = surfaceColor

        lifecycleScope.launch {
            viewModel.recordingState.collect { state ->
                val targetColor = when (state) {
                    RecordingService.State.IDLE      -> surfaceColor
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

        // ── Record/Pause button icon + tint ────────────────────────────────
        lifecycleScope.launch {
            viewModel.recordingState.collect { state ->
                recorderBinding.btnMiniRecPause.setImageResource(
                    when (state) {
                        RecordingService.State.RECORDING -> R.drawable.ic_pause
                        RecordingService.State.PAUSED    -> R.drawable.ic_record_circle
                        RecordingService.State.IDLE      -> R.drawable.ic_record_circle
                    }
                )
                val tintColor = when (state) {
                    // RECORDING: red circle means "tap to pause"
                    RecordingService.State.RECORDING ->
                        themeColor(R.attr.colorRecordActive)
                    // PAUSED: yellow/orange means "tap to resume"
                    RecordingService.State.PAUSED ->
                        themeColor(R.attr.colorRecordPause)
                    // IDLE: red dot (not recording yet / stopped)
                    RecordingService.State.IDLE ->
                        themeColor(R.attr.colorRecordActive)
                }
                recorderBinding.btnMiniRecPause.imageTintList =
                    android.content.res.ColorStateList.valueOf(tintColor)
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

        // ── Timeline: update with marks + selected index ───────────────────
        lifecycleScope.launch {
            viewModel.showRecordMarkTimestamp.collect { show ->
                recorderBinding.miniRecTimeline.showLastMarkTimestamp = show
            }
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

        // ── Enable/disable nudge cluster ───────────────────────────────────
        lifecycleScope.launch {
            combine(
                viewModel.recordingMarks,
                viewModel.markNudgeLocked,
                viewModel.selectedRecordingMarkIndex
            ) { marks, locked, selectedIdx -> Triple(marks, locked, selectedIdx) }
                .collect { (marks, locked, selectedIdx) ->
                    val hasActiveSelection = marks.isNotEmpty() &&
                            (selectedIdx >= 0 || marks.isNotEmpty())  // last mark counts
                    val canNudge = hasActiveSelection && !locked

                    recorderBinding.btnMiniNudgeBack.isEnabled    = canNudge
                    recorderBinding.btnMiniNudgeForward.isEnabled = canNudge
                    recorderBinding.btnMiniNudgeCommit.isEnabled  = canNudge

                    recorderBinding.btnMiniNudgeBack.alpha    = if (canNudge) 1f else 0.3f
                    recorderBinding.btnMiniNudgeForward.alpha = if (canNudge) 1f else 0.3f
                    recorderBinding.btnMiniNudgeCommit.alpha  = if (canNudge) 1f else 0.3f
                }
        }

        // ── Tap root (not on buttons) → navigate to Record tab ────────────
        recorderBinding.root.setOnClickListener { navigateTo(PAGE_RECORD) }
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