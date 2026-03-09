package com.treecast.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.treecast.app.R
import com.treecast.app.util.themeColor

/**
 * A slim horizontal progress bar that also draws small dots at mark positions.
 * Drop-in replacement for the ProgressBar in view_mini_player.xml.
 *
 * Usage:
 *   view.setProgress(frac)          // 0..1000, same scale as old ProgressBar.max
 *   view.setMarkFractions(fracs)    // 0f..1f for each mark dot
 */
class MiniMarkProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progress = 0        // 0..1000
    private var markFractions = emptyList<Float>()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorSurfaceElevated)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorAccent)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorMarkDefault)
    }

    private val trackRect = RectF()

    fun setProgress(value: Int) {
        progress = value.coerceIn(0, 1000)
        invalidate()
    }

    /** @param fracs each value is a fraction 0f..1f of total duration */
    fun setMarkFractions(fracs: List<Float>) {
        markFractions = fracs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2f

        // Track
        trackRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        // Fill
        val fillW = w * (progress / 1000f)
        trackRect.set(0f, 0f, fillW, h)
        canvas.drawRoundRect(trackRect, radius, radius, fillPaint)

        // Mark dots — drawn as small circles centered on the track
        val dotRadius = h * 0.9f          // slightly taller than the bar
        val dotCy = h / 2f
        for (frac in markFractions) {
            val cx = (frac * w).coerceIn(dotRadius, w - dotRadius)
            canvas.drawCircle(cx, dotCy, dotRadius, dotPaint)
        }
    }
}