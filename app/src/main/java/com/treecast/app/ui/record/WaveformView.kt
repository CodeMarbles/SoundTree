package com.treecast.app.ui.record

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Scrolling bar-graph waveform visualizer.
 * Feed it amplitude values (0–32767) via [pushAmplitude].
 * Bars scroll leftward over time like a classic audio waveform.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6C63FF")
        style = Paint.Style.FILL
    }

    private val dimBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3D3875")
        style = Paint.Style.FILL
    }

    private val MAX_AMPLITUDE = 32767f
    private val BAR_WIDTH_DP = 4f
    private val BAR_GAP_DP = 2f
    private val CORNER_RADIUS_DP = 2f

    private val density = context.resources.displayMetrics.density
    private val barWidth = BAR_WIDTH_DP * density
    private val barGap = BAR_GAP_DP * density
    private val cornerRadius = CORNER_RADIUS_DP * density

    private val amplitudes = ArrayDeque<Float>()
    private var maxBars = 60

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        maxBars = (w / (barWidth + barGap)).toInt().coerceAtLeast(1)
    }

    /** Feed a new amplitude value (0–32767). Triggers a redraw. */
    fun pushAmplitude(amplitude: Int) {
        val normalized = amplitude.coerceIn(0, 32767) / MAX_AMPLITUDE
        amplitudes.addLast(normalized)
        while (amplitudes.size > maxBars) amplitudes.removeFirst()
        invalidate()
    }

    /** Clear the waveform (call on recording stop/reset) */
    fun clear() {
        amplitudes.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val centerY = h / 2f
        val maxBarHalf = centerY * 0.85f

        val totalBars = amplitudes.size
        var x = width - (totalBars * (barWidth + barGap))

        for ((index, amp) in amplitudes.withIndex()) {
            val barHalf = maxBarHalf * amp.coerceAtLeast(0.05f)
            val rect = RectF(x, centerY - barHalf, x + barWidth, centerY + barHalf)
            val paint = if (index == totalBars - 1) barPaint else dimBarPaint
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
            x += barWidth + barGap
        }
    }
}
