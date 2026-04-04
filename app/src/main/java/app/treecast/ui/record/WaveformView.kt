package app.treecast.ui.record

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import app.treecast.R
import app.treecast.util.themeColor

/**
 * Scrolling bar-graph waveform visualizer.
 * Feed it amplitude values (0–32767) via [pushAmplitude].
 * Bars scroll leftward over time like a classic audio waveform.
 *
 * Mark support:
 * Call [pushMark] when the user drops a mark. A pink vertical line with a
 * downward triangle (matching the Listen tab style) is drawn at the current
 * rightmost bar position and scrolls left naturally with the waveform until
 * it exits the left edge — giving the user immediate visual confirmation
 * that the mark was registered.
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

    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f * context.resources.displayMetrics.density
        style = Paint.Style.FILL_AND_STROKE
        color = context.themeColor(R.attr.colorMarkDefault)
    }

    private val MAX_AMPLITUDE = 32767f
    private val BAR_WIDTH_DP = 4f
    private val BAR_GAP_DP = 2f
    private val CORNER_RADIUS_DP = 2f

    private val density = context.resources.displayMetrics.density
    private val barWidth = BAR_WIDTH_DP * density
    private val barGap = BAR_GAP_DP * density
    private val cornerRadius = CORNER_RADIUS_DP * density
    private val stride get() = barWidth + barGap

    private val amplitudes = ArrayDeque<Float>()
    private var maxBars = 60

    /**
     * Ages of pending mark lines, in bar-counts from the right edge.
     *   0 = just dropped (rightmost bar)
     *   1 = one bar ago, etc.
     * Incremented on every [pushAmplitude]; entries >= [maxBars] are pruned
     * because the mark has scrolled past the left edge.
     */
    private val markAges = mutableListOf<Int>()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        maxBars = (w / stride).toInt().coerceAtLeast(1)
    }

    /** Feed a new amplitude value (0–32767). Triggers a redraw. */
    fun pushAmplitude(amplitude: Int) {
        val normalized = amplitude.coerceIn(0, 32767) / MAX_AMPLITUDE
        amplitudes.addLast(normalized)
        while (amplitudes.size > maxBars) amplitudes.removeFirst()

        // Every new bar ages all existing marks by one position to the left.
        markAges.replaceAll { it + 1 }
        markAges.removeAll { it >= maxBars }

        invalidate()
    }

    /**
     * Record a mark at the current position (rightmost bar).
     * Call this immediately after the user taps the Mark button.
     */
    fun pushMark() {
        markAges.add(0)
        invalidate()
    }

    /** Clear the waveform and all mark lines (call on recording stop/reset). */
    fun clear() {
        amplitudes.clear()
        markAges.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val centerY = h / 2f
        val maxBarHalf = centerY * 0.85f

        val totalBars = amplitudes.size
        var x = width - (totalBars * stride)

        // ── Waveform bars ─────────────────────────────────────────────
        for ((index, amp) in amplitudes.withIndex()) {
            val barHalf = maxBarHalf * amp.coerceAtLeast(0.05f)
            val rect = RectF(x, centerY - barHalf, x + barWidth, centerY + barHalf)
            val paint = if (index == totalBars - 1) barPaint else dimBarPaint
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
            x += stride
        }

        // ── Mark lines + triangles ────────────────────────────────────
        // A mark at age `a` corresponds to the bar `a` positions from the
        // right edge. Center X of that bar = width - stride*a - barWidth/2.
        val triSize   = 6f * density
        val triHeight = 5f * density
        val markPath  = Path()

        for (age in markAges) {
            val markX = width - stride * age - barWidth / 2f
            if (markX < 0f) continue

            canvas.drawLine(markX, 0f, markX, h, markPaint)

            markPath.rewind()
            markPath.moveTo(markX - triSize, 0f)
            markPath.lineTo(markX + triSize, 0f)
            markPath.lineTo(markX, triHeight)
            markPath.close()
            canvas.drawPath(markPath, markPaint)
        }
    }
}