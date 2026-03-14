package com.treecast.app.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

/**
 * ORGANIZE tab — placeholder for the deprecated Recordings view.
 *
 * The old [com.treecast.app.ui.topics.TopicsFragment] code is preserved but no
 * longer shown here. This blank fragment occupies PAGE_RECORDINGS (index 3) in
 * the Library ViewPager2 while the Organize feature is being designed.
 *
 * To restore the old view, swap this back to TopicsFragment in
 * [LibraryTilesAdapter] and update the tab label in fragment_library.xml.
 */
class OrganizeFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = View(requireContext())   // completely blank view
}