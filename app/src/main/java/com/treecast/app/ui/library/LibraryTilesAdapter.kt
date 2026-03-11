package com.treecast.app.ui.library

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.treecast.app.ui.library.details.TopicDetailsFragment
import com.treecast.app.ui.library.manage.TopicsManageFragment
import com.treecast.app.ui.library.all.AllRecordingsFragment
import com.treecast.app.ui.topics.TopicsFragment

/**
 * Adapter for the 5-page Library ViewPager2.
 *
 *   [0] AllRecordingsFragment  — flat chronological list of all recordings
 *   [1] InboxTileFragment      — unsorted / inbox recordings
 *   [2] TopicsManageFragment   — topic tree management (no recordings inline)
 *   [3] TopicsFragment         — full topic + recording tree (Recordings tab)
 *   [4] TopicDetailsFragment   — details page for a selected topic
 */
class LibraryTilesAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    private val pages = listOf(
        AllRecordingsFragment(),
        InboxTileFragment(),
        TopicsManageFragment(),
        TopicsFragment(),
        TopicDetailsFragment()
    )

    override fun getItemCount(): Int = pages.size
    override fun createFragment(position: Int): Fragment = pages[position]

    override fun getItemId(position: Int): Long = pages[position].hashCode().toLong()
    override fun containsItem(itemId: Long): Boolean =
        pages.any { it.hashCode().toLong() == itemId }
}