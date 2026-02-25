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
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : ViewGroup(context, attrs, defStyle) {

    private val hGapPx = (8 * resources.displayMetrics.density).toInt()
    private val vGapPx = (8 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
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

            // Wrap if this child would overflow the row (but never wrap if we're
            // still at the start of a row — every child gets at least one row).
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

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val availableWidth = r - l
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