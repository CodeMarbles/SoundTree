package com.treecast.app.ui.library

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.treecast.app.ui.MainViewModel
import com.treecast.app.ui.tree.TreeViewFragment

/**
 * Adapter for the Library swipe-tile ViewPager2.
 *
 * Page 0 : TreeViewFragment  (family-tree overview)
 * Page 1 : InboxTileFragment (uncategorised recordings)
 * Page 2+ : CategoryTileFragment for each root category
 */
class LibraryTilesAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    private val pages = mutableListOf<Fragment>()

    init {
        // Always present: Tree view tile
        pages.add(TreeViewFragment())
        // Always present: Inbox tile
        pages.add(InboxTileFragment())
    }

    /**
     * Called whenever the tree changes. Rebuilds root category tiles.
     * TODO: diff instead of full rebuild for smooth animations.
     */
    fun rebuild(viewModel: MainViewModel) {
        // For now the tree view and inbox handle their own data observation.
        // Additional root category tiles could be added here dynamically.
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = pages.size
    override fun createFragment(position: Int): Fragment = pages[position]

    override fun getItemId(position: Int): Long = pages[position].hashCode().toLong()
    override fun containsItem(itemId: Long): Boolean =
        pages.any { it.hashCode().toLong() == itemId }
}
