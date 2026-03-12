package com.treecast.app.ui.library

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 *   [3] RECORDINGS   — Full topic+recording tree (original Topics view)
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

    companion object {
        const val PAGE_ALL        = 0
        const val PAGE_UNSORTED   = 1
        const val PAGE_TOPICS     = 2
        const val PAGE_RECORDINGS = 3
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
        binding.tilePager.offscreenPageLimit = 4
        binding.tilePager.isUserInputEnabled = false

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
                viewModel.libraryDetailsTopicId.collect {
                    updateSubNavSelection(binding.tilePager.currentItem)
                    // Keep the title in sync if the user is already on the Details page
                    if (binding.tilePager.currentItem == PAGE_DETAILS) {
                        updateTopTitle(PAGE_DETAILS)
                    }
                }
            }
        }

        // Start on Topics page.
        binding.tilePager.setCurrentItem(PAGE_TOPICS, false)
        updateSubNavSelection(PAGE_TOPICS)
        updateTopTitle(PAGE_TOPICS)
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Called by TopicsManageFragment when the user taps DETAILS on a topic row.
     * Sets the selected topic in the ViewModel, enables the Details tab,
     * and navigates to it.
     */
    fun openTopicDetails(topicId: Long) {
        viewModel.setLibraryDetailsTopic(topicId)
        binding.tilePager.setCurrentItem(PAGE_DETAILS, true)
    }

    /**
     * Called from TopicDetailsFragment when the user taps an ancestor in the
     * hierarchy map — navigates to that topic's details without going back
     * through the Topics tab.
     */
    fun navigateToTopicDetails(topicId: Long) {
        viewModel.setLibraryDetailsTopic(topicId)
        // Stay on PAGE_DETAILS; TopicDetailsFragment observes the ID and re-renders.
    }

    fun navigateToUnsorted() {
        binding.tilePager.setCurrentItem(PAGE_UNSORTED, true)
    }

    /**
     * Called from MainActivity after saving a recording. Navigates to whichever
     * sub-page is most relevant.
     */
    fun jumpToSubPageForRecording(topicId: Long?) {
        val page = if (topicId != null) PAGE_RECORDINGS else PAGE_UNSORTED
        binding.tilePager.setCurrentItem(page, true)
    }

    /**
     * Android back-press handler. Returns true if this fragment consumed the press.
     */
    fun handleBackPress(): Boolean {
        return when (binding.tilePager.currentItem) {
            PAGE_DETAILS -> {
                binding.tilePager.setCurrentItem(PAGE_TOPICS, true)
                true
            }
            PAGE_UNSORTED, PAGE_ALL, PAGE_RECORDINGS -> {
                binding.tilePager.setCurrentItem(PAGE_TOPICS, true)
                true
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
            PAGE_ALL        -> "Library > All"
            PAGE_UNSORTED   -> "Library > Unsorted"
            PAGE_TOPICS     -> "Library > Topics"
            PAGE_RECORDINGS -> "Library > Recordings"
            PAGE_DETAILS    -> {
                val topicId = viewModel.libraryDetailsTopicId.value
                val name = viewModel.allTopics.value
                    .firstOrNull { it.id == topicId }?.name.orEmpty()
                if (name.isNotEmpty()) "Library > Details > $name" else "Library > Details"
            }
            else -> "Library"
        }
        (requireActivity() as? MainActivity)?.setTopTitle(title)
    }

    private fun updateSubNavSelection(position: Int) {
        val accent    = requireContext().themeColor(R.attr.colorAccent)
        val dim       = requireContext().themeColor(R.attr.colorTextSecondary)
        val detailsOk = viewModel.libraryDetailsTopicId.value != null

        data class Entry(val tab: TextView, val page: Int, val enabled: Boolean = true)

        listOf(
            Entry(binding.subNavAll,        PAGE_ALL),
            Entry(binding.subNavUnsorted,   PAGE_UNSORTED),
            Entry(binding.subNavTopics,     PAGE_TOPICS),
            Entry(binding.subNavRecordings, PAGE_RECORDINGS),
            Entry(binding.subNavDetails,    PAGE_DETAILS, enabled = detailsOk)
        ).forEach { (tab, page, enabled) ->
            val isActive = page == position
            tab.setTextColor(if (isActive) accent else dim)
            tab.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
            tab.alpha = when {
                isActive  -> 1f
                !enabled  -> 0.35f
                else      -> 1f
            }
            tab.isEnabled = enabled
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}