package com.treecast.app.ui.record

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.treecast.app.R
import com.treecast.app.util.themeColor

/**
 * Dead-space / in-development placeholder view.
 *
 * Fills its bounds with a subtle dot grid drawn at [GRID_SPACING_DP] intervals.
 * Height is controlled externally by the S / M / L buttons in RecordFragment,
 * which set [layoutParams.height] directly. This view just draws the pattern.
 *
 * The dot colour uses [colorSurfaceElevated] so the grid is legible in both
 * light and dark themes without any special configuration.
 *
 * Reference heights (set from RecordFragment):
 *   Small  — SMALL_DP  (single-row equivalent, ~52dp)
 *   Medium — MEDIUM_DP (3× small)
 *   Large  — LARGE_DP  (20× small, forces deep scroll)
 */
class DotGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val SMALL_DP  = 0        // no extra height below the button row
        const val MEDIUM_DP = 52 * 3   // 156dp
        const val LARGE_DP  = 52 * 20  // 1040dp
    }

    private val gridSpacingPx: Float
    private val dotRadiusPx: Float

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    init {
        val density = resources.displayMetrics.density
        gridSpacingPx = 18f * density
        dotRadiusPx   = 1.5f * density
        dotPaint.color = context.themeColor(R.attr.colorSurfaceElevated)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        var y = gridSpacingPx / 2f
        while (y < h) {
            var x = gridSpacingPx / 2f
            while (x < w) {
                canvas.drawCircle(x, y, dotRadiusPx, dotPaint)
                x += gridSpacingPx
            }
            y += gridSpacingPx
        }
    }
}