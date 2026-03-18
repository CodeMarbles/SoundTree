package com.treecast.app.ui.waveform

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.treecast.app.ui.waveform.MultiLineWaveformView.WaveformLineAdapter
import com.treecast.app.util.WaveformExtractor
import java.util.TreeMap
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Multi-line waveform visualizer. Renders the waveform across multiple
 * horizontal "lines" — like lines on a page — so that a long recording
 * can be scrolled through vertically rather than crammed into a single strip.
 *
 * ── Public API ────────────────────────────────────────────────────────────────
 *
 * Configure once after inflation (before first data arrives):
 *   [secondsPerLine]     — how much audio each line represents (fixed for now)
 *   [showPlayedSplit]    — whether bars before the playhead are full-colour
 *   [onTimeSelected]     — callback when the user taps a position
 *
 * Feed data:
 *   [setDurationMs]      — call to set/update total recording length
 *   [setPlayheadMs]      — call on every playback tick
 *   [setMarks]           — call whenever the mark list changes
 *   [setSelectedMarkId]  — call when mark selection changes
 *   [setMarksAndSelectedId] — atomic version; prefer this when both change together
 *
 * Live recording (Record tab):
 *   [pushAmplitude]      — call on each recorder amplitude sample
 *
 * ── Architecture ──────────────────────────────────────────────────────────────
 *
 * Internally this is a RecyclerView with [WaveformLineAdapter].
 * Each item is a [WaveformLineView] covering one [secondsPerLine] window.
 *
 * The mark list is stored as a [TreeMap] keyed by positionMs. Each line
 * queries its own time window via [TreeMap.subMap] — O(log n + k) per line.
 * This means mark updates never trigger bitmap rebuilds — only a lightweight
 * overlay redraw on visible lines.
 *
 * Future insertable items (MarkDetail cards, transcription blocks) will be
 * added as new [WaveformItem] subtypes in the adapter's item list without
 * changing this public API.
 */
class MultiLineWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    // ── Configuration ─────────────────────────────────────────────────────────

    /**
     * How many seconds of audio each line represents. Set once after inflation;
     * will become runtime-configurable when zoom is implemented.
     */
    var secondsPerLine: Int = DEFAULT_SECONDS_PER_LINE
        set(value) {
            field = value
            rebuildItemList()
        }

    /**
     * Whether to render bars before the playhead in full gradient colour and
     * bars after in the dimmed "not yet played" style. Set to false for the
     * Record tab where there is no playback position.
     */
    var showPlayedSplit: Boolean = true
        set(value) {
            field = value
            notifyAllVisibleLines()
        }

    /**
     * When true, a single-line recording is stretched to fill the full widget
     * width. Only applies to the first (and only) line; subsequent lines always
     * render proportionally. Set to true on the Listen tab; leave false (default)
     * on the Record tab.
     */
    var scaleToFill: Boolean = false
        set(value) {
            field = value
            rebuildItemList()
        }

    /**
     * When true, a slim left-rail column on each line shows the line's absolute
     * start timestamp, giving the user time orientation while scrolling.
     * Intended to be wired to a Settings toggle.
     */
    var showLineRail: Boolean = false
        set(value) {
            field = value
            rebuildItemList()
        }

    /**
     * Visual style applied to every [WaveformLineView] in this widget.
     *
     * Changing the style:
     *   • Notifies all visible lines so they rebuild their bitmaps.
     *   • Installs or removes the [BackgroundDecoration] on the RecyclerView.
     *   • Triggers [rebuildBackgroundBitmap] for non-Standard styles.
     *
     * Wired from [ListenFragment] by collecting [MainViewModel.waveformStyle].
     */
    var waveformStyle: WaveformStyle = WaveformStyle.Standard
        set(value) {
            if (field == value) return
            field = value
            updateBackgroundDecoration(value)
            notifyAllVisibleLines()
        }

    /**
     * Called when the user taps a position on any line.
     *
     * @param positionMs  Tapped position in milliseconds (recording-relative).
     * @param type        [WaveformTapType.TAP] or [WaveformTapType.LONG_PRESS].
     */
    var onTimeSelected: ((positionMs: Long, type: WaveformTapType) -> Unit)? = null

    // ── Internal state ────────────────────────────────────────────────────────

    /** Full-resolution amplitude array for the recording. Shared reference. */
    private var amplitudes: FloatArray? = null

    /** Total recording duration. Drives how many lines are created. */
    private var totalDurationMs: Long = 0L

    /** Current playhead position, recording-relative. -1 = none. */
    private var playheadMs: Long = -1L

    /** Mark store — keyed by positionMs for O(log n) range queries per line. */
    private val markStore = TreeMap<Long, WaveformMark>()

    /** Currently selected mark. */
    private var selectedMarkId: Long? = null

    // ── Background surface (stylized waveform styles) ──────────────────────

    /**
     * Background bitmap drawn behind all waveform lines.
     * Intentionally taller than one line (see [BACKGROUND_BITMAP_HEIGHT_LINES])
     * so each line sees a different vertical slice, giving more visual variety
     * on scroll than a single-line tile would.
     *
     * Null when [waveformStyle] is [WaveformStyle.Standard] or before the first
     * layout/style change that triggers [rebuildBackgroundBitmap].
     */
    private var backgroundBitmap: Bitmap? = null

    /** The installed decoration, or null when Standard style is active. */
    private var backgroundDecoration: BackgroundDecoration? = null

    // ── Live recording state (Record tab) ─────────────────────────────────────

    /**
     * Accumulates amplitude samples during a live recording.
     * Grown as [pushAmplitude] is called; used to build the last (partial) line.
     */
    private val liveAmplitudes = mutableListOf<Float>()

    /** Wall-clock ms of the last bitmap redraw. Used to throttle [pushAmplitude]. */
    private var lastRedrawMs: Long = 0L

    /** True when the user has manually scrolled up; suppresses live auto-scroll. */
    private var autoScrollPaused = false

    // ── Views ─────────────────────────────────────────────────────────────────

    private val recyclerView  = RecyclerView(context)
    private val adapter       = WaveformLineAdapter()
    private val topFadeView    = View(context)
    private val bottomFadeView = View(context)

    init {
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter       = adapter
        recyclerView.itemAnimator  = null
        recyclerView.isVerticalScrollBarEnabled = true
        // Reserve bottom padding so the last line can always be scrolled clear
        // of the mark-controls card. clipToPadding=false keeps items visible
        // while they scroll through the padded zone.
        val dp = resources.displayMetrics.density
        recyclerView.setPadding(0, 0, 0, (BOTTOM_PADDING_DP * dp).toInt())
        recyclerView.clipToPadding = false
        addView(recyclerView, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // ── Edge fades ────────────────────────────────────────────────────
        val fadeH = (FADE_HEIGHT_DP * dp).toInt()
        topFadeView.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xCC000000.toInt(), 0x00000000)
        )
        topFadeView.visibility = View.INVISIBLE
        addView(topFadeView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, fadeH, Gravity.TOP))

        bottomFadeView.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xCC000000.toInt(), 0x00000000)
        )
        bottomFadeView.visibility = View.INVISIBLE
        addView(bottomFadeView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, fadeH, Gravity.BOTTOM))

        // ── Scroll listener: auto-scroll pausing + fade visibility ────────
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) autoScrollPaused = true
            }
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val canScrollUp   = rv.canScrollVertically(-1)
                val canScrollDown = rv.canScrollVertically(1)
                topFadeView.visibility    = if (canScrollUp)   View.VISIBLE else View.INVISIBLE
                bottomFadeView.visibility = if (canScrollDown) View.VISIBLE else View.INVISIBLE
                if (!canScrollDown) autoScrollPaused = false
            }
        })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // If a non-Standard style was set before the view was laid out,
        // the bitmap was built at 1px wide. Rebuild now that we have real dimensions.
        if (w > 0 && waveformStyle !is WaveformStyle.Standard) {
            rebuildBackgroundBitmap(waveformStyle)
        }
    }

    // ── Public data API ───────────────────────────────────────────────────────

    /** Set (or replace) the full amplitude array. Triggers a full line rebuild. */
    fun setAmplitudes(amps: FloatArray) {
        amplitudes  = amps
        rebuildItemList()
    }

    /** Update the total recording duration. Adds new lines if needed. */
    fun setDurationMs(durationMs: Long) {
        if (durationMs == totalDurationMs) return
        totalDurationMs = durationMs
        rebuildItemList()
    }

    /** Update the playhead position. Triggers overlay-only redraws. */
    fun setPlayheadMs(positionMs: Long) {
        if (positionMs == playheadMs) return
        val prevLineIndex    = lineIndexForMs(playheadMs)
        val currentLineIndex = lineIndexForMs(positionMs)
        playheadMs = positionMs

        val minLine = minOf(prevLineIndex.coerceAtLeast(0), currentLineIndex)
        val maxLine = maxOf(prevLineIndex.coerceAtLeast(0), currentLineIndex)

        for (lineIndex in minLine..maxLine) {
            notifyLineAt(lineIndex, fullRebuild = false)
        }

        // If the background is clipped to the unplayed region, the decoration
        // boundary moves with the playhead — invalidate decorations so onDraw
        // fires on the next frame without waiting for a scroll event.
        if (BACKGROUND_UNPLAYED_ONLY && backgroundDecoration != null) {
            recyclerView.invalidateItemDecorations()
        }
    }

    /** Replace the entire mark list. */
    fun setMarks(marks: List<WaveformMark>) {
        markStore.clear()
        marks.forEach { markStore[it.positionMs] = it }
        notifyAllVisibleLines()
    }

    /** Update which mark is selected. Triggers overlay-only redraws. */
    fun setSelectedMarkId(id: Long?) {
        if (id == selectedMarkId) return
        selectedMarkId = id
        notifyAllVisibleLines()
    }

    /**
     * Atomically replace the mark list and update the selected mark ID in a
     * single pass. Prefer this over calling [setMarks] + [setSelectedMarkId]
     * separately whenever both values originate from the same upstream combine()
     * — it eliminates the one-frame window where the mark store and the
     * selection are out of sync (visible as a brief colour flash on the Record
     * tab when a nudge-forward clamps a mark to the recording boundary).
     */
    fun setMarksAndSelectedId(marks: List<WaveformMark>, selectedId: Long?) {
        markStore.clear()
        marks.forEach { markStore[it.positionMs] = it }
        selectedMarkId = selectedId
        // A mark clamped to elapsedMs can land beyond totalDurationMs when the
        // throttled pushAmplitude hasn't caught up yet. Bump totalDurationMs here
        // so the last line's effectiveEndMs always covers every mark position.
        marks.maxOfOrNull { it.positionMs }?.let { maxPos ->
            if (maxPos > totalDurationMs) totalDurationMs = maxPos
        }
        notifyAllVisibleLines()
    }

    // ── Live recording API (Record tab) ───────────────────────────────────────

    /**
     * Push a single amplitude sample from the recorder. Call this at whatever
     * rate the RecordingService emits samples. Internally accumulates samples
     * and updates [totalDurationMs] to extend lines as needed.
     *
     * [elapsedMs] is the recording's current elapsed time when this sample
     * was captured — used to keep [totalDurationMs] in sync.
     */
    fun pushAmplitude(amplitude: Float, elapsedMs: Long) {
        // Always accumulate — every sample contributes to waveform accuracy.
        liveAmplitudes.add(amplitude.coerceIn(0f, 1f))
        if (elapsedMs > totalDurationMs) {
            totalDurationMs = elapsedMs
            extendLinesIfNeeded()
        }
        // Throttle bitmap rebuilds to ~4 fps. The live boundary line redraws
        // constantly during recording; rebuilding on every amplitude tick
        // (which can be 10+ Hz) wastes CPU on imperceptible updates.
        val now = System.currentTimeMillis()
        if (now - lastRedrawMs >= LIVE_REDRAW_INTERVAL_MS) {
            lastRedrawMs = now
            val lastLineIndex = adapter.itemCount - 1
            if (lastLineIndex >= 0) notifyLineAt(lastLineIndex, fullRebuild = true)
            if (BACKGROUND_UNPLAYED_ONLY && backgroundDecoration != null) {
                recyclerView.invalidateItemDecorations()
            }
        }
    }

    /** Clear all live data (call on recording stop or cancel). */
    fun clearLiveData() {
        liveAmplitudes.clear()
        totalDurationMs = 0L
        lastRedrawMs    = 0L
        autoScrollPaused = false
        amplitudes      = null
        markStore.clear()
        selectedMarkId  = null
        playheadMs      = -1L
        adapter.submitItems(emptyList())
    }

    // ── Scrolling ─────────────────────────────────────────────────────────────

    /** Smoothly scroll so that the line containing [positionMs] is visible. */
    fun scrollToMs(positionMs: Long) {
        val adapterPos = adapterPositionForMs(positionMs)
        if (adapterPos >= 0) recyclerView.smoothScrollToPosition(adapterPos)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun rebuildItemList() {
        val items = buildLineItems(totalDurationMs, secondsPerLine)
        adapter.submitItems(items)
    }

    /**
     * Appends only new lines without rebuilding the whole list.
     * Called during live recording to avoid rebinding all existing lines.
     */
    private fun extendLinesIfNeeded() {
        val needed = buildLineItems(totalDurationMs, secondsPerLine)
        if (needed.size > adapter.itemCount) {
            adapter.submitItems(needed)
            // Auto-scroll to the new last line, unless the user has scrolled up
            // to review earlier content. Uses smooth scroll so the transition
            // isn't a jarring jump — at most one line height per expansion.
            if (!autoScrollPaused) {
                recyclerView.smoothScrollToPosition(adapter.itemCount - 1)
            }
        }
    }

    /** Notify all currently attached ViewHolders to redraw their overlay (cheap). */
    private fun notifyAllVisibleLines() {
        // Iterate attached children directly rather than using
        // findViewHolderForAdapterPosition(), which can return null for positions
        // within the visible range during layout passes — silently skipping those
        // lines and leaving stale mark colours on screen.
        for (i in 0 until recyclerView.childCount) {
            val vh = recyclerView.getChildViewHolder(recyclerView.getChildAt(i))
                    as? WaveformLineAdapter.LineViewHolder
            vh?.updateOverlay(selectedMarkId, playheadMs)
        }
    }

    /**
     * Notify the ViewHolder for a specific logical line.
     * [fullRebuild] = true means the bitmap needs to be redrawn (played/unplayed
     * split changed); false means overlay-only redraw.
     */
    private fun notifyLineAt(lineIndex: Int, fullRebuild: Boolean) {
        val adapterPos = adapterPositionForLine(lineIndex)
        if (adapterPos < 0) return
        val vh = recyclerView.findViewHolderForAdapterPosition(adapterPos)
                as? WaveformLineAdapter.LineViewHolder ?: return
        if (fullRebuild) {
            adapter.bindLineViewHolder(vh, adapterPos)
        } else {
            vh.updateOverlay(selectedMarkId, playheadMs)
        }
    }

    private fun lineIndexForMs(ms: Long): Int {
        if (ms < 0L || secondsPerLine <= 0) return -1
        return (ms / (secondsPerLine * 1000L)).toInt()
    }

    /** Maps a logical line index to its current adapter position. */
    private fun adapterPositionForLine(lineIndex: Int): Int =
        adapter.items.indexOfFirst { it is WaveformItem.Line && it.index == lineIndex }

    /** Maps a ms position to the adapter position of the containing line. */
    private fun adapterPositionForMs(ms: Long): Int =
        adapterPositionForLine(lineIndexForMs(ms))

    // ── Adapter ───────────────────────────────────────────────────────────────

    inner class WaveformLineAdapter : RecyclerView.Adapter<WaveformLineAdapter.LineViewHolder>() {

        var items: List<WaveformItem.Line> = emptyList()
            private set

        fun submitItems(newItems: List<WaveformItem.Line>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineViewHolder {
            val lineView = WaveformLineView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    LINE_HEIGHT_DP_TO_PX(context)
                )
            }
            return LineViewHolder(lineView)
        }

        override fun onBindViewHolder(holder: LineViewHolder, position: Int) {
            bindLineViewHolder(holder, position)
        }

        fun bindLineViewHolder(holder: LineViewHolder, position: Int) {
            val item = items.getOrNull(position) ?: return

            // For the live boundary line (last item), item.endMs was frozen at the
            // moment the item was first added to the list by extendLinesIfNeeded().
            // totalDurationMs is kept current by pushAmplitude, so always use it
            // for the last line. For completed lines and the listen tab, item.endMs
            // and totalDurationMs are equivalent.
            val effectiveEndMs = if (position == items.lastIndex)
                totalDurationMs.coerceAtLeast(item.endMs)
            else
                item.endMs

            // During live recording, amplitudes (the pre-extracted array) is null.
            // Use liveAmplitudes, deriving samplesPerSecond from the actual sample
            // count and elapsed time so amplitudeIndexForX maps correctly.
            val amps: FloatArray?
            val sps: Int
            if (amplitudes != null) {
                amps = amplitudes
                sps  = WaveformExtractor.SAMPLES_PER_SECOND
            } else if (liveAmplitudes.isNotEmpty() && totalDurationMs > 0) {
                amps = liveAmplitudes.toFloatArray()
                sps  = (liveAmplitudes.size * 1000L / totalDurationMs)
                    .toInt().coerceAtLeast(1)
            } else {
                amps = null
                sps  = WaveformExtractor.SAMPLES_PER_SECOND
            }

            // scaleToFill only applies to the Listen tab (showPlayedSplit) and only
            // when the recording fits on a single line. Multi-line recordings and all
            // Record tab lines always render proportionally.
            val isSingleLine    = totalDurationMs <= secondsPerLine * 1000L
            val lineScaleToFill = scaleToFill && isSingleLine
            val lineWindowMs    = if (lineScaleToFill) totalDurationMs else secondsPerLine * 1000L

            holder.lineView.bind(
                startMs          = item.startMs,
                endMs            = effectiveEndMs,
                lineWindowMs     = lineWindowMs,
                amplitudes       = amps,
                samplesPerSecond = sps,
                markStore        = markStore,
                selectedMarkId   = selectedMarkId,
                playheadMs       = playheadMs,
                showPlayedSplit  = showPlayedSplit,
                scaleToFill      = lineScaleToFill,
                showLineRail     = showLineRail,
                waveformStyle    = waveformStyle
            )
        }

        inner class LineViewHolder(val lineView: WaveformLineView) :
            RecyclerView.ViewHolder(lineView) {

            private val gestureDetector = GestureDetector(
                lineView.context,
                object : GestureDetector.SimpleOnGestureListener() {

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        fireTimeSelected(e.x, WaveformTapType.TAP)
                        return true
                    }

                    override fun onLongPress(e: MotionEvent) {
                        fireTimeSelected(e.x, WaveformTapType.LONG_PRESS)
                    }

                    private fun fireTimeSelected(x: Float, type: WaveformTapType) {
                        onTimeSelected?.invoke(lineView.xToMs(x), type)
                    }
                }
            )

            init {
                lineView.setOnTouchListener { _, event ->
                    gestureDetector.onTouchEvent(event)
                    true
                }
            }

            fun updateOverlay(selectedMarkId: Long?, playheadMs: Long) {
                lineView.updateOverlay(selectedMarkId, playheadMs)
            }
        }
    }

    // ── Background decoration ─────────────────────────────────────────────────

    /**
     * Installs or removes the [BackgroundDecoration] to match [style].
     * Called whenever [waveformStyle] changes.
     */
    private fun updateBackgroundDecoration(style: WaveformStyle) {
        // Remove any existing decoration first.
        backgroundDecoration?.let { recyclerView.removeItemDecoration(it) }
        backgroundDecoration = null
        backgroundBitmap?.recycle()
        backgroundBitmap = null

        if (style !is WaveformStyle.Standard) {
            rebuildBackgroundBitmap(style)
            BackgroundDecoration().also {
                backgroundDecoration = it
                recyclerView.addItemDecoration(it)
            }
        }
    }

    /**
     * Builds [backgroundBitmap] for the given [style].
     *
     * The bitmap is intentionally [BACKGROUND_BITMAP_HEIGHT_LINES] times the
     * height of a single line.  When the decoration draws each item, it reads
     * a vertical slice of this bitmap offset by the item's position in the
     * list — so adjacent lines show different parts of the scene, and the
     * image only repeats after [BACKGROUND_BITMAP_HEIGHT_LINES] lines.
     *
     * TODO: Implement rendering for each WaveformStyle variant:
     *   WaveformStyle.Sky  — render a day or night sky scene based on
     *                        the current theme + invertTheme flag.
     *                        Consider checking Resources.Configuration.uiMode
     *                        here, or accepting an isDarkTheme: Boolean param
     *                        passed from ListenFragment's collect block.
     */
    private fun rebuildBackgroundBitmap(style: WaveformStyle) {
        val lineH   = LINE_HEIGHT_DP_TO_PX(context)
        val bitmapW = width.coerceAtLeast(1)
        val isDark  = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val bmp = when (style) {
            is WaveformStyle.Sky -> {
                val nightMode = if (style.invertTheme) !isDark else isDark
                if (nightMode) buildNightBitmap(bitmapW, lineH)
                else           buildDayBitmap(bitmapW, lineH)
            }
            is WaveformStyle.Standard -> return  // unreachable — guarded by caller
        }

        backgroundBitmap?.recycle()
        backgroundBitmap = bmp
    }

// ── Night sky ─────────────────────────────────────────────────────────────────

    private fun buildNightBitmap(w: Int, lineH: Int): Bitmap {
        val bmpH = (lineH * NIGHT_BG_HEIGHT_LINES).toInt()
        val bmp  = Bitmap.createBitmap(w, bmpH, Bitmap.Config.ARGB_8888)
        val c    = Canvas(bmp)
        val d    = resources.displayMetrics.density

        // Sky gradient — near-black at top fading to deep navy at bottom
        c.drawRect(
            0f, 0f, w.toFloat(), bmpH.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, bmpH.toFloat(),
                    intArrayOf(0xFF060618.toInt(), 0xFF0F0F38.toInt(), 0xFF1A1A50.toInt()),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        )

        // Stars — seeded for determinism, wrapped at vertical seams
        val rng       = Random(STAR_SEED)
        val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        repeat(STAR_COUNT) {
            val x      = rng.nextFloat() * w
            val y      = rng.nextFloat() * bmpH
            val radius = (STAR_MIN_RADIUS_DP + rng.nextFloat() *
                    (STAR_MAX_RADIUS_DP - STAR_MIN_RADIUS_DP)) * d
            val alpha  = 155 + rng.nextInt(100)       // 155–254: varied brightness
            val rComp  = 235 + rng.nextInt(21)         // 235–255
            val gComp  = 235 + rng.nextInt(21)
            val bComp  = 220 + rng.nextInt(36)         // slightly cooler blue floor
            starPaint.color = Color.argb(alpha, rComp, gComp, bComp)

            c.drawCircle(x, y, radius, starPaint)
            // Seam-wrap: mirror at the bottom edge and the top edge so the
            // tiling cut is invisible as lines scroll into view
            if (y + radius > bmpH) c.drawCircle(x, y - bmpH, radius, starPaint)
            if (y - radius < 0f)   c.drawCircle(x, y + bmpH, radius, starPaint)
        }

        return bmp
    }

// ── Day sky ───────────────────────────────────────────────────────────────────

    private fun buildDayBitmap(w: Int, lineH: Int): Bitmap {
        val bmpH = (lineH * DAY_BG_HEIGHT_LINES).toInt()
        val bmp  = Bitmap.createBitmap(w, bmpH, Bitmap.Config.ARGB_8888)
        val c    = Canvas(bmp)

        // Four independent cloud strips stacked vertically, one per line height.
        // Each is a complete composition so no cloud is ever sliced at a seam.
        CLOUD_SEEDS.forEachIndexed { i, seed ->
            val stripTop = i * lineH
            c.save()
            c.translate(0f, stripTop.toFloat())
            drawCloudStrip(c, w, lineH, seed)
            c.restore()
        }

        return bmp
    }

    private fun drawCloudStrip(c: Canvas, w: Int, h: Int, seed: Long) {
        // Bright cerulean sky — peeks through gaps between cloud puffs
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(),
            Paint().apply { color = 0xFF4FC3F7.toInt() })

        val rng      = Random(seed)
        val puffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val oval      = RectF()

        val numClouds = 4 + rng.nextInt(2)   // 4–5 clouds per strip
        repeat(numClouds) { idx ->
            // Space centres evenly with slight jitter
            val fraction = (idx + 0.5f + (rng.nextFloat() - 0.5f) * 0.35f) / numClouds
            val cx       = fraction * w
            val cy       = h * (0.35f + rng.nextFloat() * 0.30f)
            val baseR    = h * (0.35f + rng.nextFloat() * 0.20f)
            val puffs    = 4 + rng.nextInt(3)   // 4–6 puffs per cloud

            repeat(puffs) { p ->
                val angle  = (p.toFloat() / puffs) * 2f * Math.PI.toFloat()
                val spread = baseR * (0.5f + rng.nextFloat() * 0.5f)
                val px     = cx + cos(angle.toDouble()).toFloat() * spread * 0.9f
                val py     = cy + sin(angle.toDouble()).toFloat() * spread * 0.45f
                val pr     = baseR * (0.55f + rng.nextFloat() * 0.45f)
                val shade  = 245 + rng.nextInt(11)   // 245–255: white to very pale grey
                val alpha  = 220 + rng.nextInt(35)   // 220–254: slightly translucent edges
                puffPaint.color = Color.argb(alpha, shade, shade, shade)
                oval.set(px - pr, py - pr * 0.72f, px + pr, py + pr * 0.72f)
                c.drawOval(oval, puffPaint)
            }

            // Bright white centre to unify each cluster
            puffPaint.color = Color.argb(255, 255, 255, 255)
            oval.set(cx - baseR * 0.55f, cy - baseR * 0.45f,
                cx + baseR * 0.55f, cy + baseR * 0.45f)
            c.drawOval(oval, puffPaint)
        }
    }

    /**
     * RecyclerView.ItemDecoration that paints [backgroundBitmap] behind each
     * waveform line before the item views are drawn.
     *
     * For each visible item, the decoration reads a vertical slice of
     * [backgroundBitmap] whose Y offset is:
     *
     *   yOffset = (lineIndex * lineHeightPx) % backgroundBitmap.height
     *
     * This makes the background scroll continuously with the list content
     * while repeating only every [BACKGROUND_BITMAP_HEIGHT_LINES] lines.
     */
    private inner class BackgroundDecoration : RecyclerView.ItemDecoration() {

        private val srcRect = android.graphics.Rect()
        private val dstRect = android.graphics.Rect()
        private val backgroundPaint = Paint().apply { alpha = BACKGROUND_ALPHA }

        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val bmp   = backgroundBitmap ?: return
            val lineH = LINE_HEIGHT_DP_TO_PX(parent.context)
            val rulerH = if (BACKGROUND_EXTENDS_UNDER_RULER) 0
            else (WaveformLineView.RULER_HEIGHT_DP *
                    parent.context.resources.displayMetrics.density).toInt()

            for (i in 0 until parent.childCount) {
                val child  = parent.getChildAt(i) ?: continue
                val holder = parent.getChildViewHolder(child)
                        as? WaveformLineAdapter.LineViewHolder ?: continue
                val adapterPos = holder.bindingAdapterPosition
                    .takeIf { it != RecyclerView.NO_ID.toInt() } ?: continue
                val lineItem = adapter.items.getOrNull(adapterPos) ?: continue

                val yOffset = ((lineItem.index * lineH) % bmp.height)
                    .coerceIn(0, (bmp.height - lineH).coerceAtLeast(0))

                srcRect.set(0, yOffset + rulerH, bmp.width, yOffset + lineH)
                dstRect.set(child.left, child.top + rulerH, child.right, child.bottom)

                // ── Unplayed-only clip ────────────────────────────────────────────────
                // When BACKGROUND_UNPLAYED_ONLY is true and playback is active, restrict
                // the background to the region right of the playhead for this line.
                val clipLeft: Float = if (BACKGROUND_UNPLAYED_ONLY) {
                    val boundaryMs: Long = if (showPlayedSplit) {
                        // Listen tab — boundary is the playhead
                        if (playheadMs < 0L) return@onDraw  // shouldn't happen but guard it
                        playheadMs
                    } else {
                        // Record tab — boundary is the recording head
                        totalDurationMs
                    }

                    when {
                        boundaryMs <= lineItem.startMs -> child.left.toFloat()   // fully future
                        boundaryMs >= lineItem.endMs   -> child.right.toFloat()  // fully filled
                        else -> {
                            val actualWindowMs   = (lineItem.endMs - lineItem.startMs).coerceAtLeast(1L)
                            val isSingleLine     = totalDurationMs <= secondsPerLine * 1000L
                            val filledWidth      = if (scaleToFill && isSingleLine) {
                                child.width.toFloat()
                            } else {
                                child.width * (actualWindowMs.toFloat() / (secondsPerLine * 1000L))
                            }
                            val fraction = (boundaryMs - lineItem.startMs).toFloat() / actualWindowMs
                            child.left + fraction * filledWidth
                        }
                    }
                } else {
                    child.left.toFloat()
                }

                if (clipLeft >= child.right) continue  // line is fully played — no background

                c.save()
                c.clipRect(clipLeft, (child.top + rulerH).toFloat(),
                    child.right.toFloat(), child.bottom.toFloat())
                c.drawBitmap(bmp, srcRect, dstRect, backgroundPaint)
                c.restore()
            }
        }
    }

    companion object {
        const val DEFAULT_SECONDS_PER_LINE = 300   // 5 minutes
        private const val LIVE_REDRAW_INTERVAL_MS = 250L   // ~4 fps
        private const val LINE_HEIGHT_DP    = 80
        private const val BOTTOM_PADDING_DP = 56
        private const val FADE_HEIGHT_DP    = 32

        private fun LINE_HEIGHT_DP_TO_PX(context: Context): Int =
            (LINE_HEIGHT_DP * context.resources.displayMetrics.density).toInt()

        // Night sky
        private const val NIGHT_BG_HEIGHT_LINES  = 4.5f   // non-integer → repeat cycle = 9 lines
        private const val STAR_SEED              = 31415L
        private const val STAR_COUNT             = 130
        private const val STAR_MIN_RADIUS_DP     = 0.7f
        private const val STAR_MAX_RADIUS_DP     = 2.2f
        // Day clouds — one seed per layer; change any to redesign that strip
        private val CLOUD_SEEDS = longArrayOf(1001L, 2002L, 3003L, 4004L)
        private const val DAY_BG_HEIGHT_LINES    = 4f
        // ── Contrast / tuning knobs ───────────────────────────────────────────────────
        // These are the two values to reach for first when adjusting the visual feel.
        const val BACKGROUND_ALPHA               = 60   // 0–255; lower = more ghostly sky
        const val BACKGROUND_EXTENDS_UNDER_RULER = false  // true = sky behind ruler strip too

        // When true, the background scene renders only behind the unplayed portion
        // of each line — the sky retreats as the playhead advances, giving a
        // "dreams to reality" transition effect. Set to false to render everywhere.
        const val BACKGROUND_UNPLAYED_ONLY = true
    }
}

/** Tap gesture types emitted by [MultiLineWaveformView.onTimeSelected]. */
enum class WaveformTapType { TAP, LONG_PRESS }