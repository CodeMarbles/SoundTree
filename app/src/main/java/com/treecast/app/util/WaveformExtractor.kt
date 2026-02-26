package com.treecast.app.util

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts a waveform amplitude array from an audio file without full PCM decoding.
 *
 * Strategy: walk every encoded audio frame via [MediaExtractor] and use the
 * compressed frame size as a proxy for signal energy. For AAC/M4A this
 * correlates well with perceived loudness and is 10–50× faster than a full
 * MediaCodec decode — critical for recordings longer than a few minutes.
 *
 * On a 1-hour M4A file expect ~300–800 ms on a mid-range device.
 *
 * The returned array has exactly [targetBars] values in [0f..1f] suitable
 * for passing directly to [com.treecast.app.ui.listen.PlaybackWaveformView.setAmplitudes].
 */
object WaveformExtractor {

    /**
     * @param filePath  Absolute path to the audio file (M4A, MP3, OGG, etc.)
     * @param targetBars  Number of amplitude bars to produce. 500 is a good
     *                    default — matches a typical 360–400 dp waveform view
     *                    with 3 dp bars and 2 dp gaps.
     * @return Normalized FloatArray of length [targetBars], values in [0f..1f].
     *         Falls back to a flat 0.5 array if the file can't be read.
     */
    suspend fun extract(filePath: String, targetBars: Int = 500): FloatArray =
        withContext(Dispatchers.IO) {
            extractInternal(filePath, targetBars)
        }

    private fun extractInternal(filePath: String, targetBars: Int): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(filePath)

            // ── Find the first audio track ──────────────────────────────
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return flatFallback(targetBars)

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)

            // Duration in microseconds; guard against malformed files
            val durationUs: Long = runCatching {
                format.getLong(MediaFormat.KEY_DURATION)
            }.getOrDefault(0L)

            if (durationUs <= 0L) return flatFallback(targetBars)

            // ── Bucket encoded frame sizes by time ──────────────────────
            // Each bucket covers (durationUs / targetBars) microseconds.
            // We keep the *maximum* encoded frame size in each bucket —
            // this preserves transient peaks better than averaging.
            val bucketSizes = IntArray(targetBars)   // max encoded bytes per bar
            val bucketUs    = durationUs.toDouble() / targetBars

            while (true) {
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0L) break                 // end of stream

                val bucketIndex = ((sampleTimeUs / bucketUs).toInt())
                    .coerceIn(0, targetBars - 1)

                val frameSize = extractor.sampleSize.toInt().coerceAtLeast(0)
                if (frameSize > bucketSizes[bucketIndex]) {
                    bucketSizes[bucketIndex] = frameSize
                }

                if (!extractor.advance()) break
            }

            // ── Fill any empty buckets by interpolating neighbours ──────
            // Gaps can occur around seek boundaries or in silence.
            fillGaps(bucketSizes)

            // ── Normalise to [0f..1f] ───────────────────────────────────
            val maxSize = bucketSizes.maxOrNull()?.coerceAtLeast(1) ?: 1
            return FloatArray(targetBars) { i ->
                (bucketSizes[i].toFloat() / maxSize).coerceIn(0.07f, 1f)
            }

        } catch (e: Exception) {
            return flatFallback(targetBars)
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Forward-then-backward fill to replace zero-sized buckets.
     * Pure silence stays as 0 (the normalise step will floor it to 0.07f).
     */
    private fun fillGaps(sizes: IntArray) {
        // Forward pass: carry last non-zero value into zero slots
        var last = 0
        for (i in sizes.indices) {
            if (sizes[i] > 0) last = sizes[i]
            else if (last > 0) sizes[i] = last / 2   // half-power for silence patches
        }
        // Backward pass for any leading zeros
        last = 0
        for (i in sizes.indices.reversed()) {
            if (sizes[i] > 0) last = sizes[i]
            else if (last > 0) sizes[i] = last / 2
        }
    }

    private fun flatFallback(bars: Int) = FloatArray(bars) { 0.5f }
}