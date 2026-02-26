package com.treecast.app.util

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
 * Storage: [Context.filesDir]/waveforms/{recordingId}.wfm
 * Format:  4-byte little-endian int (count) followed by count × 4-byte floats.
 * Size:    500 bars ≈ 2 KB per recording — negligible.
 *
 * Uses [Context.filesDir] (not cacheDir) so Android never evicts the files.
 * If a recording is deleted, its cache file can be cleaned up via [delete].
 */
class WaveformCache(context: Context) {

    private val cacheDir: File =
        File(context.filesDir, "waveforms").also { it.mkdirs() }

    /** Returns the cached amplitude array for [recordingId], or null if not cached. */
    fun load(recordingId: Long): FloatArray? {
        val file = fileFor(recordingId)
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(FileInputStream(file).buffered()).use { dis ->
                val count = dis.readInt()
                if (count <= 0 || count > 10_000) return null   // sanity guard
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

    /** Returns true if a cache entry already exists for [recordingId]. */
    fun exists(recordingId: Long): Boolean = fileFor(recordingId).exists()

    private fun fileFor(recordingId: Long): File =
        File(cacheDir, "$recordingId.wfm")
}