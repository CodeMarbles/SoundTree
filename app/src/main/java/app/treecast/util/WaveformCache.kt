package app.treecast.util

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Persists waveform amplitude arrays to disk so they never need to be
 * recomputed after the first load.
 *
 * Storage location: [Context.filesDir]/waveforms_v2/{recordingId}.wfm
 *
 * The directory name carries [CACHE_VERSION]. When the extraction resolution
 * or format changes, bump [CACHE_VERSION] so the new code uses a fresh
 * directory and all old files become invisible to it. WorkManager will
 * regenerate every recording automatically (idempotency check via [exists]
 * will return false for the new path). Old directories can be cleaned up
 * lazily at a later date.
 *
 * File format:
 *   4-byte int  — sample count (N)
 *   N × 4-byte floats — amplitude values in [0.05f..1f]
 *
 * Storage at [WaveformExtractor.SAMPLES_PER_SECOND] = 8:
 *   5 min  →    2,400 samples  (~9.4 KB)
 *   1 hour →   28,800 samples  (~113 KB)
 *   4 hours→  115,200 samples  (~450 KB)
 *
 * Uses [Context.filesDir] (not cacheDir) so Android never evicts the files.
 * Delete a recording's cache entry via [delete] when the recording is removed.
 */
class WaveformCache(context: Context) {

    companion object {
        /**
         * Increment this when [WaveformExtractor.SAMPLES_PER_SECOND] or the
         * file format changes. Causes a new cache directory to be used and
         * all recordings to be silently re-extracted by [WaveformWorker].
         *
         * History:
         *   v1 — original 500-bar fixed-width format (directory: "waveforms")
         *   v2 — time-based sampling at SAMPLES_PER_SECOND=8 (current)
         */
        const val CACHE_VERSION = 2

        private const val DIR_NAME = "waveforms_v$CACHE_VERSION"

        /**
         * Upper bound on stored sample count. At 8 samples/sec this covers
         * ~34.7 hours of audio — well beyond any realistic single recording.
         * Set high rather than removed entirely as a sanity guard against
         * corrupted files causing out-of-memory allocations.
         */
        private const val MAX_SAMPLE_COUNT = 1_000_000
    }

    private val cacheDir: File =
        File(context.filesDir, DIR_NAME).also { it.mkdirs() }

    /** Returns the cached amplitude array for [recordingId], or null if not cached. */
    fun load(recordingId: Long): FloatArray? {
        val file = fileFor(recordingId)
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(FileInputStream(file).buffered()).use { dis ->
                val count = dis.readInt()
                if (count <= 0 || count > MAX_SAMPLE_COUNT) return null
                FloatArray(count) { dis.readFloat() }
            }
        }.getOrNull()
    }

    /** Persists [amplitudes] to disk for [recordingId]. Silently swallows I/O errors. */
    fun save(recordingId: Long, amplitudes: FloatArray) {
        runCatching {
            DataOutputStream(FileOutputStream(fileFor(recordingId)).buffered()).use { dos ->
                dos.writeInt(amplitudes.size)
                amplitudes.forEach { dos.writeFloat(it) }
            }
        }
    }

    /** Removes the cached file for [recordingId]. Call when a recording is deleted. */
    fun delete(recordingId: Long) {
        runCatching { fileFor(recordingId).delete() }
    }

    /**
     * Deletes ALL cached waveform files in the current version directory.
     * Used by the "Regenerate all waveforms" action in Settings so that every
     * recording is re-decoded from scratch.
     */
    fun deleteAll() {
        runCatching {
            cacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    /** Returns true if a cache entry already exists for [recordingId]. */
    fun exists(recordingId: Long): Boolean = fileFor(recordingId).exists()

    private fun fileFor(recordingId: Long): File =
        File(cacheDir, "$recordingId.wfm")
}