package app.soundtree.util

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists waveform amplitude arrays to disk so they never need to be
 * recomputed after the first load.
 *
 * Storage location: [volumeRootDir]/../appdata/waveforms/YYYY/MM/{recordingId}.wfm
 *
 * Cache files are co-located with their source recordings on the same storage
 * volume, under an appdata/ sibling directory rather than alongside the
 * recordings/ folder itself. This keeps the recordings/ directory clean for
 * users who browse or copy their files manually, while clustering all
 * app-derived data (waveforms, and future additions like clip indexes,
 * playlists, etc.) in one place.
 *
 * Within appdata/waveforms/, files are organised into YYYY/MM/ subdirectories
 * derived from the recording's creation timestamp — mirroring the recordings/
 * hierarchy. This keeps individual directory entry counts bounded for heavy
 * users (voice notes, frequent recordings) who might otherwise accumulate
 * 10 000+ flat files over several years of use.
 *
 * Concretely:
 *   Primary volume  →  [phone]/Android/data/app.soundtree/files/appdata/waveforms/YYYY/MM/
 *   SD card         →  [SD card]/Android/data/app.soundtree/files/appdata/waveforms/YYYY/MM/
 *
 * If a recording's volume is unmounted, its cache is inaccessible. Load will
 * return null and extraction is deferred until the volume is available again.
 *
 * ## Lazy migration from the previous flat layout
 * Older builds stored cache files directly in appdata/waveforms/{recordingId}.wfm.
 * [load] checks the flat path as a fallback when the YYYY/MM path is absent,
 * and promotes the file to its correct subdirectory on a cache hit. No migration
 * pass or user action is required — files are silently relocated on first access.
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
         * Formats epoch-ms timestamps into the YYYY/MM relative directory path
         * used inside [cacheDir]. Not thread-safe; used only on IO threads via
         * [fileFor] which is always called from a single coroutine context.
         */
        private val YEAR_MONTH_FORMAT = SimpleDateFormat("yyyy/MM", Locale.US)

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
     * Root of the waveform cache for this volume. Resolves to
     * [volumeRootDir]/../appdata/waveforms/.
     *
     * [volumeRootDir] is the recordings/ subdirectory on the volume, so its
     * parent is the volume's app-private files/ root — the natural home for
     * all app-derived data on that volume. YYYY/MM subdirectories are created
     * lazily inside here as recordings are first cached.
     */
    private val cacheDir: File =
        File(volumeRootDir.parentFile!!, "appdata/waveforms").also { it.mkdirs() }

    /**
     * Returns the cached amplitude array for [recordingId], or null if not cached.
     *
     * Checks the canonical YYYY/MM path first. If absent, falls back to the
     * legacy flat path (appdata/waveforms/{recordingId}.wfm) and promotes the
     * file to its correct YYYY/MM location on a hit, so subsequent loads use
     * the fast path. No external migration step is required.
     */
    fun load(recordingId: Long, recordedAt: Long): FloatArray? {
        val canonical = fileFor(recordingId, recordedAt)

        val source = when {
            canonical.exists() -> canonical
            else -> {
                // Lazy migration: check the legacy flat location.
                val flat = File(cacheDir, "$recordingId.wfm")
                if (!flat.exists()) return null

                // Promote the flat file to its canonical YYYY/MM location.
                runCatching {
                    canonical.parentFile?.mkdirs()
                    flat.copyTo(canonical, overwrite = true)
                    if (canonical.length() == flat.length()) {
                        flat.delete()
                        Log.i("WaveformCache", "Lazily migrated $recordingId.wfm → ${canonical.absolutePath}")
                        canonical
                    } else {
                        // Partial copy — discard and fall back to the flat file.
                        // It will be promoted successfully on the next attempt.
                        canonical.delete()
                        flat
                    }
                }.getOrElse { flat }
            }
        }

        return runCatching {
            DataInputStream(FileInputStream(source).buffered()).use { dis ->
                val count = dis.readInt()
                if (count <= 0 || count > MAX_SAMPLE_COUNT) return null
                FloatArray(count) { dis.readFloat() }
            }
        }.getOrNull()
    }

    /** Persists [amplitudes] to disk for [recordingId]. Silently swallows I/O errors. */
    fun save(recordingId: Long, amplitudes: FloatArray, recordedAt: Long) {
        runCatching {
            val file = fileFor(recordingId, recordedAt)
            file.parentFile?.mkdirs()
            DataOutputStream(FileOutputStream(file).buffered()).use { dos ->
                dos.writeInt(amplitudes.size)
                amplitudes.forEach { dos.writeFloat(it) }
            }
        }
    }

    /**
     * Removes the cached file for [recordingId]. Call when a recording is deleted.
     *
     * Deletes both the canonical YYYY/MM path and any surviving legacy flat copy
     * so that un-promoted files are not left behind as orphans.
     */
    fun delete(recordingId: Long, recordedAt: Long) {
        runCatching { fileFor(recordingId, recordedAt).delete() }
        // Also remove any un-promoted flat copy that may still exist.
        runCatching { File(cacheDir, "$recordingId.wfm").delete() }
    }

    /**
     * Deletes ALL cached waveform files in this volume's waveform directory,
     * including all YYYY/MM subdirectories and any surviving flat files.
     * Used by the "Regenerate all waveforms" action so every recording on
     * this volume is re-decoded from scratch.
     */
    fun deleteAll() {
        runCatching {
            cacheDir.listFiles()?.forEach { child ->
                if (child.isDirectory) child.deleteRecursively() else child.delete()
            }
        }
    }

    /**
     * Returns true if a cache entry already exists at the canonical YYYY/MM
     * location for [recordingId].
     *
     * The legacy flat path is intentionally not checked here. [WaveformWorker]
     * uses this for its idempotency guard: returning false for a flat file
     * causes the worker to re-decode and save to the canonical path, cleanly
     * migrating the file as a side effect of normal processing.
     */
    fun exists(recordingId: Long, recordedAt: Long): Boolean =
        fileFor(recordingId, recordedAt).exists()

    /**
     * Resolves the canonical cache path for [recordingId]:
     *   appdata/waveforms/YYYY/MM/{recordingId}.wfm
     *
     * The YYYY/MM subdir is derived from [recordedAt] (epoch ms). The directory
     * itself is created lazily by [save]; [load] and [exists] do not create it.
     */
    private fun fileFor(recordingId: Long, recordedAt: Long): File {
        val relDir = YEAR_MONTH_FORMAT.format(Date(recordedAt))  // e.g. "2024/03"
        return File(cacheDir, "$relDir/$recordingId.wfm")
    }
}