package com.treecast.app.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.absoluteValue
import kotlin.math.sign

/**
 * Layout that correctly handles touch events for a nested ViewPager2
 * inside another ViewPager2 (or any other horizontal scrolling parent).
 *
 * Wrap the inner ViewPager2 in this view inside your layout XML.
 *
 * Based on Google's official ViewPager2 nested scrolling sample:
 * https://github.com/android/views-widgets-samples/blob/main/ViewPager2/app/src/main/java/androidx/viewpager2/integration/testapp/NestedScrollableHost.kt
 *
 * How it works:
 *  - On ACTION_DOWN, record the touch position.
 *  - On ACTION_MOVE, compare horizontal vs vertical delta.
 *  - If the gesture is more horizontal than vertical AND the inner
 *    ViewPager2 can still scroll in that direction, tell the parent
 *    to stop intercepting (disallowIntercept = true) so the inner
 *    pager handles it. Otherwise let the parent have it.
 */
class NestedScrollableHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var touchSlop = 0
    private var initialX  = 0f
    private var initialY  = 0f

    private val parentViewPager: ViewPager2?
        get() {
            var v: View? = parent as? View
            while (v != null && v !is ViewPager2) {
                v = v.parent as? View
            }
            return v as? ViewPager2
        }

    private val child: View?
        get() = if (childCount > 0) getChildAt(0) else null

    init {
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    }

    private fun canChildScroll(orientation: Int, delta: Float): Boolean {
        val direction = -delta.sign.toInt()
        return when (orientation) {
            0    -> child?.canScrollHorizontally(direction) ?: false
            1    -> child?.canScrollVertically(direction)   ?: false
            else -> throw IllegalArgumentException()
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        handleInterceptTouchEvent(e)
        return super.onInterceptTouchEvent(e)
    }

    private fun handleInterceptTouchEvent(e: MotionEvent) {
        val orientation = parentViewPager?.orientation ?: return

        // Only act if the inner pager can scroll in the same orientation as the parent
        if (!canChildScroll(orientation, -1f) && !canChildScroll(orientation, 1f)) return

        if (e.action == MotionEvent.ACTION_DOWN) {
            initialX = e.x
            initialY = e.y
            parent.requestDisallowInterceptTouchEvent(true)
        } else if (e.action == MotionEvent.ACTION_MOVE) {
            val dx = e.x - initialX
            val dy = e.y - initialY
            val isVpHorizontal = orientation == ViewPager2.ORIENTATION_HORIZONTAL

            // Check if the gesture has crossed the touch slop threshold
            val scaledDx = dx.absoluteValue * if (isVpHorizontal) 0.5f else 1f
            val scaledDy = dy.absoluteValue * if (isVpHorizontal) 1f else 0.5f

            if (scaledDx > touchSlop || scaledDy > touchSlop) {
                if (isVpHorizontal == (scaledDy > scaledDx)) {
                    // Gesture is in the parent's orientation — let parent intercept
                    parent.requestDisallowInterceptTouchEvent(false)
                } else {
                    // Gesture is in the child's orientation — child handles it
                    if (canChildScroll(orientation, if (isVpHorizontal) dx else dy)) {
                        parent.requestDisallowInterceptTouchEvent(true)
                    } else {
                        parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }
        }
    }
}
