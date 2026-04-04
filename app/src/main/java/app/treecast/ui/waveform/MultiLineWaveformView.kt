package app.treecast.ui.waveform

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.treecast.R
import app.treecast.ui.waveform.MultiLineWaveformView.WaveformLineAdapter
import app.treecast.util.WaveformExtractor
import app.treecast.util.themeColor
import java.util.TreeMap
import kotlin.math.roundToInt

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
            setCandidateMarkMs(null)
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
     * When false, top and bottom edge fades are suppressed regardless of scroll
     * state. Set to false when the splitter snaps up (waveform shrinks to one
     * line — fades are meaningless at that height), and back to true on
     * snap-down or free drag.
     * Call sites: [ListenFragment.snapTo], [ListenFragment.handleSplitterTouch].
     */
    var fadesEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) {
                topFadeView.visibility    = View.INVISIBLE
                bottomFadeView.visibility = View.INVISIBLE
            }
        }

    /**
     * When false, playhead position updates no longer drive the RecyclerView
     * scroll position. Fades and scroll style (pinned vs smooth) remain
     * governed independently by [fadesEnabled].
     *
     * Set to false on free-drag entry so the waveform stays wherever the user
     * left it while they interact with the marks panel. Restored to true by
     * both chevron snap paths.
     *
     * Call sites: [ListenFragment.handleSplitterTouch], [ListenFragment.snapTo].
     */
    var autoScrollEnabled: Boolean = true

    /**
     * Height of a single waveform row in pixels.
     * Returns 0 if no rows have been laid out yet (safe fallback in callers).
     */
    val lineHeightPx: Int
        get() = recyclerView.getChildAt(0)?.height ?: 0

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
     * Draw-time display parameters for the background decoration.
     *
     * Changing this does NOT require a bitmap rebuild — it calls
     * [RecyclerView.invalidateItemDecorations] immediately so the next frame
     * picks up the new values.
     *
     * Wired from [ListenFragment] by collecting [MainViewModel.waveformDisplayConfig].
     */
    var waveformDisplayConfig: WaveformDisplayConfig = WaveformDisplayConfig()
        set(value) {
            if (field == value) return
            field = value
            if (backgroundDecoration != null) recyclerView.invalidateItemDecorations()
        }

    /**
     * Called when the user taps a position on any line.
     *
     * @param positionMs  Tapped position in milliseconds (recording-relative).
     * @param type        [WaveformTapType.TAP] or [WaveformTapType.LONG_PRESS].
     */
    var onTimeSelected: ((positionMs: Long, type: WaveformTapType) -> Unit)? = null

    /**
     * Called when the user taps within [MARK_HIT_SLOP_DP] of an existing mark.
     * Fires instead of [onTimeSelected].
     * @param markId  The [WaveformMark.id] of the tapped mark.
     */
    var onMarkTapped: ((markId: Long) -> Unit)? = null

    /**
     * Called when the user taps "Add mark" on the candidate banner.
     * @param positionMs  The candidate position to confirm.
     */
    var onCandidateConfirm: ((positionMs: Long) -> Unit)? = null

    /**
     * Called when the user taps "✕" on the candidate banner or the candidate
     * is otherwise cancelled. No position argument — the candidate is gone.
     */
    var onCandidateCancel: (() -> Unit)? = null

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

    /** Candidate mark position. Drives grey indicator + confirmation banner. */
    private var candidateMarkMs: Long? = null

    // ── Background surface (stylized waveform styles) ──────────────────────

    /**
     * Background scene for the current [waveformStyle].
     *
     * Wraps the rendered [Bitmap] together with its tile height (as a multiple
     * of one line height) so the decoration always has the correct offset
     * formula regardless of which renderer built it.
     *
     * Null when [waveformStyle] is [WaveformStyle.Standard] or before the first
     * layout pass triggers [rebuildBackgroundBitmap].
     */
    private var backgroundBitmap: BackgroundBitmap? = null

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
    // ── Candidate banner (overlaid at top of FrameLayout) ─────────────────────
    private val candidateBannerLabel: TextView
    private val candidateBannerConfirm: TextView
    private val candidateBannerCancel:  TextView
    private val candidateBanner: LinearLayout

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
        // Resolve the waveform fade color from the current theme so the fade
        // blends correctly in both light and dark modes (and future custom themes).
        val tv = android.util.TypedValue()
        context.theme.resolveAttribute(R.attr.colorWaveformFade, tv, true)
        val bgOpaque = (tv.data and 0x00FFFFFF.toInt()) or 0xCC000000.toInt()
        val bgClear  = tv.data and 0x00FFFFFF.toInt()

        topFadeView.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(bgOpaque, bgClear)
        )
        topFadeView.visibility = View.INVISIBLE
        addView(topFadeView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, fadeH, Gravity.TOP))

        bottomFadeView.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(bgOpaque, bgClear)
        )
        bottomFadeView.visibility = View.INVISIBLE
        addView(bottomFadeView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, fadeH, Gravity.BOTTOM))

        // ── Candidate mark confirmation banner ───────────────────────────────────
        // Overlaid at the top-center of the FrameLayout. Visible only when a
        // candidate mark position is pending confirmation on the Record tab.
        candidateBannerLabel = TextView(context).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            setPadding((4 * dp).toInt(), 0, (10 * dp).toInt(), 0)
        }

        candidateBannerConfirm = TextView(context).apply {
            text = "✓"
            textSize = 16f
            setTextColor(context.themeColor(R.attr.colorAccent))
            setPadding((8 * dp).toInt(), (2 * dp).toInt(), (8 * dp).toInt(), (2 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f * dp
                setColor(android.graphics.Color.argb(60, 255, 255, 255))
            }
            setOnClickListener {
                candidateMarkMs?.let { ms -> onCandidateConfirm?.invoke(ms) }
                setCandidateMarkMs(null)
            }
        }

        candidateBannerCancel = TextView(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(android.graphics.Color.argb(180, 220, 220, 220))
            setPadding((8 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt(), (2 * dp).toInt())
            setOnClickListener {
                onCandidateCancel?.invoke()
                setCandidateMarkMs(null)
            }
        }

        candidateBanner = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            background  = GradientDrawable().apply {
                shape         = GradientDrawable.RECTANGLE
                cornerRadius  = 999f * dp
                setColor(android.graphics.Color.argb(230, 45, 45, 55))
                setStroke((1f * dp).toInt(), android.graphics.Color.argb(160, 130, 130, 150))
            }
            setPadding((12 * dp).toInt(), (5 * dp).toInt(), (8 * dp).toInt(), (5 * dp).toInt())
            elevation = 4 * dp
            addView(candidateBannerLabel)
            addView(candidateBannerConfirm)
            addView(candidateBannerCancel.also {
                (it.layoutParams as? MarginLayoutParams)?.marginStart = (6 * dp).toInt()
            })
            visibility = View.GONE
        }

        addView(
            candidateBanner,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity   = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = (10 * dp).toInt()
            }
        )

        // ── Scroll listener: auto-scroll pausing + fade visibility ────────
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) autoScrollPaused = true
            }
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val canScrollDown = rv.canScrollVertically(1)
                if (fadesEnabled) {
                    val canScrollUp = rv.canScrollVertically(-1)
                    topFadeView.visibility    = if (canScrollUp)   View.VISIBLE else View.INVISIBLE
                    bottomFadeView.visibility = if (canScrollDown) View.VISIBLE else View.INVISIBLE
                }
                // autoScrollPaused reset must run even when fades are disabled.
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
        if (waveformDisplayConfig.unplayedOnly && backgroundDecoration != null) {
            recyclerView.invalidateItemDecorations()
        }

        // Snap-up mode: the RecyclerView is shrunk to roughly one line height
        // so autoScrollPaused must be bypassed — always keep the playhead line
        // visible. scrollToMs() calls smoothScrollToPosition() which is a no-op
        // when the target item is already on screen, so there is no overhead
        // while the playhead stays on the same line.
        // autoScrollEnabled is false during free drag so the waveform stays
        // wherever the user left it regardless of playhead movement.
        if (!fadesEnabled && autoScrollEnabled && positionMs >= 0L) {
            scrollToMs(positionMs)
        }
    }

    // ── Marks ───────────────────────────────────────

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

    /**
     * Sets or clears the candidate mark position.
     *
     * When set, draws a grey provisional mark indicator on the appropriate
     * waveform line and shows the confirmation banner at the top of the view.
     * Passing null hides the banner and clears the indicator.
     *
     * Auto-cleared when [secondsPerLine] changes (line layout shifts).
     */
    fun setCandidateMarkMs(positionMs: Long?) {
        candidateMarkMs = positionMs
        if (positionMs != null) {
            candidateBannerLabel.text = context.getString(R.string.mark_label_candidate, formatCandidateMs(positionMs))
            candidateBanner.visibility = View.VISIBLE
        } else {
            candidateBanner.visibility = View.GONE
        }
        notifyAllVisibleLines()
    }

    private fun formatCandidateMs(ms: Long): String {
        val totalSecs = ms / 1000
        val h = totalSecs / 3600
        val m = (totalSecs % 3600) / 60
        val s = totalSecs % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
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
            if (waveformDisplayConfig.unplayedOnly && backgroundDecoration != null) {
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
        if (adapterPos < 0) return
        if (!fadesEnabled) {
            // Snap-up (single-line view): always pin the target item to offset 0.
            // scrollToPositionWithOffset is immediate and fully deterministic —
            // it eliminates the oscillation caused by repeated smoothScrollToPosition
            // calls when the RecyclerView height slightly exceeds one line height.
            (recyclerView.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(adapterPos, 0)
        } else {
            recyclerView.smoothScrollToPosition(adapterPos)
        }
    }

    /**
     * Immediately scroll so the line containing [positionMs] sits flush at
     * offset 0 (top edge of the RecyclerView). Bypasses [autoScrollPaused]
     * so it works in both SNAP_UP and SNAP_DOWN regardless of prior user
     * scrolling. Use this only for programmatic mark-jump navigation.
     */
    fun jumpScrollToMs(positionMs: Long) {
        val adapterPos = adapterPositionForMs(positionMs)
        if (adapterPos < 0) return
        (recyclerView.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(adapterPos, 0)
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
            vh?.updateOverlay(selectedMarkId, playheadMs, candidateMarkMs)
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
            vh.updateOverlay(selectedMarkId, playheadMs, candidateMarkMs)
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

        override fun onViewAttachedToWindow(holder: LineViewHolder) {
            super.onViewAttachedToWindow(holder)
            holder.updateOverlay(selectedMarkId, playheadMs, candidateMarkMs)
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
                waveformStyle    = waveformStyle,
                candidateMarkMs  = candidateMarkMs,
            )
        }

        @SuppressLint("ClickableViewAccessibility")
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
                        val hitslopPx = lineView.resources.displayMetrics.density * MARK_HIT_SLOP_DP
                        val hitMarkId = lineView.findMarkNear(x, hitslopPx)
                        if (hitMarkId != null) {
                            onMarkTapped?.invoke(hitMarkId)
                        } else {
                            onTimeSelected?.invoke(lineView.xToMs(x), type)
                        }
                    }
                }
            )

            init {
                lineView.setOnTouchListener { _, event ->
                    gestureDetector.onTouchEvent(event)
                    true
                }
            }

            fun updateOverlay(selectedMarkId: Long?, playheadMs: Long, candidateMarkMs: Long?) {
                lineView.updateOverlay(selectedMarkId, playheadMs, candidateMarkMs)
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
        backgroundBitmap?.bitmap?.recycle()
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
     * Builds [backgroundBitmap] for the given [style] by dispatching to the
     * appropriate renderer.
     *
     * Each renderer returns a [BackgroundBitmap] that bundles the raw [Bitmap]
     * with its tile height (as a multiple of one line height).  The decoration
     * uses this to compute the Y offset for each line:
     *
     *   yOffset = (lineIndex * lineHeightPx) % bitmap.height
     *
     * Called when [waveformStyle] changes and from [onSizeChanged] to handle
     * the case where the first style assignment happened before layout.
     */
    private fun rebuildBackgroundBitmap(style: WaveformStyle) {
        val lineH   = LINE_HEIGHT_DP_TO_PX(context)
        val w       = width.coerceAtLeast(1)
        val density = resources.displayMetrics.density
        val isDark  = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val result: BackgroundBitmap = when (style) {
            is WaveformStyle.Sky -> {
                val night = if (style.invertTheme) !isDark else isDark
                if (night) SkyBackgroundRenderer.buildNight(w, lineH, density)
                else       SkyBackgroundRenderer.buildDay(w, lineH, density)
            }
            is WaveformStyle.SkyLights -> {
                val night = if (style.invertTheme) !isDark else isDark
                if (night) SkyLightsBackgroundRenderer.buildAurora(w, lineH, density)
                else       SkyLightsBackgroundRenderer.buildRainbow(w, lineH, density)
            }
            is WaveformStyle.Standard -> return  // unreachable — guarded by caller
        }

        backgroundBitmap?.bitmap?.recycle()
        backgroundBitmap = result
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
        // Alpha is applied fresh on every draw so live slider changes take effect
        // without waiting for a style change or bitmap rebuild.
        private val backgroundPaint = Paint()

        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val bgBmp = backgroundBitmap ?: return
            val bmp   = bgBmp.bitmap
            val cfg   = waveformDisplayConfig

            // Update paint alpha from current config on every draw call.
            backgroundPaint.alpha = (cfg.backgroundAlpha * 255f).roundToInt().coerceIn(0, 255)

            val lineH  = LINE_HEIGHT_DP_TO_PX(parent.context)
            val density = parent.context.resources.displayMetrics.density
            val rulerH = if (cfg.extendsUnderRuler) 0
            else (WaveformLineView.RULER_HEIGHT_DP * density).toInt()

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

                // ── Unplayed-only clip ────────────────────────────────────
                // When unplayedOnly is true, restrict the background to the
                // region right of the playhead (Listen tab) or recording head
                // (Record tab) for this line.
                val clipLeft: Float = if (cfg.unplayedOnly) {
                    val boundaryMs: Long = if (showPlayedSplit) {
                        if (playheadMs < 0L) return  // guard: no playhead yet
                        playheadMs
                    } else {
                        totalDurationMs   // Record tab — boundary is the live head
                    }
                    val isSingleLine = totalDurationMs <= secondsPerLine * 1000L
                    val windowMs = if (scaleToFill && isSingleLine) totalDurationMs
                    else secondsPerLine * 1000L
                    when {
                        boundaryMs <= lineItem.startMs ->
                            child.left.toFloat()
                        boundaryMs >= lineItem.startMs + windowMs ->
                            child.right.toFloat()
                        else -> {
                            val railPx       = if (showLineRail) WaveformLineView.RAIL_WIDTH_DP * density else 0f
                            val barStridePx  = WaveformLineView.BAR_STRIDE_DP * density
                            val availableW   = child.width - railPx
                            val rawX         = (boundaryMs - lineItem.startMs).toFloat() / windowMs * availableW
                            child.left + railPx + (rawX / barStridePx).toInt() * barStridePx
                        }
                    }
                } else {
                    child.left.toFloat()
                }

                if (clipLeft >= child.right) continue  // fully played — skip

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
        private const val LIVE_REDRAW_INTERVAL_MS = 250L  // ~4 fps
        private const val LINE_HEIGHT_DP    = 80
        private const val BOTTOM_PADDING_DP = 56
        private const val FADE_HEIGHT_DP    = 32

        private fun LINE_HEIGHT_DP_TO_PX(context: Context): Int =
            (LINE_HEIGHT_DP * context.resources.displayMetrics.density).toInt()

        private const val MARK_HIT_SLOP_DP = 12f
    }
}

/** Tap gesture types emitted by [MultiLineWaveformView.onTimeSelected]. */
enum class WaveformTapType { TAP, LONG_PRESS }