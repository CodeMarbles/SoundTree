package com.treecast.app.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.treecast.app.R
import com.treecast.app.data.entities.BackupLogEntity
import com.treecast.app.databinding.ActivityMainBinding
import com.treecast.app.service.RecordingService
import com.treecast.app.ui.library.LibraryFragment
import com.treecast.app.ui.record.RecordFragment
import com.treecast.app.ui.recovery.OrphanRecoveryDialogFragment
import com.treecast.app.util.OrphanRecording
import com.treecast.app.util.StorageVolumeEventReceiver
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QUICK_RECORD = "quick_record"

        // Extras set by RecordingService when a save is triggered from the
        // notification action button. MainActivity uses these in onNewIntent
        // (app already running) and onCreate (cold start after notification
        // save, unlikely but handled) to navigate to the saved recording.
        const val EXTRA_SAVED_RECORDING_ID = "saved_recording_id"
        const val EXTRA_SAVED_TOPIC_ID     = "saved_topic_id"

        // Orphan-recovery extras: set by SplashActivity when OrphanRecordingScanner
        // finds TC_*.m4a files on disk with no matching database row.
        // MainActivity reads these in onCreate and shows OrphanRecoveryDialogFragment.
        const val EXTRA_ORPHAN_PLAYABLE_PATHS         = "orphan_playable_paths"
        const val EXTRA_ORPHAN_PLAYABLE_DURATIONS_MS  = "orphan_playable_durations_ms"
        const val EXTRA_ORPHAN_CORRUPT_PATHS          = "orphan_corrupt_paths"

        const val PAGE_SETTINGS = 0
        const val PAGE_RECORD   = 1
        const val PAGE_LIBRARY  = 2
        const val PAGE_LISTEN   = 3
        const val PAGE_WORKSPACE = 4

        /** Set as an extra on the backup notification PendingIntent. */
        const val EXTRA_NAVIGATE_TO_SETTINGS    = "navigate_to_settings"
        const val EXTRA_NAVIGATE_TO_STORAGE_TAB = "navigate_to_storage_tab"
    }

    internal lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()

    private var storageVolumeEventReceiver: StorageVolumeEventReceiver? = null

    // ── Back-stack navigation ─────────────────────────────────────────
    //
    // navHistory records every page the user visits, in order.
    // Back presses walk it in reverse; when only one entry remains
    // (or it's empty) we fall through to finish().
    //
    // isNavigatingBack suppresses history recording during the
    // programmatic setCurrentItem() call that back navigation triggers.
    internal val navHistory = ArrayDeque<Int>()
    internal var isNavigatingBack = false

    // Direct reference to the Library fragment so we can ask it to
    // handle sub-page back presses (Uncategorized → Tree View) before
    // we pop our own back stack.
    internal lateinit var libraryFragment: LibraryFragment

    // Direct reference to the Record fragment so we can
    internal lateinit var recordFragment: RecordFragment

    // Track whether we're restoring from a config change
    internal var isRestoredFromState = false

    private val stripAutoDismissHandler = Handler(Looper.getMainLooper())
    private var stripCurrentDismissLogId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // helps track whether this is a first launch or an app reconfig launch on older android devices
        isRestoredFromState = savedInstanceState != null

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPager()
        observeTopTitle()
        setupBottomNav()

        setupMiniPlayer()
        setupMiniPlayerMinimize()
        setupMiniRecorder()
        setupMiniRecorderMinimize()
        setupBackupStatusStrip()

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

        // This code block is a guard derived from Android 10 theme swapping putting the nav in a
        // bad state.  Here is Claude's explanation:
        //
        // The .post { } queues after the first layout pass, ensuring the ViewPager has settled on
        // its actual position before the nav is drawn — which is also the right moment to read
        // currentItem reliably on older Android versions.
        // Part 1 alone (a guard in setupViewPager) will fix the visible symptom on Android 10.
        // Part 2 (this fix) makes the recreation path cleaner overall and prevents the nav from
        // ever flashing the wrong selection after a theme change.
        binding.viewPager.post {
            val page = binding.viewPager.currentItem
            val isRecording = viewModel.recordingState.value != RecordingService.State.IDLE
            viewModel.setCurrentPage(page)
            updateBottomNavSelection(page, isRecording)
        }

        // Handle deep-link from notification
        if (intent.getBooleanExtra(EXTRA_NAVIGATE_TO_SETTINGS, false)) {
            binding.viewPager.setCurrentItem(PAGE_SETTINGS, false)
            if (intent.getBooleanExtra(EXTRA_NAVIGATE_TO_STORAGE_TAB, false)) {
                viewModel.requestNavigateToStorageTab()
            }
        }

        checkAndShowOrphanRecovery()
    }

    override fun onStart() {
        super.onStart()
        storageVolumeEventReceiver = StorageVolumeEventReceiver {
            // Called on the main thread — safe to update ViewModel directly.
            viewModel.refreshStorageVolumes()
        }.also { receiver ->
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                // Storage broadcasts require a data scheme to be delivered.
                addDataScheme("file")
            }
            registerReceiver(receiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.onAppClose()
        storageVolumeEventReceiver?.let { unregisterReceiver(it) }
        storageVolumeEventReceiver = null
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

        if (intent?.getBooleanExtra(EXTRA_NAVIGATE_TO_SETTINGS, false) == true) {
            binding.viewPager.setCurrentItem(PAGE_SETTINGS, false)
            if (intent.getBooleanExtra(EXTRA_NAVIGATE_TO_STORAGE_TAB, false)) {
                viewModel.requestNavigateToStorageTab()
            }
        }
    }

    /**
     * Navigates to the Library and selects the recording identified by the
     * extras in [intent]. Mirrors the post-save navigation that
     * [RecordFragment.stopAndSave] performs when the app is in the foreground.
     *
     * Only called when [EXTRA_SAVED_RECORDING_ID] is present; the topic extra
     * is optional (null means the recording landed in Unsorted).
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

    /**
     * Reads orphan-recording extras placed by [com.treecast.app.ui.SplashActivity],
     * publishes the results to [MainViewModel.setOrphanResults] unconditionally
     * (so the Settings card always shows accurate counts), then shows
     * [OrphanRecoveryDialogFragment] only when the list is non-empty.
     *
     * Called once from [onCreate].
     */
    private fun checkAndShowOrphanRecovery() {
        val playablePaths     = intent.getStringArrayListExtra(EXTRA_ORPHAN_PLAYABLE_PATHS).orEmpty()
        val playableDurations = intent.getLongArrayExtra(EXTRA_ORPHAN_PLAYABLE_DURATIONS_MS) ?: LongArray(0)
        val corruptPaths      = intent.getStringArrayListExtra(EXTRA_ORPHAN_CORRUPT_PATHS).orEmpty()

        val orphans = buildList {
            playablePaths.forEachIndexed { i, path ->
                add(
                    OrphanRecording(
                        file           = File(path),
                        suggestedTitle = "",   // re-derived inside the dialog
                        durationMs     = playableDurations.getOrElse(i) { 0L },
                    )
                )
            }
            corruptPaths.forEach { path ->
                add(
                    OrphanRecording(
                        file           = File(path),
                        suggestedTitle = "",
                        durationMs     = 0L,
                    )
                )
            }
        }

        // Always publish — SettingsFragment observes this to show counts/sizes.
        viewModel.setOrphanResults(orphans)

        if (orphans.isEmpty()) return

        // Only show the dialog on a genuine cold start — not when the activity
        // is being recreated due to a configuration change (e.g. a theme switch).
        // On Android 10, theme changes trigger activity recreation and would
        // otherwise re-surface this dialog even after the user dismissed it.
        if (orphans.isNotEmpty() && !isRestoredFromState) {
            OrphanRecoveryDialogFragment
                .newInstance(orphans)
                .show(supportFragmentManager, OrphanRecoveryDialogFragment.TAG)
        }
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
        val anyPillActive = viewModel.playerPillMinimized.value   || viewModel.recorderPillMinimized.value
                || viewModel.alwaysShowPlayerPill.value  || viewModel.alwaysShowRecorderPill.value
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
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
                binding.rootStack.addView(view, lp)
            } else {
                val heightPx = when (element) {
                    LayoutElement.MINI_PLAYER   -> (108 * dp).toInt()
                    LayoutElement.MINI_RECORDER -> (108 * dp).toInt()
                    LayoutElement.NAV           -> (64 * dp).toInt()
                    LayoutElement.TITLE_BAR -> if (pillOnlyMode) LinearLayout.LayoutParams.WRAP_CONTENT
                    else               (53 * dp).toInt()
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
     * Observes [MainViewModel.layoutOrder] and [MainViewModel.showTitleBar]
     * together, re-applying the layout whenever the user commits a change.
     *
     * Uses [combine] so a single Apply that updates both simultaneously only
     * triggers one re-layout pass.
     */
    private fun observeLayoutOrder() {
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
                viewModel.alwaysShowRecorderPill
            ) { _, _, _ -> }
                .collect { applyLayoutOrder() }
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

    // ── Public navigation helpers ─────────────────────────────────────
    fun navigateTo(page: Int) {
        binding.viewPager.currentItem = page
        // Eagerly sync nav + ViewModel so same-page assignments
        // (e.g. after a silent setCurrentItem on Android 10) still update correctly.
        val isRecording = viewModel.recordingState.value != RecordingService.State.IDLE
        updateBottomNavSelection(page, isRecording)
        viewModel.setCurrentPage(page)
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

    fun navigateToTopicDetails(topicId: Long) {
        navigateTo(PAGE_LIBRARY)
        libraryFragment.enteredFromExternalTab = true
        libraryFragment.openTopicDetails(topicId)
    }

    fun navigateToLibraryUnsorted() {
        navigateTo(PAGE_LIBRARY)
        libraryFragment.enteredFromExternalTab = true
        libraryFragment.navigateToUnsorted()
    }

    fun setTopTitle(title: String) {
        viewModel.setTopTitle(title)
    }

    /**
     * Builds a pill-shaped GradientDrawable with the given fill and stroke.
     * cornerRadius = 999dp produces true semicircular ends at any height.
     */
    internal fun pillBackground(fillColor: Int, strokeColor: Int): GradientDrawable {
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
    internal fun solidPillBackground(fillColor: Int): GradientDrawable {
        val dp = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f * dp
            setColor(fillColor)
        }
    }

    /**
     * Observes [MainViewModel.backupStripState] and drives the title-bar status
     * strip: slide-down animation on appearance, content binding for Running and
     * Completed states, auto-dismiss timers, and tap-to-navigate behaviour.
     */
    private fun setupBackupStatusStrip() {
        val strip          = binding.backupStatusStrip
        val stripHeight    = resources.getDimensionPixelSize(R.dimen.backup_strip_height)

        // Start off-screen so the first slide-down looks correct.
        strip.root.translationY = -stripHeight.toFloat()

        // Pulsing animator for the border while a job is running.
        val pulseAnimator = ValueAnimator.ofFloat(1f, 0.35f, 1f).apply {
            duration       = 1_400
            repeatCount    = ValueAnimator.INFINITE
            interpolator   = AccelerateDecelerateInterpolator()
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
                            if (strip.root.visibility == View.VISIBLE) slideStripUp(strip.root, stripHeight, pulseAnimator)
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

                            // Progress
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
                                ContextCompat.getColor(this@MainActivity, R.color.backup_strip_running)
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

                            // Border colour
                            val borderColor = when (log.status) {
                                BackupLogEntity.BackupStatus.SUCCESS ->
                                    ContextCompat.getColor(this@MainActivity, R.color.backup_strip_success)
                                BackupLogEntity.BackupStatus.PARTIAL ->
                                    ContextCompat.getColor(this@MainActivity, R.color.backup_strip_partial)
                                else ->
                                    ContextCompat.getColor(this@MainActivity, R.color.backup_strip_failed)
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

        // Tap → navigate to Settings → Storage
        strip.root.setOnClickListener {
            binding.viewPager.setCurrentItem(PAGE_SETTINGS, false)
            viewModel.requestNavigateToStorageTab()
            // Dismiss a completed strip on tap
            (lastState as? BackupStripState.Completed)?.let {
                stripAutoDismissHandler.removeCallbacksAndMessages(null)
                viewModel.dismissBackupStrip(it.log.id)
            }
        }
    }

    private fun slideStripDown(view: View, stripHeight: Int) {
        view.translationY = -stripHeight.toFloat()
        view.visibility   = View.VISIBLE
        view.animate()
            .translationY(0f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun slideStripUp(view: View, stripHeight: Int, pulseAnimator: ValueAnimator) {
        pulseAnimator.cancel()
        view.animate()
            .translationY(-stripHeight.toFloat())
            .setDuration(150)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { view.visibility = View.GONE }
            .start()
    }

    /**
     * Dims tvTopTitle when pills are overlaying it, restores full alpha when none are.
     * Called after each pill visibility update.
     */
    internal fun updateTitleTextAlpha() {
        val anyPill = binding.titlePills.pillPlayer.visibility  == View.VISIBLE ||
                binding.titlePills.pillRecorder.visibility == View.VISIBLE
        binding.tvTopTitle.animate()
            .alpha(if (anyPill) 0.35f else 1.0f)
            .setDuration(150)
            .start()
    }

    internal fun formatMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}