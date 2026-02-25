package com.treecast.app.ui.library

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.treecast.app.ui.MainViewModel
import com.treecast.app.ui.topics.TopicsFragment

/**
 * Adapter for the Library swipe-tile ViewPager2.
 *
 * Page 0 : TopicsFragment    (hierarchical topics overview)
 * Page 1 : InboxTileFragment (recordings with no topic assigned)
 */
class LibraryTilesAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    private val pages = mutableListOf<Fragment>()

    init {
        pages.add(TopicsFragment())
        pages.add(InboxTileFragment())
    }

    /**
     * Called whenever the tree changes. Rebuilds tiles.
     * TODO: diff instead of full rebuild for smooth animations.
     */
    fun rebuild(viewModel: MainViewModel) {
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = pages.size
    override fun createFragment(position: Int): Fragment = pages[position]

    override fun getItemId(position: Int): Long = pages[position].hashCode().toLong()
    override fun containsItem(itemId: Long): Boolean =
        pages.any { it.hashCode().toLong() == itemId }
}