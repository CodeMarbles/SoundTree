package com.treecast.app.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
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
 * Horizontal carousel of tile-cards:
 *   [0] TreeViewFragment  — 🌳 Podcast Tree
 *   [1] InboxTileFragment — 📥 Inbox
 *
 * A small peek (12dp padding) hints that the cards are swipeable.
 * Dot indicators below make it unambiguous.
 */
class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var tileAdapter: LibraryTilesAdapter

    private val tileTitles = listOf("Podcast Tree", "Inbox")
    private val dotViews = mutableListOf<ImageView>()

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
        binding.tilePager.offscreenPageLimit = 3

        applyCarouselTransform()
        buildDotIndicators()

        binding.tilePager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
                val title = tileTitles.getOrElse(position) { "Library" }
                (requireActivity() as? MainActivity)?.setTopTitle(title)
            }
        })

        // Set initial state
        updateDots(0)
        (requireActivity() as? MainActivity)?.setTopTitle(tileTitles[0])
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun applyCarouselTransform() {
        val density = resources.displayMetrics.density
        // Subtle scale + alpha so adjacent card is clearly secondary
        binding.tilePager.setPageTransformer { page, position ->
            val absPos = kotlin.math.abs(position)
            page.scaleY = 1f - 0.03f * absPos
            page.alpha  = 1f - 0.12f * absPos
        }
    }

    private fun buildDotIndicators() {
        val density = resources.displayMetrics.density
        val margin = (6 * density).toInt()

        repeat(tileTitles.size) { i ->
            val dot = ImageView(requireContext()).apply {
                setImageResource(
                    if (i == 0) R.drawable.dot_indicator_active
                    else R.drawable.dot_indicator_inactive
                )
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(margin, 0, margin, 0)
                }
                layoutParams = lp
            }
            binding.pageIndicator.addView(dot)
            dotViews.add(dot)
        }
    }

    private fun updateDots(activeIndex: Int) {
        dotViews.forEachIndexed { i, dot ->
            dot.setImageResource(
                if (i == activeIndex) R.drawable.dot_indicator_active
                else R.drawable.dot_indicator_inactive
            )
        }
    }
}
