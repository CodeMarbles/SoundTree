package app.treecast.ui.waveform

import kotlin.math.ceil

// ── Line loading state ────────────────────────────────────────────────────────

/**
 * Whether a waveform line's bitmap is ready to display or still being prepared.
 *
 * [LOADING] — shown briefly during zoom reflow. The line renders a skeleton
 *             placeholder instead of real waveform data.
 * [READY]   — normal state; full waveform bars are rendered.
 */
enum class LineState { LOADING, READY }

// ── Adapter item types ────────────────────────────────────────────────────────

/**
 * Sealed hierarchy of items that can appear in [MultiLineWaveformView]'s
 * internal RecyclerView.
 *
 * Currently only [Line] exists. Future insertable types (mark detail cards,
 * transcription blocks, etc.) will be added here as new subclasses without
 * touching the existing [Line] implementation.
 *
 * The structural list of [WaveformItem]s lives inside the adapter. All audio
 * data (amplitude array, marks, playhead position) lives separately and is
 * passed into each ViewHolder at bind time — items are structural descriptors,
 * not data carriers.
 */
sealed class WaveformItem {

    /**
     * A single horizontal strip of waveform covering [startMs]..[endMs].
     *
     * @param index   Zero-based line number. Stable across zoom reflows for
     *                the same time window.
     * @param startMs Start of this line's time window, in milliseconds.
     * @param endMs   End of this line's time window, in milliseconds.
     *                For the last line of an in-progress recording this will
     *                grow over time as the recording extends.
     * @param state   [LineState.LOADING] during zoom reflow; [LineState.READY]
     *                for normal rendering.
     */
    data class Line(
        val index:   Int,
        val startMs: Long,
        val endMs:   Long,
        val state:   LineState = LineState.READY
    ) : WaveformItem()

    // ── Future types — reserved, not yet implemented ──────────────────────────
    //
    // data class MarkDetail(
    //     val markId: Long,
    //     val afterLineIndex: Int   // inserted after this logical line
    // ) : WaveformItem()
    //
    // data class TranscriptionBlock(
    //     val blockId: Long,
    //     val afterLineIndex: Int
    // ) : WaveformItem()
}

// ── Line list builder ─────────────────────────────────────────────────────────

/**
 * Builds the structural list of [WaveformItem.Line] items for a recording of
 * [totalDurationMs] at the given [secondsPerLine] zoom level.
 *
 * This is a pure function with no Android dependencies — easy to unit-test
 * and safe to call on any thread.
 *
 * The last line may be shorter than [secondsPerLine] if the duration is not
 * evenly divisible.
 *
 * Example: 7-minute recording at 5 min/line →
 *   Line(0, 0ms,      300_000ms)
 *   Line(1, 300_000ms, 420_000ms)
 *
 * @param totalDurationMs Total recording length in milliseconds. For a
 *                        live recording this value grows; call this function
 *                        again and diff the result to append new lines.
 * @param secondsPerLine  How many seconds of audio each line represents.
 */
fun buildLineItems(
    totalDurationMs: Long,
    secondsPerLine:  Int
): List<WaveformItem.Line> {
    if (totalDurationMs <= 0L || secondsPerLine <= 0) return emptyList()

    val windowMs   = secondsPerLine * 1000L
    val lineCount  = ceil(totalDurationMs.toDouble() / windowMs).toInt()

    return List(lineCount) { i ->
        WaveformItem.Line(
            index   = i,
            startMs = i * windowMs,
            endMs   = minOf((i + 1) * windowMs, totalDurationMs)
        )
    }
}