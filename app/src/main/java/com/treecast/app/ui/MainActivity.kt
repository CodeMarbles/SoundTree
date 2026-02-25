package com.treecast.app.ui

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.onAppOpen()

        setupViewPager()
        setupBottomNav()
        setupMiniPlayer()
        observeLockState()
        observeTopTitle()

        if (intent.getBooleanExtra(EXTRA_QUICK_RECORD, false)) {
            binding.viewPager.currentItem = PAGE_RECORD
        }
    }

    // ── ViewPager ─────────────────────────────────────────────────────
    private fun setupViewPager() {
        val adapter = MainPagerAdapter(this).apply {
            addFragment(SettingsFragment(), "Settings")
            addFragment(RecordFragment(),   "Record")
            addFragment(LibraryFragment(),  "Library")
            addFragment(ListenFragment(),   "Listen")
        }
        binding.viewPager.adapter = adapter
        binding.viewPager.offscreenPageLimit = 4
        binding.viewPager.setCurrentItem(PAGE_RECORD, false)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // The inner Library tile-pager no longer claims swipe gestures,
                // so the outer ViewPager2 stays enabled on every tab —
                // no isUserInputEnabled intercept needed for PAGE_LIBRARY.
                updateBottomNavSelection(position)
                if (position != PAGE_LIBRARY) {
                    viewModel.setTopTitle(pageTitles[position] ?: "")
                }
                // When on Library, LibraryFragment drives the top title itself.
            }
        })
    }

    // ── Bottom nav ────────────────────────────────────────────────────
    private fun setupBottomNav() {
        binding.navSettings.setOnClickListener { binding.viewPager.currentItem = PAGE_SETTINGS }
        binding.navRecord.setOnClickListener   { binding.viewPager.currentItem = PAGE_RECORD   }
        binding.navLibrary.setOnClickListener  { binding.viewPager.currentItem = PAGE_LIBRARY  }
        binding.navListen.setOnClickListener   { binding.viewPager.currentItem = PAGE_LISTEN   }
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

    // ── Mini Player ───────────────────────────────────────────────────
    private fun setupMiniPlayer() {
        // Show/hide and update content based on nowPlaying state
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

        // Controls
        binding.miniPlayer.btnMiniPlayPause.setOnClickListener {
            viewModel.togglePlayPause()
        }
        binding.miniPlayer.btnMiniSkipBack.setOnClickListener {
            viewModel.skipBack15()
        }
        binding.miniPlayer.btnMiniSkipForward.setOnClickListener {
            viewModel.skipForward15()
        }
        // Tap anywhere else on the mini player → go to Listen tab
        binding.miniPlayer.root.setOnClickListener {
            binding.viewPager.currentItem = PAGE_LISTEN
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

    fun setTopTitle(title: String) { viewModel.setTopTitle(title) }

    /** Navigate to any top-level page from anywhere in the app. */
    fun navigateTo(page: Int) { binding.viewPager.currentItem = page }

    // ── Lock ──────────────────────────────────────────────────────────
    private fun observeLockState() {
        lifecycleScope.launch {
            viewModel.isLocked.collect { locked ->
                binding.lockOverlay.visibility = if (locked) View.VISIBLE else View.GONE
            }
        }
    }

    fun onUnlockClicked(@Suppress("UNUSED_PARAMETER") v: View) {
        viewModel.setLocked(false)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (viewModel.isLocked.value) {
            val btn = binding.btnUnlock
            val loc = IntArray(2); btn.getLocationOnScreen(loc)
            val inBounds = ev.rawX >= loc[0] && ev.rawX <= loc[0] + btn.width &&
                    ev.rawY >= loc[1] && ev.rawY <= loc[1] + btn.height
            return if (inBounds) super.dispatchTouchEvent(ev) else true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onStop() {
        super.onStop()
        viewModel.onAppClose()
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}