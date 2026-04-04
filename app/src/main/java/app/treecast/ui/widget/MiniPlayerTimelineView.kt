package app.treecast.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import app.treecast.R
import app.treecast.util.themeColor
import kotlin.math.abs

/**
 * Playback timeline for the Mini Player widget.
 *
 * Mirrors [MiniRecorderTimelineView] in look and feel, but adapted for
 * fixed-duration playback rather than a growing recording bar:
 *
 *  - The track is always full-width (total duration = full bar).
 *  - A semi-transparent accent fill shows how much has been played.
 *  - A solid accent playhead dot rides on top of the fill edge.
 *  - Mark lines sit at their fraction of total duration.
 *  - The selected mark is drawn in colorMarkSelected (teal); all others
 *    in colorMarkDefault (pink).
 *  - A floating timestamp label sits above the selected mark, always
 *    visible when a mark is selected (no config toggle needed).
 *
 * Touch behaviour:
 *   - Tap within 20dp of a mark line → [onMarkTapped] fires with the mark's DB id.
 *   - Tap anywhere else              → [onSeekRequested] fires with a 0f..1f fraction.
 *
 * Public API (call from MainActivity):
 *   timeline.setProgress(fraction)            // 0f..1f, called on playback tick
 *   timeline.setMarks(fracs, ids)             // parallel lists, called on marks Flow emit
 *   timeline.setSelectedMark(id, timestampMs) // called when selectedMarkId changes
 */
class MiniPlayerTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Data ──────────────────────────────────────────────────────────

    private var progressFraction: Float = 0f
    private var markFractions: List<Float> = emptyList()
    private var markIds: List<Long> = emptyList()
    private var selectedMarkId: Long? = null
    private var selectedMarkMs: Long? = null

    // ── Callbacks ─────────────────────────────────────────────────────

    /** Fired when the user taps near a mark line. Argument = mark's DB id. */
    var onMarkTapped: ((id: Long) -> Unit)? = null

    /** Fired when the user taps an empty area. Argument = 0f..1f seek fraction. */
    var onSeekRequested: ((fraction: Float) -> Unit)? = null

    // ── Cached mark list for hit-testing (populated in onDraw) ────────

    private data class MarkInfo(val cx: Float, val id: Long)
    private val markList = mutableListOf<MarkInfo>()

    // ── Paints ────────────────────────────────────────────────────────

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorSurfaceElevated)
    }

    /** Semi-transparent accent fill for the played portion. */
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val accent = context.themeColor(R.attr.colorAccent)
        color = android.graphics.Color.argb(
            120,
            android.graphics.Color.red(accent),
            android.graphics.Color.green(accent),
            android.graphics.Color.blue(accent)
        )
    }

    /** Solid accent dot marking the current playhead position. */
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorAccent)
    }

    private val dotDefaultPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorMarkDefault)
    }

    private val dotSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorMarkSelected)   // teal
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorMarkSelected)   // teal
        textSize = context.resources.displayMetrics.density * 9f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val trackRect = RectF()
    private val markRect  = RectF()

    // ── Public API ────────────────────────────────────────────────────

    /** Update playback progress. fraction = 0f..1f. Call on every tick. */
    fun setProgress(fraction: Float) {
        progressFraction = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    /**
     * Update the mark list. Call whenever the marks Flow emits.
     * [fracs] and [ids] must be parallel — same indices, same length.
     */
    fun setMarks(fracs: List<Float>, ids: List<Long>) {
        markFractions = fracs
        markIds = ids
        invalidate()
    }

    /**
     * Update which mark is selected (for teal highlight + timestamp label).
     * [id] = null clears the selection. [timestampMs] = position in ms.
     */
    fun setSelectedMark(id: Long?, timestampMs: Long?) {
        selectedMarkId = id
        selectedMarkMs = timestampMs
        invalidate()
    }

    // ── Touch ─────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Consume all events so parent doesn't also get them (prevents
        // accidentally navigating to Listen tab on a timeline tap).
        if (event.action != MotionEvent.ACTION_UP) return true

        val touchX  = event.x
        val hitSlop = resources.displayMetrics.density * 20f

        val hit = markList.minByOrNull { abs(it.cx - touchX) }
        if (hit != null && abs(hit.cx - touchX) <= hitSlop) {
            onMarkTapped?.invoke(hit.id)
        } else {
            val fraction = (touchX / width.toFloat()).coerceIn(0f, 1f)
            onSeekRequested?.invoke(fraction)
        }
        return true
    }

    // ── Draw ──────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w       = width.toFloat()
        val h       = height.toFloat()
        val density = resources.displayMetrics.density

        // ── Geometry ──────────────────────────────────────────────────
        // Reserve space at the top for the floating timestamp label when
        // a mark is selected; bar is vertically centred in the remaining space.
        val hasLabel   = selectedMarkMs != null && selectedMarkId != null
        val labelAreaH = if (hasLabel) density * 13f else 0f

        val barH      = (density * 4f).coerceAtMost((h - labelAreaH) * 0.45f)
        val barCy     = labelAreaH + (h - labelAreaH) / 2f
        val barTop    = barCy - barH / 2f
        val barBottom = barCy + barH / 2f
        val barRadius = barH / 2f

        // ── Track ─────────────────────────────────────────────────────
        trackRect.set(0f, barTop, w, barBottom)
        canvas.drawRoundRect(trackRect, barRadius, barRadius, trackPaint)

        // ── Played fill ───────────────────────────────────────────────
        val fillW = progressFraction * w
        if (fillW > 0f) {
            trackRect.set(0f, barTop, fillW, barBottom)
            canvas.drawRoundRect(trackRect, barRadius, barRadius, fillPaint)
        }

        // ── Mark lines ────────────────────────────────────────────────
        markList.clear()
        val mark = TimelineMarkStyle.compute(density, barH)

        for (i in markFractions.indices) {
            val frac  = markFractions[i]
            val id    = markIds[i]
            val cx    = (frac * w).coerceIn(mark.halfW, w - mark.halfW)
            val paint = if (id == selectedMarkId) dotSelectedPaint else dotDefaultPaint

            markRect.set(cx - mark.halfW, barCy - mark.halfH, cx + mark.halfW, barCy + mark.halfH)
            canvas.drawRoundRect(markRect, mark.corner, mark.corner, paint)
            markList.add(MarkInfo(cx, id))
        }

        // ── Playhead dot ──────────────────────────────────────────────
        val playheadR  = barH * 0.9f
        val playheadCx = (progressFraction * w).coerceIn(playheadR, w - playheadR)
        canvas.drawCircle(playheadCx, barCy, playheadR, playheadPaint)

        // ── Floating timestamp label above selected mark ───────────────
        val labelMs = selectedMarkMs
        if (hasLabel && labelMs != null) {
            val selIdx  = markIds.indexOf(selectedMarkId)
            val selFrac = markFractions.getOrNull(selIdx)
            if (selFrac != null) {
                val labelText = formatMs(labelMs)
                val halfW     = labelPaint.measureText(labelText) / 2f
                val labelCx   = (selFrac * w)
                    .coerceIn(halfW + density * 2f, w - halfW - density * 2f)
                // Baseline sits a few dp above the top of the mark line
                val labelY = (barCy - mark.halfH) - density * 3f
                canvas.drawText(labelText, labelCx, labelY, labelPaint)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}