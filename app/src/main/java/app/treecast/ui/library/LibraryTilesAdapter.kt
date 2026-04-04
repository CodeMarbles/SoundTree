package app.treecast.ui.library

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import app.treecast.ui.library.details.TopicDetailsFragment
import app.treecast.ui.library.manage.TopicsManageFragment
import app.treecast.ui.library.all.AllRecordingsFragment
// TopicsFragment is intentionally NOT imported here — it's preserved but unused
// while the Organize tab shows a blank placeholder.  Re-import and swap
// OrganizeFragment below back to TopicsFragment() to restore the old view.

/**
 * Adapter for the 5-page Library ViewPager2.
 *
 *   [0] AllRecordingsFragment  — flat chronological list of all recordings
 *   [1] UnsortedTileFragment      — unsorted / inbox recordings
 *   [2] TopicsManageFragment   — topic tree management (no recordings inline)
 *   [3] OrganizeFragment       — blank placeholder (formerly TopicsFragment)
 *   [4] TopicDetailsFragment   — details page for a selected topic
 */
class LibraryTilesAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    private val pages = listOf(
        AllRecordingsFragment(),
        UnsortedTileFragment(),
        TopicsManageFragment(),
        OrganizeFragment(),          // was TopicsFragment() — code preserved in TopicsFragment.kt
        TopicDetailsFragment()
    )

    override fun getItemCount(): Int = pages.size
    override fun createFragment(position: Int): Fragment = pages[position]

    override fun getItemId(position: Int): Long = pages[position].hashCode().toLong()
    override fun containsItem(itemId: Long): Boolean =
        pages.any { it.hashCode().toLong() == itemId }
}