package com.treecast.app.ui.library

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.treecast.app.R
import com.treecast.app.databinding.FragmentLibraryBinding
import com.treecast.app.ui.MainActivity
import com.treecast.app.ui.MainViewModel
import com.treecast.app.util.themeColor
import kotlinx.coroutines.launch


/**
 * Library tab — hosts 5 sub-pages via an internal ViewPager2.
 *
 *   [0] ALL          — Flat chronological list of all recordings
 *   [1] UNSORTED     — Recordings with no topic (Inbox)
 *   [2] TOPICS       — Topic tree management (no recordings inline)
 *   [3] ORGANIZE     — Deprecated Recordings view; now a blank placeholder
 *   [4] DETAILS      — Details page for a selected topic
 *
 * Swipe between sub-pages is DISABLED — the outer ViewPager2 owns horizontal swipe.
 * The DETAILS tab is greyed out and non-navigable until a topic has been selected
 * via [openTopicDetails].
 */
class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var tileAdapter: LibraryTilesAdapter

    private val topicNavHistory = ArrayDeque<Long>()

    var enteredFromExternalTab: Boolean = false

    companion object {
        const val PAGE_ALL        = 0
        const val PAGE_UNSORTED   = 1
        const val PAGE_TOPICS     = 2
        const val PAGE_RECORDINGS = 3   // kept for back-compat; now shows Organize placeholder
        const val PAGE_DETAILS    = 4
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tileAdapter = LibraryTilesAdapter(this)
        binding.tilePager.adapter = tileAdapter
        binding.tilePager.isUserInputEnabled = false
        binding.tilePager.offscreenPageLimit = 4

        // ── Sub-nav clicks ────────────────────────────────────────────
        binding.subNavAll.setOnClickListener {
            binding.tilePager.setCurrentItem(PAGE_ALL, true)
        }
        binding.subNavUnsorted.setOnClickListener {
            binding.tilePager.setCurrentItem(PAGE_UNSORTED, true)
        }
        binding.subNavTopics.setOnClickListener {
            binding.tilePager.setCurrentItem(PAGE_TOPICS, true)
        }
        binding.subNavRecordings.setOnClickListener {
            binding.tilePager.setCurrentItem(PAGE_RECORDINGS, true)
        }
        binding.subNavDetails.setOnClickListener {
            if (viewModel.libraryDetailsTopicId.value != null) {
                binding.tilePager.setCurrentItem(PAGE_DETAILS, true)
            }
        }

        // ── Page change callback ──────────────────────────────────────
        binding.tilePager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateSubNavSelection(position)
                updateTopTitle(position)
            }
        })

        // ── React to Details topic changing (enables the tab) ─────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.libraryDetailsTopicId.collect {
                        updateSubNavSelection(binding.tilePager.currentItem)
                        if (binding.tilePager.currentItem == PAGE_DETAILS) {
                            updateTopTitle(PAGE_DETAILS)
                        }
                    }
                }

                launch {
                    viewModel.futureMode.collect { enabled ->
                        binding.subNavRecordings.visibility      = if (enabled) View.VISIBLE else View.GONE
                        binding.dividerBeforeOrganize.visibility = if (enabled) View.VISIBLE else View.GONE
                        if (!enabled && binding.tilePager.currentItem == PAGE_RECORDINGS) {
                            binding.tilePager.setCurrentItem(PAGE_ALL, true)
                        }
                    }
                }

            }
        }

        // Start on ALL page
        binding.tilePager.setCurrentItem(PAGE_ALL, false)
        updateSubNavSelection(PAGE_ALL)
        updateTopTitle(PAGE_ALL)
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Called by TopicsManageFragment when the user taps DETAILS on a topic row.
     * Sets the selected topic in the ViewModel, enables the Details tab,
     * and navigates to it.
     */
    fun openTopicDetails(topicId: Long) {
        topicNavHistory.clear()  // ← fresh entry from Topics tab resets history
        viewModel.setLibraryDetailsTopic(topicId)
        binding.tilePager.setCurrentItem(PAGE_DETAILS, true)
    }

    /**
     * Called from TopicDetailsFragment when the user taps an ancestor in the
     * hierarchy map — navigates to that topic's details without going back
     * through the Topics tab.
     */
    fun navigateToTopicDetails(topicId: Long) {
        viewModel.libraryDetailsTopicId.value?.let { topicNavHistory.addLast(it) }
        viewModel.setLibraryDetailsTopic(topicId)
        // Stay on PAGE_DETAILS; TopicDetailsFragment observes the ID and re-renders.
    }

    fun navigateToUnsorted() {
        binding.tilePager.setCurrentItem(PAGE_UNSORTED, true)
    }

    /**
     * Called from MainActivity after saving a recording.
     *
     * If the recording has a topic: sets the Details topic in the ViewModel and
     * navigates to PAGE_DETAILS so the user lands on that topic's detail page
     * with the new recording highlighted.
     *
     * If the recording is unsorted (topicId == null): navigates to PAGE_UNSORTED.
     *
     * The recording selection (viewModel.selectRecording) is always called by the
     * caller before this, so the adapter highlight will be set automatically.
     */
    fun jumpToSubPageForRecording(topicId: Long?) {
        if (topicId != null) {
            viewModel.setLibraryDetailsTopic(topicId)
            binding.tilePager.setCurrentItem(PAGE_DETAILS, true)
        } else {
            binding.tilePager.setCurrentItem(PAGE_UNSORTED, true)
        }
    }

    /**
     * Android back-press handler. Returns true if this fragment consumed the press.
     */
    fun handleBackPress(): Boolean {
        return when (binding.tilePager.currentItem) {
            PAGE_DETAILS -> {
                when {
                    topicNavHistory.isNotEmpty() -> {
                        // Walk back through in-Library topic navigation first.
                        viewModel.setLibraryDetailsTopic(topicNavHistory.removeLast())
                        true
                    }
                    enteredFromExternalTab -> {
                        // Came from outside Library — let MainActivity pop back
                        // to the originating tab rather than going to Topics.
                        enteredFromExternalTab = false
                        false
                    }
                    else -> {
                        binding.tilePager.setCurrentItem(PAGE_TOPICS, true)
                        true
                    }
                }
            }
            PAGE_UNSORTED, PAGE_ALL, PAGE_RECORDINGS -> {
                if (enteredFromExternalTab) {
                    // Same — yield to MainActivity rather than routing to Topics.
                    enteredFromExternalTab = false
                    false
                } else {
                    binding.tilePager.setCurrentItem(PAGE_TOPICS, true)
                    true
                }
            }
            else -> false
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────

    /**
     * Called by MainActivity when the outer ViewPager2 swipes to the Library
     * tab, so the title reflects whichever inner sub-page is currently visible
     * rather than staying frozen on the previous tab's title.
     */
    fun refreshTopTitle() {
        if (_binding == null) return
        updateTopTitle(binding.tilePager.currentItem)
    }

    /**
     * Computes the breadcrumb title for [position] and pushes it to the
     * activity's top title bar.
     *
     *   Topics      → "Library > Topics"
     *   Details     → "Library > Details > My Topic Name"
     *   etc.
     */
    private fun updateTopTitle(position: Int) {
        val title = when (position) {
            PAGE_ALL        -> getString(R.string.library_title_all)
            PAGE_UNSORTED   -> getString(R.string.library_title_unsorted)
            PAGE_TOPICS     -> getString(R.string.library_title_topics)
            PAGE_RECORDINGS -> getString(R.string.library_title_organize)
            PAGE_DETAILS    -> {
                val topicId = viewModel.libraryDetailsTopicId.value
                val name = viewModel.allTopics.value
                    .firstOrNull { it.id == topicId }?.name.orEmpty()
                if (name.isNotEmpty()) getString(R.string.library_title_details_topic, name)
                else getString(R.string.library_title_details)
            }
            else -> getString(R.string.library_title_root)
        }
        (requireActivity() as? MainActivity)?.setTopTitle(title)
    }

    private fun updateSubNavSelection(position: Int) {
        val onActive       = requireContext().themeColor(R.attr.colorTextPrimary)
        val inactive       = requireContext().themeColor(R.attr.colorTextSecondary)
        val pillColor      = requireContext().themeColor(R.attr.colorSurfaceElevated)
        val radius         = 10f * resources.displayMetrics.density  // tighter than bottom nav's 20dp
        val detailsEnabled = viewModel.libraryDetailsTopicId.value != null

        val overshoot  = OvershootInterpolator(1.6f)  // slightly softer than bottom nav
        val decelerate = DecelerateInterpolator()

        fun style(tv: TextView, active: Boolean, enabled: Boolean = true) {
            // ── Background pill ───────────────────────────────────────────
            tv.background = if (active) {
                android.graphics.drawable.GradientDrawable().apply {
                    shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = radius
                    setColor(pillColor)
                }
            } else null

            // ── Text color + weight ───────────────────────────────────────
            tv.setTextColor(if (active) onActive else inactive)
            tv.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            tv.alpha = when {
                active  -> 1f
                enabled -> 0.7f
                else    -> 0.35f
            }

            // ── Scale bounce ──────────────────────────────────────────────
            // Slightly more restrained than the bottom nav (1.08/0.95 vs 1.13/0.93)
            // — text-only tabs don't need as much lift to read clearly.
            val targetScale = if (active) 1.08f else 0.95f
            val interp      = if (active) overshoot else decelerate

            tv.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(200)
                .setInterpolator(interp)
                .start()
        }

        style(binding.subNavAll,        position == PAGE_ALL)
        style(binding.subNavUnsorted,   position == PAGE_UNSORTED)
        style(binding.subNavTopics,     position == PAGE_TOPICS)
        style(binding.subNavRecordings, position == PAGE_RECORDINGS)
        style(binding.subNavDetails,    position == PAGE_DETAILS, enabled = detailsEnabled)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}