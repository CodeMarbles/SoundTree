package app.soundtree.ui

import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.soundtree.SoundTreeApp
import app.soundtree.databinding.ActivityMainBinding
import app.soundtree.service.RecordingService
import app.soundtree.storage.StorageRootsObserver
import app.soundtree.ui.library.LibraryFragment
import app.soundtree.ui.record.RecordFragment
import app.soundtree.storage.StorageVolumeEventReceiver

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QUICK_RECORD = "quick_record"

        // Extras set by RecordingService when a save is triggered from the
        // notification action button. MainActivity uses these in onNewIntent
        // (app already running) and onCreate (cold start after notification
        // save, unlikely but handled) to navigate to the saved recording.
        const val EXTRA_SAVED_RECORDING_ID = "saved_recording_id"
        const val EXTRA_SAVED_TOPIC_ID     = "saved_topic_id"

        const val PAGE_SETTINGS = 0
        const val PAGE_RECORD   = 1
        const val PAGE_LIBRARY  = 2
        const val PAGE_LISTEN   = 3
        const val PAGE_WORKSPACE = 4

        /** Set as an extra on the backup notification PendingIntent. */
        const val EXTRA_NAVIGATE_TO_SETTINGS    = "navigate_to_settings"
        const val EXTRA_NAVIGATE_TO_STORAGE_TAB = "navigate_to_storage_tab"
        const val EXTRA_NAVIGATE_TO_PAGE = "navigate_to_page"
    }

    internal lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()

    private var storageVolumeEventReceiver: StorageVolumeEventReceiver? = null

    private var storageRootsObserver: StorageRootsObserver? = null

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

    internal val libraryFragment: LibraryFragment?
        get() = supportFragmentManager
            .findFragmentByTag("f${PAGE_LIBRARY.toLong()}") as? LibraryFragment
    internal val recordFragment: RecordFragment?
        get() = supportFragmentManager
            .findFragmentByTag("f${PAGE_RECORD.toLong()}") as? RecordFragment

    // Track whether we're restoring from a config change
    internal var isRestoredFromState = false

    /* ── onCreate: one-time wiring for the single-activity shell ───────────────
     *
     * SoundTree is single-activity, so this is the *only* Activity — every screen
     * (Settings, Record, Library, Listen, Workspace) is a page inside the
     * ViewPager2 built here, not a separate Activity. onCreate runs once per
     * Activity instance and does all the assembly:
     *   • inflates the ViewBinding + applies system-bar insets (edge-to-edge)
     *   • builds the pager, bottom nav, and the persistent mini-player /
     *     mini-recorder overlays that sit above whichever page is showing
     *   • wires back-stack navigation and observes lock + layout-order state
     *   • routes the launch intent (quick-record, notification-save deep links)
     *
     * What it deliberately does NOT do: run audio. Playback lives in
     * PlaybackService (reached through a MediaController in MainViewModel) and
     * recording in RecordingService. This Activity only reflects their state.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // helps track whether this is a first launch or an app reconfig launch on older android devices
        isRestoredFromState = savedInstanceState != null

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Hardware volume keys always target media playback while this Activity
        // is foreground. Without this, the platform guesses the "active" stream,
        // which fails over Bluetooth A2DP (absolute volume never gets the keypress).
        volumeControlStream = AudioManager.STREAM_MUSIC

        // ── Edge-to-edge inset handling (Android 15+ compatibility) ──────────
        // Android 15 forces all apps to draw behind system bars. We consume the
        // window insets here and apply them as padding on rootStack, which keeps
        // our title bar and bottom nav clear of the status bar and nav bar.
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootStack) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        // ─────────────────────────────────────────────────────────────────────

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
        // ── Playback notification → navigate to target page ───────────────
        val targetPage = intent.getIntExtra(EXTRA_NAVIGATE_TO_PAGE, -1)
        if (targetPage != -1) {
            binding.viewPager.setCurrentItem(targetPage, false)
        }

        checkAndShowOrphanRecovery()
    }

    override fun onStart() {
        super.onStart()
        (application as SoundTreeApp).recordAppForeground()
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

        // ContentObserver on the external storage documents provider roots URI.
        // Fires when any volume mounts or unmounts — an alternative signal path
        // that may work on GrapheneOS where ACTION_MEDIA_MOUNTED is suppressed.
        storageRootsObserver = StorageRootsObserver(this) {
            viewModel.refreshStorageVolumes()
        }.also { it.register() }
    }


    override fun onStop() {
        super.onStop()
        viewModel.onAppClose()
        storageVolumeEventReceiver?.let { unregisterReceiver(it) }
        storageVolumeEventReceiver = null
        storageRootsObserver?.unregister()
        storageRootsObserver = null
    }


    /**
     * Called when the app is already running and a new intent arrives — the
     * primary path for notification-save navigation since the back stack is
     * almost always alive when the user is actively recording.
     *
     * [android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP] on the intent from [RecordingService] ensures
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
        // ── Playback notification → navigate to target page ───────────────
        val targetPage = intent?.getIntExtra(EXTRA_NAVIGATE_TO_PAGE, -1) ?: -1
        if (targetPage != -1) {
            binding.viewPager.setCurrentItem(targetPage, false)
        }
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
        libraryFragment?.jumpToSubPageForRecording(topicId)
    }

    fun navigateToTopicDetails(topicId: Long) {
        navigateTo(PAGE_LIBRARY)
        libraryFragment?.enteredFromExternalTab = true
        libraryFragment?.openTopicDetails(topicId)
    }

    fun navigateToLibraryAll() {
        navigateTo(PAGE_LIBRARY)
        libraryFragment?.enteredFromExternalTab = true
        libraryFragment?.navigateToAll()
    }

    fun navigateToLibraryUnsorted() {
        navigateTo(PAGE_LIBRARY)
        libraryFragment?.enteredFromExternalTab = true
        libraryFragment?.navigateToUnsorted()
    }

    fun navigateToLibraryTopics() {
        navigateTo(PAGE_LIBRARY)
        libraryFragment?.enteredFromExternalTab = true
        libraryFragment?.navigateToTopics()
    }

    fun setTopTitle(title: String) {
        viewModel.setTopTitle(title)
    }
}