package com.treecast.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.treecast.app.R
import com.treecast.app.util.themeColor

/**
 * Growing recording timeline for the Mini Recorder widget.
 *
 * The bar always renders as "full" — its right edge represents "now".
 * Mark dots are positioned at (markTimestampMs / elapsedMs) * width.
 *
 * The mark at [selectedMarkIndex] is drawn in colorMarkSelected (teal) and
 * optionally shows a floating timestamp label above it.
 * All other marks draw in colorMarkDefault (pink).
 *
 * Tapping near a mark dot fires [onMarkTapped] with that mark's index.
 * The tap target is generously sized (dotRadius * 4) so small dots are
 * easy to hit.
 *
 * Bar is vertically centered in the space below the label area so it
 * aligns visually with the rec/pause and save buttons to its left.
 */
class MiniRecorderTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Data ──────────────────────────────────────────────────────────

    private var markTimestamps: List<Long> = emptyList()
    private var elapsedMs: Long = 0L

    /** Index of the mark currently selected for nudging. -1 = none selected. */
    private var selectedMarkIndex: Int = -1

    var isRecording: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startPulse() else stopPulse()
        }

    /** When true, draws a timestamp label above the selected/last mark. */
    var showLastMarkTimestamp: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Called when the user taps a mark dot. Receives the mark's index. */
    var onMarkTapped: ((index: Int) -> Unit)? = null

    // ── Cached dot positions for touch hit-testing ────────────────────

    // Populated during onDraw; used in onTouchEvent.
    private val dotCxList = mutableListOf<Float>()

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
        color = context.themeColor(R.attr.colorRecordActive).let { red ->
            val r = android.graphics.Color.red(red)
            val g = android.graphics.Color.green(red)
            val b = android.graphics.Color.blue(red)
            android.graphics.Color.argb(110, r, g, b)
        }
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorMarkDefault)
    }
    private val selectedDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorMarkSelected)  // teal
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorRecordActive)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorMarkSelected)  // teal
        textSize = context.resources.displayMetrics.density * 9f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val trackRect = RectF()

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Updates timeline data and selected mark. Call from MainActivity whenever
     * elapsed time, marks list, or selected mark index changes.
     *
     * @param elapsedMs  Current recording elapsed time.
     * @param marks      All pending mark timestamps in ms.
     * @param selectedMarkIndex  Index of the mark currently selected for nudging,
     *                           or -1 if none (which visually highlights the last mark).
     */
    fun update(elapsedMs: Long, marks: List<Long>, selectedMarkIndex: Int = -1) {
        this.elapsedMs = elapsedMs
        this.markTimestamps = marks
        this.selectedMarkIndex = selectedMarkIndex
        invalidate()
    }

    // ── Touch ─────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && markTimestamps.isNotEmpty()) {
            val touchX = event.x
            val density = resources.displayMetrics.density
            val hitSlop = density * 20f   // 20dp hit target radius — generous for small dots

            val hitIndex = dotCxList.indexOfFirst { cx -> Math.abs(cx - touchX) <= hitSlop }
            if (hitIndex >= 0) {
                onMarkTapped?.invoke(hitIndex)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ── Draw ──────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val density = resources.displayMetrics.density

        // ── Layout geometry ───────────────────────────────────────────
        // Label area sits at the top; bar is centered in the remaining space.
        val labelAreaH = if (showLastMarkTimestamp && hasActiveLabel()) density * 14f else 0f

        val barH      = (density * 4f).coerceAtMost((h - labelAreaH) * 0.35f)
        val barCy     = labelAreaH + (h - labelAreaH) / 2f          // centered below label area
        val barTop    = barCy - barH / 2f
        val barBottom = barCy + barH / 2f
        val barRadius = barH / 2f

        // ── Track ─────────────────────────────────────────────────────
        trackRect.set(0f, barTop, w, barBottom)
        canvas.drawRoundRect(trackRect, barRadius, barRadius, trackPaint)

        // ── Fill (always full width) ───────────────────────────────────
        if (elapsedMs > 0) {
            trackRect.set(0f, barTop, w, barBottom)
            canvas.drawRoundRect(trackRect, barRadius, barRadius, fillPaint)
        }

        // ── Mark dots ─────────────────────────────────────────────────
        val dotRadius = barH * 1.15f
        dotCxList.clear()

        // Resolve which index is "active" (teal). Explicit selection wins;
        val activeIndex =
            if (selectedMarkIndex >= 0 && selectedMarkIndex < markTimestamps.size)
                selectedMarkIndex
            else
                -1

        for (i in markTimestamps.indices) {
            val ts   = markTimestamps[i]
            val frac = if (elapsedMs > 0) (ts.toFloat() / elapsedMs.toFloat()).coerceIn(0f, 1f) else 0f
            val cx   = (frac * w).coerceIn(dotRadius, w - dotRadius)
            dotCxList.add(cx)

            val isActive = (i == activeIndex)
            canvas.drawCircle(cx, barCy, dotRadius, if (isActive) selectedDotPaint else dotPaint)

            // Floating timestamp above the active dot
            if (isActive && showLastMarkTimestamp) {
                val labelText = formatMs(ts)
                val textHalfW = labelPaint.measureText(labelText) / 2f
                val labelX = cx.coerceIn(textHalfW + density * 2f, w - textHalfW - density * 2f)
                // Position label so its baseline is 5dp above the dot's top edge
                val labelY = barTop - dotRadius - density * 5f
                canvas.drawText(labelText, labelX, labelY, labelPaint)
            }
        }

        // ── Live pulse dot at right edge ───────────────────────────────
        if (isRecording && elapsedMs > 0) {
            pulsePaint.alpha = (pulseAlpha * 255).toInt()
            canvas.drawCircle(w - dotRadius * 1.2f, barCy, dotRadius * 0.85f, pulsePaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopPulse()
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /** True when there is a mark to show a label for. */
    private fun hasActiveLabel(): Boolean = markTimestamps.isNotEmpty()

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}