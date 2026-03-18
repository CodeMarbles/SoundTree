package com.treecast.app.ui.waveform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.treecast.app.R
import com.treecast.app.util.WaveformExtractor
import com.treecast.app.util.themeColor
import java.util.TreeMap
import kotlin.math.roundToInt

/**
 * Renders a single horizontal strip of waveform covering a fixed time window.
 *
```
 * ── Rendering architecture ────────────────────────────────────────────────────
 *
 * Drawing is split into three layers:
 *
 *   1. Waveform bitmap  ([lineBitmap]) — the amplitude bars.
 *      ARGB_8888, height = view height minus ruler strip.
 *      Rebuilt when amplitude data or view dimensions change.
 *
 *   2. Ruler bitmap  ([rulerBitmap]) — tick marks, grid lines, time labels.
 *      ARGB_8888 transparent, full view height so vertical grid lines extend
 *      through the waveform area. Blitted over layer 1 in a single GPU pass.
 *      Rebuilt only when [startMs]/[endMs]/[fillWidthPx] change — never during
 *      playback or mark updates. Future per-layer opacity effects (e.g. fading
 *      the ruler on the live-recording boundary line) require no bitmap changes.
 *
 *   3. Dynamic overlays — drawn directly to the canvas on every [onDraw]:
 *        a. Mark lines + triangles
 *        b. Playhead line + timestamp label
 *
 * ── Coordinate model ──────────────────────────────────────────────────────────
 *
 * A position in milliseconds maps to an X pixel coordinate as:
 *   x = ((positionMs - startMs).toFloat() / (endMs - startMs)) * width
 *
 * The inverse (touch → ms) is:
 *   positionMs = startMs + ((x / width) * (endMs - startMs)).toLong()
 *
 * ── Layout ────────────────────────────────────────────────────────────────────
 *
 *   [ timestamp ruler — RULER_HEIGHT_DP tall                     ]
 *   [ waveform bars + mark overlay — remaining height            ]
 *
 */
class WaveformLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ── Configuration (set by adapter at bind time) ───────────────────────────


    /** Start of this line's time window, milliseconds. */
    var startMs: Long = 0L
        private set

    /** End of this line's time window, milliseconds. */
    var endMs: Long = 0L
        private set

    /**
     * How many milliseconds a fully filled line represents.  Used to render a partial line
     */
    private var lineWindowMs: Long = 0L

    /**
     * Full recording amplitude array (all lines share the same array reference).
     * This view slices [startMs]..[endMs] from it at render time.
     */
    private var amplitudes: FloatArray? = null

    /**
     * Samples per second the amplitude array was generated at.
     * Must match [WaveformExtractor.SAMPLES_PER_SECOND].
     */
    private var samplesPerSecond: Int = WaveformExtractor.SAMPLES_PER_SECOND

    /**
     * Actual draw width of the waveform in the line.  Used for partial waveform lines
     */
    private var fillWidthPx: Float = 0f

    /**
     * Shared mark store (owned by [MultiLineWaveformView]).
     * Keyed by positionMs for O(log n) range queries.
     */
    private var markStore: TreeMap<Long, WaveformMark>? = null

    /** Currently selected mark ID, or null. Determines teal vs pink colour. */
    private var selectedMarkId: Long? = null

    /**
     * Current playhead position in milliseconds, across the whole recording.
     * -1 means "no playhead" (e.g. record tab where there's no seek position).
     * Only rendered when this value falls within [startMs]..[endMs].
     */
    private var playheadMs: Long = -1L

    /**
     * Whether to show the "played / unplayed" split on the waveform.
     * True on the Listen tab; false on the Record tab.
     */
    private var showPlayedSplit: Boolean = true

    /**
     * When true, this line stretches to fill the full view width regardless of
     * how much of the time window is actually occupied. Used by the Listen tab
     * for single-line recordings so short clips render at full width.
     *
     * When false (default), the line is drawn proportionally — a 3-minute clip
     * in a 5-minute window occupies 60% of the width as normal.
     */
    private var scaleToFill: Boolean = false

    /** When true, a left-rail timestamp column is drawn on each line. */
    private var showLineRail: Boolean = false

    /** Width of the left-rail column when active; 0 when inactive. */
    private val railWidthPx get() = if (showLineRail) RAIL_WIDTH_DP * density else 0f

    // ── Bitmap cache ──────────────────────────────────────────────────────────

    /** Pre-rendered waveform bars. Null until first layout + data bind. */
    private var lineBitmap: Bitmap? = null

    /** True when [lineBitmap] needs to be redrawn before next onDraw. */
    private var bitmapDirty: Boolean = true

    /**
     * Pre-rendered ruler layer — tick marks, grid lines, and time labels —
     * drawn onto a transparent ARGB_8888 bitmap the full view height so that
     * vertical grid lines extend through the waveform area.
     *
     * Rebuilt only when [startMs]/[endMs]/[fillWidthPx] change (i.e. at bind
     * time for completed lines; on each new partial-line tick for the live
     * recording boundary). The transparent background means blitting it over
     * [lineBitmap] is a single GPU compositing pass with no extra overdraw.
     */
    private var rulerBitmap: Bitmap? = null

    /** True when [rulerBitmap] needs to be redrawn before next onDraw. */
    private var rulerBitmapDirty: Boolean = true

    // ── Geometry constants ────────────────────────────────────────────────────

    private val density        = resources.displayMetrics.density
    private val barWidthPx     = 3f  * density
    private val barGapPx       = 2f  * density
    private val cornerPx       = 1.5f * density
    private val stride         get() = barWidthPx + barGapPx
    private val rulerHeightPx  = (RULER_HEIGHT_DP * density).roundToInt()
    private val tickHeightPx   = 6f * density
    private val markTriSizePx  = 6f * density
    private val markTriHeightPx = 5f * density

    // ── Paints ────────────────────────────────────────────────────────────────

    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // Shader set in onSizeChanged once width is known.
    }

    private val unplayedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        val base  = context.themeColor(R.attr.colorWaveformUnplayed)
        color = android.graphics.Color.argb(
            0x88,
            android.graphics.Color.red(base),
            android.graphics.Color.green(base),
            android.graphics.Color.blue(base)
        )
    }

    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.5f * density
        style       = Paint.Style.STROKE
        color       = context.themeColor(R.attr.colorPlayhead)
    }

    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f * density
        style       = Paint.Style.FILL_AND_STROKE
        color       = context.themeColor(R.attr.colorMarkDefault)
    }

    private val markSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f * density
        style       = Paint.Style.FILL_AND_STROKE
        color       = context.themeColor(R.attr.colorMarkSelected)
    }

    private val rulerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = context.themeColor(R.attr.colorTextSecondary)
        textSize  = 9f * density
        textAlign = Paint.Align.CENTER
    }

    private val rulerTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = context.themeColor(R.attr.colorTextSecondary)
        strokeWidth = 1f * density
        alpha       = 120
        style       = Paint.Style.STROKE
    }

    private val playheadLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = context.themeColor(R.attr.colorPlayhead)
        textSize = 9f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val railLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = context.themeColor(R.attr.colorTextSecondary)
        textSize  = 9f * density
        textAlign = Paint.Align.CENTER
        alpha     = 160
    }

    // ── Reusable objects (avoid per-draw allocations) ─────────────────────────

    private val rect     = RectF()
    private val markPath = Path()

    // ── Public bind API ───────────────────────────────────────────────────────

    /**
     * Called by the adapter's onBindViewHolder. Sets all data for this line.
     * Marks the bitmap dirty so it will be redrawn on the next [onDraw].
     */
    fun bind(
        startMs:          Long,
        endMs:            Long,
        lineWindowMs:     Long,
        amplitudes:       FloatArray?,
        samplesPerSecond: Int,
        markStore:        TreeMap<Long, WaveformMark>,
        selectedMarkId:   Long?,
        playheadMs:       Long,
        showPlayedSplit:  Boolean,
        scaleToFill:      Boolean,
        showLineRail:     Boolean
    ) {
        val windowChanged = this.startMs != startMs || this.endMs != endMs
        if (windowChanged) rulerBitmapDirty = true
        val dataChanged   = this.amplitudes !== amplitudes   // reference check: same array = same data

        this.startMs          = startMs
        this.endMs            = endMs
        this.lineWindowMs     = lineWindowMs
        this.amplitudes       = amplitudes
        this.samplesPerSecond = samplesPerSecond
        this.markStore        = markStore
        this.selectedMarkId   = selectedMarkId
        this.playheadMs       = playheadMs
        this.showPlayedSplit  = showPlayedSplit
        this.scaleToFill      = scaleToFill
        this.showLineRail     = showLineRail

        recomputeFillWidthPx()

        if (windowChanged || dataChanged) bitmapDirty = true
        invalidate()
    }

    /**
     * Lightweight update — only mark selection or playhead changed.
     * Does NOT dirty the bitmap; just triggers a cheap overlay redraw.
     */
    fun updateOverlay(selectedMarkId: Long?, playheadMs: Long) {
        this.selectedMarkId = selectedMarkId
        this.playheadMs     = playheadMs
        invalidate()
    }

    fun xToMs(x: Float): Long {
        val adjustedX = (x - railWidthPx).coerceAtLeast(0f)
        if (fillWidthPx <= 0f) return startMs
        val windowMs = endMs - startMs
        return (startMs + ((adjustedX / fillWidthPx) * windowMs).toLong())
            .coerceIn(startMs, endMs)
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun recomputeFillWidthPx() {
        val availableW = (width - railWidthPx).coerceAtLeast(0f)
        fillWidthPx = if (scaleToFill) {
            availableW
        } else {
            val actualWindowMs = (endMs - startMs).coerceAtLeast(1L)
            if (lineWindowMs >= endMs - startMs)
                (actualWindowMs.toFloat() / lineWindowMs) * availableW
            else
                availableW
        }
        // Gradient spans the waveform area only — must be kept in sync with
        // availableW so it never bleeds into or skips the rail column.
        if (availableW > 0f) {
            playedPaint.shader = LinearGradient(
                0f, 0f, availableW, 0f,
                intArrayOf(
                    context.themeColor(R.attr.colorWaveformGradientStart),
                    context.themeColor(R.attr.colorWaveformGradientMid),
                    context.themeColor(R.attr.colorWaveformGradientEnd)
                ),
                null, Shader.TileMode.CLAMP
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            recomputeFillWidthPx()
            bitmapDirty      = true
            rulerBitmapDirty = true
        }
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val waveformTop = rulerHeightPx.toFloat()
        val waveformH   = height - rulerHeightPx
        val rail        = railWidthPx

        // ── Rail label ────────────────────────────────────────────────────
        // Drawn in view coordinates before the translate, centered in the rail
        // column at the vertical midpoint of the waveform area.
        if (showLineRail && rail > 0f) {
            val cx = rail / 2f
            val cy = waveformTop + waveformH / 2f
            canvas.save()
            canvas.rotate(-90f, cx, cy)
            canvas.drawText(formatMs(startMs), cx, cy + railLabelPaint.textSize / 3f, railLabelPaint)
            canvas.restore()
        }

        // ── Waveform content (shifted right by rail width) ────────────────
        // All downstream drawing (bitmaps, marks, playhead) uses coordinates
        // relative to the waveform area — no internal changes needed.
        canvas.save()
        canvas.translate(rail, 0f)

        if (bitmapDirty || lineBitmap == null) rebuildWaveformBitmap(waveformH)
        lineBitmap?.let { canvas.drawBitmap(it, 0f, waveformTop, null) }

        if (rulerBitmapDirty || rulerBitmap == null) rebuildRulerBitmap()
        rulerBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        drawMarks(canvas, waveformTop, waveformH.toFloat())
        drawPlayhead(canvas, waveformTop, waveformH.toFloat())

        canvas.restore()
    }

    // ── Bitmap rebuild ────────────────────────────────────────────────────────

    /**
     * Renders the waveform bars into [lineBitmap].
     *
     * ── Stable bucket mapping ─────────────────────────────────────────────────────
     *
     * Each visual bar covers a fixed range of samples, computed from the full
     * line width and [lineWindowMs] — both of which are constants for the lifetime
     * of a line. This means bar N always maps to the same sample range regardless
     * of how many samples have arrived so far, eliminating the pulsation effect
     * seen on the live recording line when [liveAmplitudes] grows between redraws.
     *
     * Each bar takes the peak (max) amplitude across its sample range rather than
     * a single point sample. This makes bars monotonically stable — a bar's height
     * can only stay the same or grow as new audio fills its range, never jump to
     * an unrelated value.
     *
     * For completed lines and the Listen tab the behaviour is identical to before:
     * [lineWindowMs] == [endMs] - [startMs] and [fillWidthPx] == [width], so the
     * full bar count is drawn and every bar has exactly the same sample coverage
     * as it always did.
     */
    private fun rebuildWaveformBitmap(waveformH: Int) {
        if (width == 0 || waveformH <= 0) return

        val bitmapW = (width - railWidthPx).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bitmapW, waveformH, Bitmap.Config.ARGB_8888)
        val c   = Canvas(bmp)

        // Number of bars that fit across the full view width — fixed for this line.
        val fullBarCount = (bitmapW / stride).toInt().coerceAtLeast(1)

        // How many samples a full line represents, anchored to the full line window.
        // Using lineWindowMs (not endMs-startMs) keeps this constant even while the
        // live partial line is still growing.
        val fullWindowSamples = (lineWindowMs / 1000.0 * samplesPerSecond)
            .coerceAtLeast(1.0)

        // Fixed samples-per-bar — never changes during a recording.
        val samplesPerBar = fullWindowSamples / fullBarCount

        // Sample index where this line's audio starts in the shared amplitude array.
        val startIdx = ((startMs / 1000.0) * samplesPerSecond).toInt()

        val amps    = amplitudes ?: WaveformExtractor.flatFallback(fullBarCount)
        val centerY = waveformH / 2f
        val maxHalf = centerY * 0.88f

        // Only draw bars that are within the filled portion of the line.
        val barCount = (fillWidthPx / stride).toInt().coerceAtLeast(1)

        for (i in 0 until barCount) {
            // Fixed sample range for this bar — stable across redraws.
            val sampleStart = (startIdx + i       * samplesPerBar).toInt().coerceIn(0, amps.size - 1)
            val sampleEnd   = (startIdx + (i + 1) * samplesPerBar).toInt().coerceIn(0, amps.size)

            // Peak amplitude across the bar's sample range.
            val amp = if (sampleEnd > sampleStart)
                amps.slice(sampleStart until sampleEnd).max()!!
            else
                amps[sampleStart]

            val half = maxHalf * amp.coerceAtLeast(0.07f)
            val x    = i * stride
            rect.set(x, centerY - half, x + barWidthPx, centerY + half)
            c.drawRoundRect(rect, cornerPx, cornerPx, playedPaint)
        }

        lineBitmap?.recycle()
        lineBitmap  = bmp
        bitmapDirty = false
    }

    /**
     * Returns the interval between ruler ticks, chosen so that ticks are never
     * uncomfortably close together in pixels — regardless of how narrow the
     * filled portion of the line is.
     *
     * The pixel-aware approach matters most on the Record tab, where the live
     * partial line grows from zero width and the time window is always the full
     * [secondsPerLine] even when only a few seconds have been recorded.
     */
    private fun rulerIntervalMs(windowMs: Long, fillWidthPx: Float): Long {
        // "Nice" tick spacings in ascending order.
        val candidates = longArrayOf(
            5_000L, 10_000L, 15_000L, 30_000L,
            60_000L, 2 * 60_000L, 5 * 60_000L
        )

        // Smallest pixel gap we're comfortable with between any two ticks.
        // At 44dp the ruler stays readable without feeling sparse on full lines.
        val minTickSpacingPx = 44f * density

        // What time interval (ms) produces at least minTickSpacingPx of separation,
        // given the current physical fill width?
        val minIntervalMs = ((windowMs * minTickSpacingPx) /
                fillWidthPx.coerceAtLeast(1f)).toLong()

        // Pick the smallest "nice" interval that satisfies the pixel constraint.
        return candidates.firstOrNull { it >= minIntervalMs }
            ?: candidates.last()   // fallback: widest spacing if nothing fits
    }

    private fun snapToNiceInterval(rawMs: Long): Long {
        val candidates = longArrayOf(
            5_000L, 10_000L, 15_000L, 30_000L,
            60_000L, 2 * 60_000L, 5 * 60_000L
        )
        return candidates.firstOrNull { it >= rawMs } ?: candidates.last()
    }

    /**
     * Renders the timestamp ruler into a full-view-height transparent bitmap.
     *
     * The bitmap covers [0, 0, width, height]:
     *   - Top [rulerHeightPx] rows: tick caps and time labels.
     *   - Remaining rows: vertical grid lines continuing into the waveform.
     *
     * Drawing to a transparent bitmap rather than directly to the canvas means
     * this work only runs when [startMs]/[endMs]/[fillWidthPx] change — not on
     * every frame. It also keeps the ruler visually independent of the waveform
     * bars, enabling future per-layer opacity/alpha effects without bitmap
     * reconstruction.
     */
    private fun rebuildRulerBitmap() {
        if (width == 0 || height == 0) return

        val bitmapW = (width - railWidthPx).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bitmapW, height, Bitmap.Config.ARGB_8888)
        // Default pixel value is 0 (transparent) — no explicit clear needed.
        val c = Canvas(bmp)

        val windowMs = (endMs - startMs).toFloat()
        if (windowMs > 0f) {
            val labelY    = rulerHeightPx - (3f * density)
            val edgePadPx = 2f * density

            // Minimum px gap an interior tick must have from each boundary tick
            // before its label is drawn. Prevents label collisions.
            val minLabelSpacePx = 28f * density

            // Minimum px width before we bother drawing the right boundary tick.
            // Below this threshold the two boundary ticks would be too close.
            val rightBoundaryMinPx = 60f * density

            fun drawTickLine(x: Float) {
                // Short tick cap in the ruler strip.
                c.drawLine(x, 0f, x, tickHeightPx, rulerTickPaint)
                // Grid line extending through the full waveform area.
                c.drawLine(x, rulerHeightPx.toFloat(), x, height.toFloat(), rulerTickPaint)
            }

            // ── Left boundary (always drawn) ──────────────────────────────────
            drawTickLine(0f)
            rulerPaint.textAlign = Paint.Align.LEFT
            c.drawText(formatMs(startMs), edgePadPx, labelY, rulerPaint)

            // ── Right boundary (only when line is wide enough) ────────────────
            if (fillWidthPx >= rightBoundaryMinPx) {
                drawTickLine(fillWidthPx)
                rulerPaint.textAlign = Paint.Align.RIGHT
                c.drawText(formatMs(endMs), fillWidthPx - edgePadPx, labelY, rulerPaint)
            }

            // ── Interior interval ticks ───────────────────────────────────────────
            val maxInteriorTicks = if (scaleToFill)
                (windowMs / 30_000f).toInt().coerceIn(0, 4)
            else
                Int.MAX_VALUE

            // When scaleToFill, distribute ticks evenly across the duration rather than
            // using the pixel-spacing interval — prevents the cap from creating a large
            // gap at the end when the spacing and cap don't naturally align.
            val intervalMs = if (scaleToFill && maxInteriorTicks > 0)
                snapToNiceInterval((windowMs / (maxInteriorTicks + 1)).toLong())
            else
                rulerIntervalMs(windowMs.toLong(), fillWidthPx)

            var interiorTicksDrawn = 0
            var tickMs = ((startMs / intervalMs) + 1) * intervalMs
            while (tickMs < endMs && interiorTicksDrawn < maxInteriorTicks) {
                val x = ((tickMs - startMs).toFloat() / windowMs) * fillWidthPx
                if (x >= fillWidthPx) break
                drawTickLine(x)
                if (x > minLabelSpacePx && x < fillWidthPx - minLabelSpacePx) {
                    c.drawText(formatMs(tickMs), x, labelY, rulerPaint)
                }
                interiorTicksDrawn++
                tickMs += intervalMs
            }
        }

        rulerBitmap?.recycle()
        rulerBitmap      = bmp
        rulerBitmapDirty = false
    }

    // ── Mark overlay ──────────────────────────────────────────────────────────

    private fun drawMarks(canvas: Canvas, waveformTop: Float, waveformH: Float) {
        val store = markStore ?: return
        if (store.isEmpty()) return
        val windowMs = (endMs - startMs).toFloat()
        if (windowMs <= 0f) return

        // Fetch only marks within this line's time window — O(log n + k).
        // Lower bound: inclusive only for the very first line (startMs == 0) so a
        // mark exactly at a line boundary isn't double-drawn on two adjacent lines.
        // Upper bound: inclusive so a mark clamped to the exact recording boundary
        // (endMs) is always rendered on the last line.
        val visibleMarks = store.subMap(startMs, startMs == 0L, endMs, true).values
        if (visibleMarks.isEmpty()) return

        val selectedId = selectedMarkId

        // TODO: I feel like we can optimize this more.  Like...we dont need to walk the list twice?
        // Draw unselected marks first, selected on top.
        for (pass in 0..1) {
            for (mark in visibleMarks) {
                val isSelected = mark.id == selectedId
                if ((pass == 0) == isSelected) continue   // first pass = unselected only

                val x = ((mark.positionMs - startMs).toFloat() / windowMs) * fillWidthPx
                val paint = if (isSelected) markSelectedPaint else markPaint

                canvas.drawLine(x, waveformTop, x, waveformTop + waveformH, paint)

                markPath.rewind()
                markPath.moveTo(x - markTriSizePx, waveformTop)
                markPath.lineTo(x + markTriSizePx, waveformTop)
                markPath.lineTo(x, waveformTop + markTriHeightPx)
                markPath.close()
                canvas.drawPath(markPath, paint)
            }
        }
    }

    // ── Playhead overlay ──────────────────────────────────────────────────────

    private fun drawPlayhead(canvas: Canvas, waveformTop: Float, waveformH: Float) {
        if (!showPlayedSplit || playheadMs < 0L) return
        val windowMs = (endMs - startMs).toFloat()
        if (windowMs <= 0f) return

        val playheadX = when {
            playheadMs <= startMs -> 0f
            playheadMs >= endMs   -> fillWidthPx
            else -> ((playheadMs - startMs).toFloat() / windowMs) * fillWidthPx
        }

        // Dim the unplayed region to the right of the playhead.
        if (playheadX < fillWidthPx) {
            canvas.drawRect(
                playheadX, waveformTop,
                fillWidthPx, waveformTop + waveformH,
                unplayedPaint
            )
        }

        // Don't draw the playhead line if it's off this line entirely.
        if (playheadMs < startMs || playheadMs > endMs) return

        canvas.drawLine(playheadX, waveformTop, playheadX, waveformTop + waveformH, playheadPaint)

        val label = formatMs(playheadMs)
        val labelY = waveformTop - (4f * density)
        canvas.drawText(label, playheadX.coerceIn(30f, fillWidthPx - 30f), labelY, playheadLabelPaint)
    }

    // ── Timestamp ruler ───────────────────────────────────────────────────────
    /**
     * Returns a round interval in milliseconds suitable for ruler ticks given
     * the line's [windowMs] span. Aims for roughly 4–6 ticks per line.
     */
    private fun rulerIntervalMs(windowMs: Long): Long = when {
        windowMs > 20 * 60_000L  -> 5 * 60_000L    // > 20 min  → 5 min ticks
        windowMs > 10 * 60_000L  -> 2 * 60_000L    // > 10 min  → 2 min ticks
        windowMs >  4 * 60_000L  ->     60_000L    //  > 4 min  → 1 min ticks
        windowMs >  2 * 60_000L  ->     30_000L    //  > 2 min  → 30 sec ticks
        windowMs >      60_000L  ->     15_000L    //  > 1 min  → 15 sec ticks
        windowMs >      30_000L  ->     10_000L    // > 30 sec  → 10 sec ticks
        else                     ->      5_000L    //  ≤ 30 sec →  5 sec ticks
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Maps an X pixel coordinate to an index in [amplitudes], clamped to
     * the sub-array covering [startMs]..[endMs].
     */
    private fun amplitudeIndexForX(x: Float, totalSamples: Int): Int {
        // Index into the full amplitude array that corresponds to startMs.
        val startIdx = ((startMs  / 1000.0) * samplesPerSecond).toInt()
            .coerceIn(0, totalSamples - 1)
        val endIdx   = ((endMs    / 1000.0) * samplesPerSecond).toInt()
            .coerceIn(0, totalSamples - 1)
        val windowSamples = (endIdx - startIdx).coerceAtLeast(1)
        val barCount      = (fillWidthPx / stride).toInt().coerceAtLeast(1)
        val barIdx        = (x / stride).toInt().coerceIn(0, barCount - 1)
        return (startIdx + (barIdx.toFloat() / barCount * windowSamples).toInt())
            .coerceIn(0, totalSamples - 1)
    }

    /** Formats a millisecond timestamp as m:ss or h:mm:ss. */
    private fun formatMs(ms: Long): String {
        val totalSecs = ms / 1000L
        val hours     = totalSecs / 3600L
        val mins      = (totalSecs % 3600L) / 60L
        val secs      = totalSecs % 60L
        return if (hours > 0)
            "%d:%02d:%02d".format(hours, mins, secs)
        else
            "%d:%02d".format(mins, secs)
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        lineBitmap?.recycle()
        lineBitmap = null
        rulerBitmap?.recycle()
        rulerBitmap = null
    }

    companion object {
        /** Height of the timestamp ruler strip at the top of each line. */
        const val RULER_HEIGHT_DP = 18
        private const val RAIL_WIDTH_DP = 12f
    }
}