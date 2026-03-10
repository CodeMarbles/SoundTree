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
 * Growing recording timeline for the Mini Recorder widget.
 *
 * The bar always renders as "full" — its right edge represents "now"
 * (current elapsed time). Mark dots are positioned at:
 *   cx = (markTimestampMs / elapsedMs) * width
 *
 * As [elapsedMs] grows, existing dots naturally drift leftward — their
 * proportional position shrinks — giving an honest visual of the mark
 * distribution across the whole recording so far.
 *
 * The most recently dropped mark is drawn in [colorMarkSelected] (teal)
 * rather than the default pink, and optionally shows a small timestamp
 * label floating above it. This label is the primary feedback surface
 * during mark nudging.
 *
 * When [isRecording] is true a small pulsing dot blinks at the right edge.
 * When false (paused) the pulse is hidden.
 */
class MiniRecorderTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Data ──────────────────────────────────────────────────────────

    /** List of mark timestamps in elapsed-ms. The last element is the newest mark. */
    private var markTimestamps: List<Long> = emptyList()

    /** Current recording elapsed time in ms. Controls mark dot positions. */
    private var elapsedMs: Long = 0L

    /** Whether the recording is actively running (drives pulse animation). */
    var isRecording: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startPulse() else stopPulse()
        }

    /** When true, draws a formatted timestamp label above the last mark dot. */
    var showLastMarkTimestamp: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // ── Pulse animation ───────────────────────────────────────────────

    private var pulseAlpha: Float = 1f
    private var pulseGrowing: Boolean = false

    private val pulseRunnable = object : Runnable {
        override fun run() {
            val step = 0.06f
            if (pulseGrowing) {
                pulseAlpha = (pulseAlpha + step).coerceAtMost(1f)
                if (pulseAlpha >= 1f) pulseGrowing = false
            } else {
                pulseAlpha = (pulseAlpha - step).coerceAtLeast(0.2f)
                if (pulseAlpha <= 0.2f) pulseGrowing = true
            }
            invalidate()
            postDelayed(this, 60L)
        }
    }

    private fun startPulse() { post(pulseRunnable) }
    private fun stopPulse()  { removeCallbacks(pulseRunnable); pulseAlpha = 1f; invalidate() }

    // ── Paints ────────────────────────────────────────────────────────

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorSurfaceElevated)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Use a muted version of colorRecordActive for the fill — more subtle than accent
        color = context.themeColor(R.attr.colorRecordActive).let { red ->
            // 50% alpha blended over surface for a softer look
            val r = (android.graphics.Color.red(red))
            val g = (android.graphics.Color.green(red))
            val b = (android.graphics.Color.blue(red))
            android.graphics.Color.argb(120, r, g, b)
        }
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorMarkDefault)
    }
    private val lastDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorMarkSelected)  // teal
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorRecordActive)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorMarkSelected)  // teal
        textSize = context.resources.displayMetrics.density * 9f  // 9sp
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val trackRect = RectF()

    // ── Public API ────────────────────────────────────────────────────

    fun update(elapsedMs: Long, marks: List<Long>) {
        this.elapsedMs = elapsedMs
        this.markTimestamps = marks
        invalidate()
    }

    // ── Draw ──────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val density = resources.displayMetrics.density

        // Reserve space at top for the floating timestamp label
        val labelAreaH = if (showLastMarkTimestamp) density * 14f else 0f
        val barTop    = labelAreaH + density * 3f
        val barH      = (density * 4f).coerceAtMost(h - barTop)
        val barBottom = barTop + barH
        val barRadius = barH / 2f

        // Track (background)
        trackRect.set(0f, barTop, w, barBottom)
        canvas.drawRoundRect(trackRect, barRadius, barRadius, trackPaint)

        // Fill — always full width (right edge = "now")
        if (elapsedMs > 0) {
            trackRect.set(0f, barTop, w, barBottom)
            canvas.drawRoundRect(trackRect, barRadius, barRadius, fillPaint)
        }

        // Mark dots
        val dotRadius  = barH * 1.1f
        val dotCy      = barTop + barH / 2f

        for (i in markTimestamps.indices) {
            val ts   = markTimestamps[i]
            val frac = if (elapsedMs > 0) (ts.toFloat() / elapsedMs.toFloat()).coerceIn(0f, 1f) else 0f
            val cx   = (frac * w).coerceIn(dotRadius, w - dotRadius)

            val isLast = (i == markTimestamps.lastIndex)
            val paint  = if (isLast) lastDotPaint else dotPaint
            canvas.drawCircle(cx, dotCy, dotRadius, paint)

            // Floating timestamp above last mark dot
            if (isLast && showLastMarkTimestamp && elapsedMs > 0) {
                val labelText = formatMs(ts)
                val labelY    = barTop - density * 2f
                // Nudge horizontally so label doesn't clip edges
                val labelX = cx.coerceIn(labelPaint.measureText(labelText) / 2f + density * 2f,
                    w - labelPaint.measureText(labelText) / 2f - density * 2f)
                canvas.drawText(labelText, labelX, labelY, labelPaint)
            }
        }

        // Live pulse dot at right edge
        if (isRecording && elapsedMs > 0) {
            pulsePaint.alpha = (pulseAlpha * 255).toInt()
            canvas.drawCircle(w - dotRadius, dotCy, dotRadius * 0.9f, pulsePaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopPulse()
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}