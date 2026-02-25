package com.treecast.app.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 *   [0] TreeViewFragment  — 💭 Topics
 *   [1] InboxTileFragment — 📥 Uncategorized (Inbox)
 *
 * Swipe navigation between the sub-views is DISABLED. The user
 * navigates via the sub-nav bar at the bottom of this fragment.
 * This means the outer ViewPager2 (Listen ↔ Library ↔ Record) owns
 * all horizontal swipe gestures when the Library tab is active —
 * no special intercept is required in MainActivity.
 *
 * The sub-nav bar mirrors the style of the main bottom nav and has
 * room on its right side for a future feature.
 */
class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var tileAdapter: LibraryTilesAdapter

    // PAGE_* constants mirror the adapter order
    private val PAGE_TREE          = 0
    private val PAGE_UNCATEGORIZED = 1

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

        // ── Disable swipe inside the Library ──────────────────────────
        // The outer ViewPager2 now owns horizontal swipe while on this tab.
        binding.tilePager.isUserInputEnabled = false

        // ── Sub-nav button clicks ─────────────────────────────────────
        binding.subNavTreeView.setOnClickListener {
            binding.tilePager.setCurrentItem(PAGE_TREE, true)
        }
        binding.subNavUncategorized.setOnClickListener {
            binding.tilePager.setCurrentItem(PAGE_UNCATEGORIZED, true)
        }

        // ── Sync sub-nav selection with pager position ────────────────
        binding.tilePager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateSubNavSelection(position)
                // Title stays "Library" regardless of sub-page — no update here.
            }
        })

        // Initial state — Topics selected, top title fixed to Library
        updateSubNavSelection(PAGE_TREE)
        (requireActivity() as? MainActivity)?.setTopTitle("Library")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Back press handling ───────────────────────────────────────────

    /**
     * Called by MainActivity when the system back button is pressed while
     * the Library tab is active.
     *
     * Returns true if the back press was consumed here (i.e. we navigated
     * from Uncategorized → Topics), or false if MainActivity should
     * continue with its own back-stack logic (leaving the Library tab).
     */
    fun handleBackPress(): Boolean {
        if (!isAdded || _binding == null) return false
        return if (binding.tilePager.currentItem == PAGE_UNCATEGORIZED) {
            binding.tilePager.setCurrentItem(PAGE_TREE, true)
            true
        } else {
            false
        }
    }

    // ── Sub-nav visual state ──────────────────────────────────────────

    /**
     * Highlights the active button container at full opacity; dims the
     * inactive one to 0.40 alpha — matching the treatment used in the
     * main bottom nav bar.
     *
     * Alphaing the parent LinearLayout covers both the emoji and the
     * label in one shot, with no ImageView tinting needed.
     */
    private fun updateSubNavSelection(activePosition: Int) {
        binding.subNavTreeView.alpha      = if (activePosition == PAGE_TREE)          1f else 0.40f
        binding.subNavUncategorized.alpha = if (activePosition == PAGE_UNCATEGORIZED) 1f else 0.40f
    }
}