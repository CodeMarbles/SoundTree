package app.soundtree.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup

/**
 * A simple wrapping flow layout.
 *
 * Children are placed left-to-right in a row. When the next child would exceed
 * the available width it wraps onto a new row. All children in a row share the
 * row's maximum measured height.
 *
 * No RTL support, no gravity, no item spacing attributes — kept intentionally
 * minimal for the frequent-topics lineage strip use case. If those needs arise,
 * add them here rather than reaching for a library.
 *
 * Usage in XML:
 *   <app.soundtree.ui.common.FlowLayout
 *       android:layout_width="match_parent"
 *       android:layout_height="wrap_content" />
 *
 * Children are added programmatically (buildLineageStrip / bindFrequentEntry).
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec)

        var rowWidth  = 0
        var rowHeight = 0
        var totalHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue

            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val cw = child.measuredWidth
            val ch = child.measuredHeight

            if (rowWidth + cw > maxWidth && rowWidth > 0) {
                // Wrap: commit current row and start a new one.
                totalHeight += rowHeight
                rowWidth  = cw
                rowHeight = ch
            } else {
                rowWidth  += cw
                rowHeight  = maxOf(rowHeight, ch)
            }
        }
        totalHeight += rowHeight   // commit final row

        setMeasuredDimension(
            resolveSize(maxWidth, widthMeasureSpec),
            resolveSize(totalHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l

        var rowLeft   = 0
        var rowTop    = 0
        var rowHeight = 0

        // First pass: collect children that belong to each row so we know the
        // row height before laying them out (for vertical centering within the row).
        data class RowEntry(val index: Int, val width: Int, val height: Int)
        val rows = mutableListOf<MutableList<RowEntry>>()
        var currentRow = mutableListOf<RowEntry>()
        var currentRowWidth = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val cw = child.measuredWidth
            val ch = child.measuredHeight

            if (currentRowWidth + cw > maxWidth && currentRowWidth > 0) {
                rows.add(currentRow)
                currentRow = mutableListOf()
                currentRowWidth = 0
            }
            currentRow.add(RowEntry(i, cw, ch))
            currentRowWidth += cw
        }
        if (currentRow.isNotEmpty()) rows.add(currentRow)

        // Second pass: lay out each row.
        var top = 0
        for (row in rows) {
            val rh = row.maxOf { it.height }
            var left = 0
            for (entry in row) {
                val child = getChildAt(entry.index)
                // Vertically center shorter children within the row.
                val childTop = top + (rh - entry.height) / 2
                child.layout(left, childTop, left + entry.width, childTop + entry.height)
                left += entry.width
            }
            top += rh
        }
    }
}