package com.treecast.app.ui.listen

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup

/**
 * A simple left-to-right wrapping layout for the mark timestamp chips.
 *
 * Children are placed in rows. When a child would exceed the available width
 * it wraps onto the next row. Both horizontal and vertical gaps are applied
 * between children. No external dependencies required.
 *
 * ── Single-row mode ──────────────────────────────────────────────────────────
 *
 * When [singleRow] is true all children are placed in a single horizontal
 * line with no wrapping. The measured width expands to fit all children so
 * that a parent [android.widget.HorizontalScrollView] can scroll the strip.
 * [singleRow] is toggled by [com.treecast.app.ui.listen.ListenFragment] when
 * the splitter snaps between its two positions.
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : ViewGroup(context, attrs, defStyle) {

    private val hGapPx = (8 * resources.displayMetrics.density).toInt()
    private val vGapPx = (8 * resources.displayMetrics.density).toInt()

    /**
     * When true, all children are laid out in a single horizontal row with no
     * wrapping. Setting this property calls [requestLayout] automatically.
     * Default is false (wrapping flow).
     */
    var singleRow: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    // ── Measure ───────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (singleRow) {
            measureSingleRow(heightMeasureSpec)
        } else {
            measureWrapping(widthMeasureSpec, heightMeasureSpec)
        }
    }

    /**
     * Single-row: report natural width (sum of all children + gaps) so that
     * HorizontalScrollView can determine the scrollable extent. Width
     * MeasureSpec may be UNSPECIFIED here — that is expected and handled.
     */
    private fun measureSingleRow(heightMeasureSpec: Int) {
        val unconstrained = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var totalWidth = paddingLeft + paddingRight
        var maxHeight  = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChild(child, unconstrained, heightMeasureSpec)
            // Add width + trailing gap for every child — consistent with
            // layoutSingleRow which also adds hGapPx after each child.
            totalWidth += child.measuredWidth + hGapPx
            maxHeight   = maxOf(maxHeight, child.measuredHeight)
        }

        val totalHeight = if (childCount == 0) 0 else maxHeight + paddingTop + paddingBottom
        setMeasuredDimension(totalWidth, totalHeight)
    }

    /**
     * Wrapping flow: children wrap when they exceed the available width
     * provided by the parent MeasureSpec. Behavior unchanged from original.
     */
    private fun measureWrapping(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val cw = child.measuredWidth
            val ch = child.measuredHeight

            if (x + cw > availableWidth - paddingRight && x > paddingLeft) {
                x = paddingLeft
                y += rowHeight + vGapPx
                rowHeight = 0
            }
            x += cw + hGapPx
            rowHeight = maxOf(rowHeight, ch)
        }

        val totalHeight = if (childCount == 0) 0 else y + rowHeight + paddingBottom
        setMeasuredDimension(availableWidth, totalHeight)
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (singleRow) {
            layoutSingleRow()
        } else {
            layoutWrapping(r - l)
        }
    }

    private fun layoutSingleRow() {
        var x = paddingLeft
        val y = paddingTop
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            child.layout(x, y, x + cw, y + ch)
            x += cw + hGapPx
        }
    }

    private fun layoutWrapping(availableWidth: Int) {
        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val cw = child.measuredWidth
            val ch = child.measuredHeight

            if (x + cw > availableWidth - paddingRight && x > paddingLeft) {
                x = paddingLeft
                y += rowHeight + vGapPx
                rowHeight = 0
            }
            child.layout(x, y, x + cw, y + ch)
            x += cw + hGapPx
            rowHeight = maxOf(rowHeight, ch)
        }
    }
}