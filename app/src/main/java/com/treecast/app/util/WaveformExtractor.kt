package com.treecast.app.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Extracts a real waveform amplitude array from an audio file by fully
 * decoding the compressed audio to PCM samples via [MediaCodec].
 *
 * Strategy:
 *   - [MediaExtractor] feeds encoded AAC frames into a [MediaCodec] decoder.
 *   - The decoder outputs raw PCM (16-bit short) buffers.
 *   - Each output buffer carries a presentation timestamp. We use that to
 *     determine which of the time-bucketed samples it belongs to.
 *   - We compute the RMS of all samples in each bucket and keep the peak
 *     across all buffers that land in the same bucket.
 *   - The resulting array is normalised to [0.05f..1f].
 *
 * Resolution:
 *   Samples are produced at [SAMPLES_PER_SECOND] per second of audio. This
 *   is time-based rather than screen-width-based so that the same cache file
 *   can serve any zoom level in MultiLineWaveformView without re-extraction.
 *
 *   At 8 samples/sec:
 *     - 5-minute recording  →  2,400 samples  (~9.4 KB)
 *     - 1-hour recording    → 28,800 samples  (~113 KB)
 *     - 2-hour recording    → 57,600 samples  (~225 KB)
 *
 *   This is Tier-1 resolution. If a future zoom feature needs sub-second
 *   fidelity, a Tier-2 on-demand decode of a small time window can be added
 *   to WaveformExtractor without touching the cache format.
 *
 * Performance (typical mid-range device):
 *   - Hardware AAC decoder is used automatically; < 3 s for a 1-hour file.
 *   - Called on an IO/Worker thread — never the main thread.
 */
object WaveformExtractor {

    /**
     * Samples produced per second of audio. Controls the resolution of the
     * stored waveform. 8 samples/sec gives smooth rendering down to ~1 minute
     * per line on a typical phone screen, which is our current minimum zoom.
     *
     * Do not change this constant without bumping [WaveformCache.CACHE_VERSION]
     * so that existing cached files are regenerated at the new resolution.
     */
    const val SAMPLES_PER_SECOND = 8

    /**
     * @param filePath  Absolute path to the audio file (M4A/AAC, MP3, OGG, …)
     * @return Normalised [FloatArray] of length ⌈durationSecs × [SAMPLES_PER_SECOND]⌉,
     *         values in [0.05f..1f]. Falls back to a flat 0.5 array if the file
     *         cannot be decoded.
     */
    suspend fun extract(filePath: String): FloatArray =
        withContext(Dispatchers.IO) {
            runCatching { decodeWithCodec(filePath) }
                .getOrElse { flatFallback(DEFAULT_FALLBACK_SIZE) }
        }

    // ── Core decode loop ──────────────────────────────────────────────────────

    private fun decodeWithCodec(filePath: String): FloatArray {
        val mediaExtractor = MediaExtractor()
        mediaExtractor.setDataSource(filePath)

        val trackIndex = (0 until mediaExtractor.trackCount).firstOrNull { i ->
            mediaExtractor.getTrackFormat(i)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        } ?: return flatFallback(DEFAULT_FALLBACK_SIZE)

        mediaExtractor.selectTrack(trackIndex)
        val format = mediaExtractor.getTrackFormat(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: return flatFallback(DEFAULT_FALLBACK_SIZE)

        val durationUs: Long = runCatching {
            format.getLong(MediaFormat.KEY_DURATION)
        }.getOrDefault(0L)

        if (durationUs <= 0L) return flatFallback(DEFAULT_FALLBACK_SIZE)

        val durationSecs = durationUs / 1_000_000.0
        val targetBars   = ceil(durationSecs * SAMPLES_PER_SECOND).toInt().coerceAtLeast(1)
        val bucketPeak   = FloatArray(targetBars)

        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone  = false
            var outputDone = false

            while (!outputDone) {
                // ── Feed input ─────────────────────────────────────────
                if (!inputDone) {
                    val inputIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIdx >= 0) {
                        val inputBuf    = codec.getInputBuffer(inputIdx)!!
                        val sampleSize  = mediaExtractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIdx, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputIdx, 0, sampleSize, mediaExtractor.sampleTime, 0
                            )
                            mediaExtractor.advance()
                        }
                    }
                }

                // ── Drain output ───────────────────────────────────────
                val outputIdx = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Format can change after the first buffer; we only
                        // care about PCM output so no action needed here.
                    }
                    outputIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Nothing ready yet — loop back.
                    }
                    outputIdx >= 0 -> {
                        val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                        if (bufferInfo.size > 0) {
                            val outputBuf = codec.getOutputBuffer(outputIdx)
                            if (outputBuf != null) {
                                outputBuf.position(bufferInfo.offset)
                                outputBuf.limit(bufferInfo.offset + bufferInfo.size)

                                // Map this buffer's presentation time to a bucket index.
                                val pts       = bufferInfo.presentationTimeUs.coerceIn(0L, durationUs)
                                val bucketIdx = ((pts.toDouble() / durationUs) * targetBars)
                                    .toInt().coerceIn(0, targetBars - 1)

                                // Compute RMS of PCM 16-bit samples in this buffer.
                                val shorts = outputBuf.asShortBuffer()
                                var sumSq  = 0.0
                                var count  = 0
                                while (shorts.hasRemaining()) {
                                    val s = shorts.get().toDouble()
                                    sumSq += s * s
                                    count++
                                }
                                if (count > 0) {
                                    val rms = sqrt(sumSq / count).toFloat()
                                    if (rms > bucketPeak[bucketIdx]) {
                                        bucketPeak[bucketIdx] = rms
                                    }
                                }
                            }
                        }

                        codec.releaseOutputBuffer(outputIdx, false)
                        if (eos) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { mediaExtractor.release() }
        }

        return normalise(bucketPeak)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Normalises [raw] to [0.05f..1f]. Buckets that received no samples
     * (gaps in the file) are set to a small non-zero floor so they render
     * as a sliver rather than disappearing entirely.
     */
    private fun normalise(raw: FloatArray): FloatArray {
        val max = raw.maxOrNull() ?: 0f
        if (max <= 0f) return flatFallback(raw.size)
        return FloatArray(raw.size) { i ->
            (raw[i] / max).coerceIn(0.05f, 1f)
        }
    }

    /** Flat mid-amplitude array used as a safe fallback. */
    internal fun flatFallback(size: Int): FloatArray = FloatArray(size) { 0.5f }

    private const val TIMEOUT_US           = 10_000L   // 10 ms dequeue timeout
    private const val DEFAULT_FALLBACK_SIZE = 100       // fallback when duration unknown
}