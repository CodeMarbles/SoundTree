package com.treecast.app.ui.listen

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.treecast.app.R
import com.treecast.app.util.themeColor
import kotlin.math.abs
import kotlin.math.sin

/**
 * Decorative waveform for the Listen tab.
 *
 * Bars left of the playhead are accent-gradient coloured; right are dim.
 * Mark positions are drawn as dark-pink vertical lines with a small
 * downward-pointing triangle at the top.
 * The selected mark is drawn in teal instead of pink to distinguish it clearly.
 */
class PlaybackWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density

    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val unplayedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3 * density
        style = Paint.Style.STROKE
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f * density
        style = Paint.Style.FILL_AND_STROKE
    }
    private val markPaintSelected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f * density
        style = Paint.Style.FILL_AND_STROKE
    }

    init {
        //unplayedPaint.color  = context.themeColor(R.attr.colorWaveformUnplayed)
        val base = context.themeColor(R.attr.colorWaveformUnplayed)
        unplayedPaint.color = android.graphics.Color.argb(
            0x88,
            android.graphics.Color.red(base),
            android.graphics.Color.green(base),
            android.graphics.Color.blue(base)
        )

        markPaint.color      = context.themeColor(R.attr.colorMarkDefault)
        markPaintSelected.color = context.themeColor(R.attr.colorMarkSelected)
        playheadPaint.color = context.themeColor(R.attr.colorPlayhead)
    }

    private val barWidthDp = 3f
    private val barGapDp   = 2f
    private val cornerDp   = 1.5f

    private var progressFraction = 0f
    private var amplitudes: FloatArray? = null
    private var seed: Long = 42L

    /** List of (fraction 0..1, markId) for all marks on this recording */
    private var markFractions: List<Pair<Float, Long>> = emptyList()
    private var selectedMarkId: Long? = null

    fun setProgress(fraction: Float) { progressFraction = fraction.coerceIn(0f, 1f); invalidate() }

    fun setSeed(recordingId: Long) { seed = recordingId; amplitudes = null; invalidate() }

    fun setAmplitudes(amps: FloatArray) { amplitudes = amps; invalidate() }

    fun setMarks(marks: List<Pair<Float, Long>>) { markFractions = marks; invalidate() }

    fun setSelectedMark(id: Long?) { selectedMarkId = id; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            playedPaint.shader = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                intArrayOf(
                    context.themeColor(R.attr.colorWaveformGradientStart),
                    context.themeColor(R.attr.colorWaveformGradientMid),
                    context.themeColor(R.attr.colorWaveformGradientEnd)
                ),
                null, Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val bw       = barWidthDp * density
        val bg       = barGapDp   * density
        val cr       = cornerDp   * density
        val stride   = bw + bg
        val barCount = (width / stride).toInt().coerceAtLeast(1)
        val centerY  = height / 2f
        val maxHalf  = centerY * 0.88f
        val playheadX = progressFraction * width

        val amps = amplitudes ?: generateAmplitudes(barCount)
        val rect = RectF()

        // ── Waveform bars ──────────────────────────────────────────
        for (i in 0 until barCount) {
            val x   = i * stride
            val idx = (i.toFloat() / barCount * amps.size).toInt().coerceIn(0, amps.size - 1)
            val half = maxHalf * amps[idx].coerceAtLeast(0.07f)
            rect.set(x, centerY - half, x + bw, centerY + half)
            canvas.drawRoundRect(rect, cr, cr, if (x <= playheadX) playedPaint else unplayedPaint)
        }

        // ── Playhead ───────────────────────────────────────────────
        canvas.drawLine(playheadX, 0f, playheadX, height.toFloat(), playheadPaint)

        // ── Mark lines + triangles ─────────────────────────────────
        // Draw non-selected marks first so the selected one renders on top.
        val triSize   = 7f * density
        val triHeight = 6f * density

        // Pass 1: unselected
        for ((fraction, markId) in markFractions) {
            if (markId == selectedMarkId) continue
            val x = fraction * width
            canvas.drawLine(x, 0f, x, height.toFloat(), markPaint)
            val path = Path().apply {
                moveTo(x - triSize, 0f); lineTo(x + triSize, 0f); lineTo(x, triHeight); close()
            }
            canvas.drawPath(path, markPaint)
        }

        // Pass 2: selected (drawn on top in teal)
        for ((fraction, markId) in markFractions) {
            if (markId != selectedMarkId) continue
            val x = fraction * width
            canvas.drawLine(x, 0f, x, height.toFloat(), markPaintSelected)
            val path = Path().apply {
                moveTo(x - triSize, 0f); lineTo(x + triSize, 0f); lineTo(x, triHeight); close()
            }
            canvas.drawPath(path, markPaintSelected)
        }
    }

    private fun generateAmplitudes(count: Int): FloatArray {
        val result = FloatArray(count)
        var s = seed xor (seed shl 17)
        for (i in 0 until count) {
            s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17)
            val base = (abs(s.toFloat()) % 1000f) / 1000f
            val env  = 0.3f + 0.7f * sin(Math.PI * i / count).toFloat()
            result[i] = (base * env).coerceIn(0.08f, 1f)
        }
        return result
    }
}