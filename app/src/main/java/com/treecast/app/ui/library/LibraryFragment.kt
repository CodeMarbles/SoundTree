package com.treecast.app.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.viewpager2.widget.ViewPager2
import com.treecast.app.R
import com.treecast.app.databinding.FragmentLibraryBinding
import com.treecast.app.ui.MainActivity
import com.treecast.app.ui.MainViewModel

/**
 * Library tab.
 *
 * Contains two sub-views managed by an internal ViewPager2:
 *   [0] TopicsFragment     — 🌳 Topics tree
 *   [1] InboxTileFragment  — 📥 Inbox (recordings with no topic)
 *
 * Swipe navigation between the sub-views is DISABLED. The user
 * navigates via the sub-nav bar at the bottom of this fragment.
 */
class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var tileAdapter: LibraryTilesAdapter

    private val PAGE_TOPICS      = 0
    private val PAGE_INBOX       = 1

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
        binding.tilePager.offscreenPageLimit = 2

        // Disable swipe inside the Library — outer ViewPager2 owns horizontal swipe.
        binding.tilePager.isUserInputEnabled = false

        // ── Sub-nav button clicks ─────────────────────────────────────
        binding.subNavTopics.setOnClickListener {
            binding.tilePager.setCurrentItem(PAGE_TOPICS, true)
        }
        binding.subNavInbox.setOnClickListener {
            binding.tilePager.setCurrentItem(PAGE_INBOX, true)
        }

        // ── Sync sub-nav selection with pager position ────────────────
        binding.tilePager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateSubNavSelection(position)
                // Always show "Library" in the top bar regardless of which sub-page is active.
                (requireActivity() as? MainActivity)?.setTopTitle("Library")
            }
        })

        // Start on Topics page.
        // Set title immediately here too — onPageSelected may not fire when the
        // initial position is already 0.
        binding.tilePager.setCurrentItem(PAGE_TOPICS, false)
        updateSubNavSelection(PAGE_TOPICS)
        (requireActivity() as? MainActivity)?.setTopTitle("Library")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Called by MainActivity back handler — returns true if we consumed the event. */
    fun handleBackPress(): Boolean {
        return if (binding.tilePager.currentItem == PAGE_INBOX) {
            binding.tilePager.setCurrentItem(PAGE_TOPICS, true)
            true
        } else {
            false
        }
    }

    private fun updateSubNavSelection(position: Int) {
        val selectedBg = ContextCompat.getColor(requireContext(), R.color.surface_light)
        val defaultBg  = android.graphics.Color.TRANSPARENT

        binding.subNavTopics.setBackgroundColor(if (position == PAGE_TOPICS) selectedBg else defaultBg)
        binding.subNavInbox.setBackgroundColor(if (position == PAGE_INBOX)  selectedBg else defaultBg)

        binding.subNavTopics.isSelected = (position == PAGE_TOPICS)
        binding.subNavInbox.isSelected  = (position == PAGE_INBOX)
    }
}