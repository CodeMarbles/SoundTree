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
 * Storage location: [volumeRootDir]/../appdata/waveforms/{recordingId}.wfm
 *
 * Cache files are co-located with their source recordings on the same storage
 * volume, under an appdata/ sibling directory rather than alongside the
 * recordings/ folder itself. This keeps the recordings/ directory clean for
 * users who browse or copy their files manually, while clustering all
 * app-derived data (waveforms, and future additions like clip indexes,
 * playlists, etc.) in one place.
 *
 * Concretely:
 *   Primary volume  →  [phone]/Android/data/app.treecast/files/appdata/waveforms/
 *   SD card         →  [SD card]/Android/data/app.treecast/files/appdata/waveforms/
 *
 * If a recording's volume is unmounted, its cache is inaccessible. Load will
 * return null and extraction is deferred until the volume is available again.
 *
 * File format (little-endian, matches [DataOutputStream] defaults):
 *   4-byte int  — sample count (N)
 *   N × 4-byte floats — amplitude values in [0.05f..1f]
 *
 * Storage at [WaveformExtractor.SAMPLES_PER_SECOND] = 8:
 *   5 min  →    2,400 samples  (~9.4 KB)
 *   1 hour →   28,800 samples  (~113 KB)
 *   4 hours→  115,200 samples  (~450 KB)
 *
 * Delete a recording's cache entry via [delete] when the recording is removed.
 * Use [legacyDir] + [deleteAll] during "Regenerate all waveforms" to clean up
 * the old filesDir/waveforms_v2/ location.
 */
class WaveformCache(volumeRootDir: File) {

    companion object {

        /**
         * Upper bound on stored sample count. At 8 samples/sec this covers
         * ~34.7 hours of audio — well beyond any realistic single recording.
         * Acts as a sanity guard against corrupted files causing OOM allocations.
         */
        private const val MAX_SAMPLE_COUNT = 1_000_000

        /**
         * The legacy cache directory that existed before waveforms were
         * co-located with their recordings (formerly filesDir/waveforms_v2/).
         *
         * Call [File.deleteRecursively] on this from the "Regenerate all
         * waveforms" action to clean up old files left behind by the migration.
         */
        fun legacyDir(context: Context): File =
            File(context.filesDir, "waveforms_v2")
    }

    /**
     * Resolves to [volumeRootDir]/../appdata/waveforms/.
     *
     * [volumeRootDir] is the recordings/ subdirectory on the volume, so its
     * parent is the volume's app-private files/ root — the natural home for
     * all app-derived data on that volume.
     */
    private val cacheDir: File =
        File(volumeRootDir.parentFile!!, "appdata/waveforms").also { it.mkdirs() }

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
     * Deletes ALL cached waveform files in this volume's waveform directory.
     * Used by the "Regenerate all waveforms" action so every recording on
     * this volume is re-decoded from scratch.
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