package com.treecast.app.ui.waveform

/**
 * Component-internal representation of a mark for [MultiLineWaveformView].
 *
 * This is deliberately decoupled from [com.treecast.app.data.entities.MarkEntity]
 * so that the waveform component does not depend on the data layer. The fragment
 * is responsible for mapping MarkEntity → WaveformMark when feeding data into
 * the component.
 *
 * As mark data grows richer (labels, durations, sub-classes, etc.), MarkEntity
 * will evolve independently. The fragment's mapping function is the single
 * translation point — the component's internal model stays minimal.
 *
 * @param id         Stable identifier matching [MarkEntity.id]. Used to track
 *                   selection state and future cross-references to inserted
 *                   MarkDetail items in the waveform's RecyclerView.
 * @param positionMs Position in the recording timeline, in milliseconds.
 *                   This is the key used in [MultiLineWaveformView]'s internal
 *                   TreeMap, enabling O(log n) range queries per line.
 *
 * Note: if two marks share the exact same [positionMs], the TreeMap will only
 * retain one. Sub-millisecond collisions are not a realistic concern for a
 * recording app; this is documented as a known limitation rather than handled.
 */
data class WaveformMark(
    val id:         Long,
    val positionMs: Long
)