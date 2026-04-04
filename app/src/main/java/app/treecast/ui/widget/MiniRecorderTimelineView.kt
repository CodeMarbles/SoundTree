package app.treecast.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import app.treecast.R
import app.treecast.util.themeColor
import kotlin.math.abs

/**
 * Growing recording timeline for the Mini Recorder widget.
 *
 * Visual layers (back to front):
 *   1. Muted track rect          — always drawn
 *   2. Semi-transparent fill     — drawn when elapsedMs > 0
 *   3. Amplitude waveform path   — drawn while recording; symmetric envelope
 *                                  clipped to the bar bounds, very low amplitude
 *                                  so it reads as "breath" not "waveform"
 *   4. Traveling shimmer         — a soft LinearGradient highlight sweeping
 *                                  right→left on a ~4 s loop while recording
 *   5. Mark dots                 — pink (default) or teal (selected)
 *   6. Floating timestamp label  — above selected/last mark when enabled
 *   7. Live-edge pulse dot       — pulsing red circle at right edge while recording
 *
 * Amplitude data:
 *   Call [pushAmplitude] on every amplitude tick (from MainActivity, which
 *   collects MainViewModel.liveAmplitude). Samples accumulate in a fixed-size
 *   ring buffer; the waveform path maps the whole buffer across the bar width
 *   so it always looks "full" regardless of how many samples have arrived.
 *
 * Animation:
 *   A single postDelayed Runnable drives all three animated elements (wave
 *   redraw, shimmer position, pulse alpha) at 60 ms / ~16 fps. This is
 *   sufficient for this kind of ambient animation and keeps CPU negligible.
 */
class MiniRecorderTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Constants ─────────────────────────────────────────────────────

    companion object {
        /** Ring buffer depth. At ~10 Hz this covers ~6 seconds of history. */
        private const val BUFFER_SIZE = 64

        /**
         * Maximum half-height of the waveform envelope in dp.
         * Kept deliberately small — the goal is "breathing" not "waveform bars".
         * The bar itself is 4dp tall (2dp half-height), so 1.1dp gives ~55% fill
         * at full amplitude while staying visually flat at typical voice levels.
         */
        private const val MAX_WAVE_DP = 1.5f

        /** Shimmer highlight total width as a fraction of bar width. */
        private const val SHIMMER_W_FRAC = 0.28f

        /** Shimmer peak opacity. Low keeps it subliminal. */
        private const val SHIMMER_ALPHA = 30

        /** Distance the shimmer moves per 60 ms tick (fraction of width). */
        private const val SHIMMER_STEP = 0.035f
    }

    // ── Data ──────────────────────────────────────────────────────────

    private var markTimestamps: List<Long> = emptyList()
    private var elapsedMs: Long = 0L
    private var selectedMarkIndex: Int = -1

    // Ring buffer for amplitude samples (0f–1f), newest at head.
    private val ampBuffer  = FloatArray(BUFFER_SIZE) { 0f }
    private var bufferHead = 0   // index of next write slot

    var isRecording: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startAnimating() else stopAnimating()
        }

    var showLastMarkTimestamp: Boolean = false
        set(value) { field = value; invalidate() }

    var onMarkTapped: ((index: Int) -> Unit)? = null

    // ── Cached hit-test positions ─────────────────────────────────────

    private val dotCxList = mutableListOf<Float>()

    // ── Animation state ───────────────────────────────────────────────

    /** Shimmer center expressed as a fraction of bar width (0 = left, 1 = right). */
    private var shimmerPos  = 1f + SHIMMER_W_FRAC   // start off right edge

    private var pulseAlpha  = 1f
    private var pulseGrowing = false

    private val animRunnable = object : Runnable {
        override fun run() {
            // Advance shimmer right → left
            shimmerPos -= SHIMMER_STEP
            if (shimmerPos < -(SHIMMER_W_FRAC)) shimmerPos = 1f + SHIMMER_W_FRAC

            // Pulse the live-edge dot
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

    private fun startAnimating() { post(animRunnable) }
    private fun stopAnimating()  {
        removeCallbacks(animRunnable)
        pulseAlpha  = 1f
        shimmerPos  = 1f + SHIMMER_W_FRAC
        invalidate()
    }

    // ── Paints ────────────────────────────────────────────────────────

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorSurfaceElevated)
    }

    /** Semi-transparent fill — always covers the full width while recording. */
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorRecordActive).let { c ->
            android.graphics.Color.argb(
                110,
                android.graphics.Color.red(c),
                android.graphics.Color.green(c),
                android.graphics.Color.blue(c)
            )
        }
    }

    /** Waveform envelope path fill — slightly more opaque than the base fill. */
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
//        color = context.themeColor(R.attr.colorRecordActive).let { c ->
//            android.graphics.Color.argb(
//                170,
//                android.graphics.Color.red(c),
//                android.graphics.Color.green(c),
//                android.graphics.Color.blue(c)
//            )
//        }
        color = android.graphics.Color.argb(230, 255, 160, 160)
        style = Paint.Style.FILL
    }

    /** Shimmer paint — shader is rebuilt each frame (cheap at 3 color stops). */
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorMarkDefault)
    }
    private val selectedDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorMarkSelected)
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.colorRecordActive)
    }
    private val pulseRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // colorForeground = white in dark theme, black in light theme
        val tv = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorForeground, tv, true)
        color = tv.data
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 1.5f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = context.themeColor(R.attr.colorMarkSelected)
        textSize  = context.resources.displayMetrics.density * 9f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val trackRect  = RectF()
    private val markRect = RectF()
    private val wavePath   = Path()

    // Decomposed accent RGB for shimmer gradient construction
    private val accentR: Int
    private val accentG: Int
    private val accentB: Int

    init {
        val accent = context.themeColor(R.attr.colorRecordActive)
        accentR = android.graphics.Color.red(accent)
        accentG = android.graphics.Color.green(accent)
        accentB = android.graphics.Color.blue(accent)
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Updates timeline data. Call from MainActivity whenever elapsed time,
     * marks list, or selected mark index changes.
     */
    fun update(elapsedMs: Long, marks: List<Long>, selectedMarkIndex: Int = -1) {
        this.elapsedMs          = elapsedMs
        this.markTimestamps     = marks
        this.selectedMarkIndex  = selectedMarkIndex
        invalidate()
    }

    /**
     * Pushes a normalized amplitude sample (0f–1f) into the ring buffer.
     * Called from MainActivity at whatever rate the RecordingService emits.
     * Does NOT call invalidate() — the animation runnable drives all redraws.
     */
    fun pushAmplitude(amplitude: Float) {
        ampBuffer[bufferHead % BUFFER_SIZE] = amplitude.coerceIn(0f, 1f)
        bufferHead++
    }

    // ── Touch ─────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && markTimestamps.isNotEmpty()) {
            val hitSlop = resources.displayMetrics.density * 20f
            val hitIndex = dotCxList.indexOfFirst { cx -> abs(cx - event.x) <= hitSlop }
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

        // ── Geometry ──────────────────────────────────────────────────
        val labelAreaH = if (showLastMarkTimestamp && hasActiveLabel()) density * 14f else 0f
        val barH       = (density * 4f).coerceAtMost((h - labelAreaH) * 0.35f)
        val barCy      = labelAreaH + (h - labelAreaH) / 2f
        val barTop     = barCy - barH / 2f
        val barBottom  = barCy + barH / 2f
        val barRadius  = barH / 2f

        // ── Track ──────────────────────────────────────────────────
        trackRect.set(0f, barTop, w, barBottom)
        canvas.drawRoundRect(trackRect, barRadius, barRadius, trackPaint)

        if (elapsedMs > 0) {
            // ── Fill ───────────────────────────────────────────────
            trackRect.set(0f, barTop, w, barBottom)
            canvas.drawRoundRect(trackRect, barRadius, barRadius, fillPaint)

            if (isRecording) {
                // ── Waveform envelope ──────────────────────────────
                drawWaveEnvelope(canvas, w, barCy, barTop, barBottom)

                // ── Shimmer ────────────────────────────────────────
                drawShimmer(canvas, w, barTop, barBottom, barRadius)
            }
        }

        val dotRadius   = barH * 1.15f
        val activeIndex = if (selectedMarkIndex in markTimestamps.indices) selectedMarkIndex else -1
        dotCxList.clear()

        // ── Pulse dot + ring (drawn before mark dots so marks sit on top) ──────
//        if (isRecording && elapsedMs > 0) {
//            val dotCx = w - dotRadius * 1.2f
//            // Ring slightly larger than the dot
//            pulsePaint.alpha = (pulseAlpha * 255).toInt()
//            //pulseRingPaint.alpha = (pulseAlpha * 255).toInt()
//            pulseRingPaint.alpha = 200
//            canvas.drawCircle(dotCx, barCy, dotRadius * 0.85f + density * 1.8f, pulseRingPaint)
//            canvas.drawCircle(dotCx, barCy, dotRadius * 0.85f, pulsePaint)
//        }
        if (isRecording && elapsedMs > 0) {
            val ringRadius = dotRadius * 0.85f + density * 1.8f
            val dotCx = w - ringRadius - density * 2f   // 2dp breathing room from right edge
            pulsePaint.alpha = (pulseAlpha * 255).toInt()
            pulseRingPaint.alpha = (pulseAlpha * 255).toInt()
            canvas.drawCircle(dotCx, barCy, ringRadius, pulseRingPaint)
            canvas.drawCircle(dotCx, barCy, dotRadius * 0.85f, pulsePaint)
        }

        val mark = TimelineMarkStyle.compute(density, barH)

        // ── Mark dots ──────────────────────────────────────────────
        for (i in markTimestamps.indices) {
            val ts   = markTimestamps[i]
            val frac = if (elapsedMs > 0) (ts.toFloat() / elapsedMs).coerceIn(0f, 1f) else 0f
            //val cx   = (frac * w).coerceIn(dotRadius, w - dotRadius)
            val cx   = (frac * w).coerceIn(mark.halfW, w - mark.halfW)
            dotCxList.add(cx)

//            canvas.drawCircle(cx, barCy, dotRadius,
//                if (i == activeIndex) selectedDotPaint else dotPaint)
            markRect.set(cx - mark.halfW, barCy - mark.halfH, cx + mark.halfW, barCy + mark.halfH)
            canvas.drawRoundRect(markRect, mark.corner, mark.corner, if (i == activeIndex) selectedDotPaint else dotPaint)

            // ── Floating timestamp label ───────────────────────────
            if (i == activeIndex && showLastMarkTimestamp) {
                val labelText = formatMs(ts)
                val textHalfW = labelPaint.measureText(labelText) / 2f
                val labelX    = cx.coerceIn(textHalfW + density * 2f, w - textHalfW - density * 2f)
                val labelY    = (barCy - mark.halfH) - density * 5f
                canvas.drawText(labelText, labelX, labelY, labelPaint)
            }
        }
    }

    /**
     * Draws a symmetric waveform envelope centred on [barCy].
     *
     * The ring buffer is mapped across the full bar width (oldest sample at x=0,
     * newest at x=w). Each sample determines how far the envelope deviates above
     * and below centre. The resulting filled path is clipped to the bar's
     * bounding rect so it never bleeds outside.
     *
     * At zero amplitude the path collapses to a flat line and becomes invisible
     * against the fill. At full amplitude it expands to ~MAX_WAVE_DP above and
     * below centre — still comfortably inside the 2dp half-height of the bar.
     */
    private fun drawWaveEnvelope(
        canvas: Canvas,
        w: Float, barCy: Float, barTop: Float, barBottom: Float
    ) {
        val maxDev = MAX_WAVE_DP * resources.displayMetrics.density
        wavePath.reset()

        // Top edge: left → right
        for (i in 0 until BUFFER_SIZE) {
            val sampleIdx = ((bufferHead - BUFFER_SIZE + i).let { ((it % BUFFER_SIZE) + BUFFER_SIZE) % BUFFER_SIZE })
            val amp = ampBuffer[sampleIdx]
            val x   = i.toFloat() / (BUFFER_SIZE - 1).toFloat() * w
            val y   = barCy - amp * maxDev
            if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
        }

        // Bottom edge: right → left (mirror)
        for (i in BUFFER_SIZE - 1 downTo 0) {
            val sampleIdx = ((bufferHead - BUFFER_SIZE + i).let { ((it % BUFFER_SIZE) + BUFFER_SIZE) % BUFFER_SIZE })
            val amp = ampBuffer[sampleIdx]
            val x   = i.toFloat() / (BUFFER_SIZE - 1).toFloat() * w
            val y   = barCy + amp * maxDev
            wavePath.lineTo(x, y)
        }
        wavePath.close()

        canvas.save()
        //canvas.clipRect(0f, barTop, w, barBottom)
        canvas.drawPath(wavePath, wavePaint)
        canvas.restore()
    }

    /**
     * Draws a soft horizontal highlight that sweeps right → left across the bar.
     *
     * The gradient is rebuilt each frame (3 color stops, no Bitmap) — at 60 ms
     * intervals on a view this small the allocation cost is negligible.
     */
    private fun drawShimmer(
        canvas: Canvas,
        w: Float, barTop: Float, barBottom: Float, barRadius: Float
    ) {
        val halfW   = w * SHIMMER_W_FRAC / 2f
        val centerX = shimmerPos * w
        val x0      = centerX - halfW
        val x1      = centerX + halfW

        // Skip entirely when the shimmer is fully off either edge
        if (x1 < 0f || x0 > w) return

        shimmerPaint.shader = LinearGradient(
            x0, 0f, x1, 0f,
            intArrayOf(
                android.graphics.Color.argb(0,           accentR, accentG, accentB),
                android.graphics.Color.argb(SHIMMER_ALPHA, accentR, accentG, accentB),
                android.graphics.Color.argb(0,           accentR, accentG, accentB)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        canvas.save()
        canvas.clipRect(0f, barTop, w, barBottom)
        trackRect.set(0f, barTop, w, barBottom)
        canvas.drawRoundRect(trackRect, barRadius, barRadius, shimmerPaint)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimating()
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun hasActiveLabel(): Boolean = markTimestamps.isNotEmpty()

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}