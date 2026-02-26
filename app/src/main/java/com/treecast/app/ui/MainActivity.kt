package com.treecast.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.treecast.app.R
import com.treecast.app.databinding.ActivityMainBinding
import com.treecast.app.ui.library.LibraryFragment
import com.treecast.app.ui.listen.ListenFragment
import com.treecast.app.ui.record.RecordFragment
import com.treecast.app.ui.settings.SettingsFragment
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.onAppOpen()

        setupViewPager()
        setupBottomNav()
        setupMiniPlayer()
        setupBackNavigation()
        observeLockState()
        observeTopTitle()

        when {
            // A save completed in the notification while the app was away —
            // navigate directly to the saved recording.
            intent.hasExtra(EXTRA_SAVED_RECORDING_ID) -> handleNotificationSaveIntent(intent)

            // Normal quick-record launch from SplashActivity.
            intent.getBooleanExtra(EXTRA_QUICK_RECORD, false) ->
                binding.viewPager.currentItem = PAGE_RECORD
        }
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

    // ── ViewPager ─────────────────────────────────────────────────────
    private fun setupViewPager() {
        libraryFragment = LibraryFragment()

        val adapter = MainPagerAdapter(this).apply {
            addFragment(SettingsFragment(), "Settings")
            addFragment(RecordFragment(),   "Record")
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
        val accent = ContextCompat.getColor(this, R.color.accent)
        val dim    = ContextCompat.getColor(this, R.color.text_dim)

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
                    binding.miniPlayer.miniProgressBar.progress = frac
                    binding.miniPlayer.tvMiniTime.text =
                        "${formatMs(state.positionMs)} / ${formatMs(state.durationMs)}"
                    binding.miniPlayer.btnMiniPlayPause.setImageResource(
                        if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )
                }
            }
        }

        binding.miniPlayer.root.setOnClickListener {
            navigateTo(PAGE_LISTEN)
        }

        binding.miniPlayer.btnMiniPlayPause.setOnClickListener {
            viewModel.togglePlayPause()
        }

        binding.miniPlayer.btnMiniSkipBack.setOnClickListener {
            viewModel.skipBack()
        }

        binding.miniPlayer.btnMiniSkipForward.setOnClickListener {
            viewModel.skipForward()
        }
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