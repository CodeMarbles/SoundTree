package com.treecast.app.ui.waveform

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.treecast.app.util.WaveformExtractor
import java.util.TreeMap

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

    // ── Live recording state (Record tab) ─────────────────────────────────────

    /**
     * Accumulates amplitude samples during a live recording.
     * Grown as [pushAmplitude] is called; used to build the last (partial) line.
     */
    private val liveAmplitudes = mutableListOf<Float>()

    /** Wall-clock ms of the last bitmap redraw. Used to throttle [pushAmplitude]. */
    private var lastRedrawMs: Long = 0L

    // ── Views ─────────────────────────────────────────────────────────────────

    private val recyclerView  = RecyclerView(context)
    private val adapter       = WaveformLineAdapter()

    init {
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter       = adapter
        // Remove the default item animator — we don't want flash on overlay updates.
        recyclerView.itemAnimator  = null
        addView(recyclerView, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
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
        }
    }

    /** Clear all live data (call on recording stop or cancel). */
    fun clearLiveData() {
        liveAmplitudes.clear()
        totalDurationMs = 0L
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
        }
    }

    /** Notify all visible ViewHolders to redraw their overlay (cheap). */
    private fun notifyAllVisibleLines() {
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last  = lm.findLastVisibleItemPosition()
        for (pos in first..last) {
            val vh = recyclerView.findViewHolderForAdapterPosition(pos) as? WaveformLineAdapter.LineViewHolder
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
            holder.lineView.bind(
                startMs          = item.startMs,
                endMs            = item.endMs,
                lineWindowMs     = if (items.size > 1) secondsPerLine * 1000L else (item.endMs - item.startMs),
                amplitudes       = amplitudes,
                samplesPerSecond = WaveformExtractor.SAMPLES_PER_SECOND,
                markStore        = markStore,
                selectedMarkId   = selectedMarkId,
                playheadMs       = playheadMs,
                showPlayedSplit  = showPlayedSplit
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

    companion object {
        const val DEFAULT_SECONDS_PER_LINE = 300   // 5 minutes
        private const val LIVE_REDRAW_INTERVAL_MS = 250L   // ~4 fps

        /** Height of each waveform line in the RecyclerView, in dp. */
        private const val LINE_HEIGHT_DP = 80

        private fun LINE_HEIGHT_DP_TO_PX(context: Context): Int =
            (LINE_HEIGHT_DP * context.resources.displayMetrics.density).toInt()
    }
}

/** Tap gesture types emitted by [MultiLineWaveformView.onTimeSelected]. */
enum class WaveformTapType { TAP, LONG_PRESS }