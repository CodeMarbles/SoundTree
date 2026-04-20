package app.soundtree.util

import android.content.Context
import android.media.MediaMetadataRetriever
import app.soundtree.storage.StorageVolumeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Represents a recording file found on disk that has no matching row in the
 * database — orphaned by a crash, process kill, or interrupted save.
 *
 * [durationMs] = 0 means the file failed the playability probe (MOOV atom
 * likely absent because MediaRecorder.stop() was never called). A positive
 * value means the file is a fully-finalised M4A and safe to import.
 *
 * All marks are lost for orphan recordings; there is no way to recover them
 * after a crash because they were only in [RecordingService.pendingMarks].
 */
data class OrphanRecording(
    val file: File,
    /** Human-readable title derived from the TC_ timestamp in the filename. */
    val suggestedTitle: String,
    /** Duration in milliseconds. Zero = corrupt / unplayable. */
    val durationMs: Long,
) {
    val isPlayable: Boolean get() = durationMs > 0L
}

/**
 * Scans all known recording directories for TC_*.m4a files that have no
 * matching row in the Room database, and classifies each as playable or
 * corrupt using [MediaMetadataRetriever].
 *
 * Intended to be called once at startup from [app.soundtree.ui.SplashActivity]
 * so that [app.soundtree.ui.MainActivity] can show a recovery prompt if
 * any orphans are found.
 */
object OrphanRecordingScanner {

    /**
     * Returns all orphaned recording files across every known storage volume,
     * each classified as playable or corrupt.
     *
     * [knownPaths] is the complete set of file_path values currently in the
     * database, used to skip already-registered recordings.
     *
     * Runs entirely on [Dispatchers.IO]; safe to call from a lifecycleScope
     * or the application's IO-scoped coroutine.
     */
    suspend fun scan(
        context: Context,
        knownPaths: Set<String>,
    ): List<OrphanRecording> = withContext(Dispatchers.IO) {
        recordingDirs(context)
            .flatMap { dir ->
                dir.walkTopDown()
                    .filter { it.isFile && it.name.startsWith("TC_") && it.extension == "m4a" }
                    .toList()
            }
            .filter { file -> file.absolutePath !in knownPaths }
            .map { file ->
                OrphanRecording(
                    file           = file,
                    suggestedTitle = suggestedTitleFrom(file),
                    durationMs     = probePlayable(file),
                )
            }
    }

    // ── Private helpers ───────────────────────────────────────────────

    /**
     * All directories that could contain recording files.
     *
     * Mirrors the selection logic in [app.soundtree.service.RecordingService.createOutputFile]:
     *   - Each AppVolume's rootDir  (<externalFilesDir>/recordings per volume)
     *   - The pre-storage-feature fallback (<primaryExternalFilesDir>/recordings)
     *
     * Canonical paths are used for deduplication so the fallback directory
     * is not scanned twice if it matches the primary volume's rootDir.
     * Only directories that currently exist on disk are returned.
     */
    private fun recordingDirs(context: Context): List<File> {
        val dirs = StorageVolumeHelper.getVolumes(context)
            .map { it.rootDir }
            .toMutableList()

        val legacy = File(context.getExternalFilesDir(null), "recordings")
        if (dirs.none { it.canonicalPath == legacy.canonicalPath }) {
            dirs += legacy
        }

        return dirs.filter { it.exists() }
    }

    /**
     * Derives a human-readable title from a TC_yyyyMMdd_HHmmss filename.
     * Falls back to the bare filename without extension if parsing fails.
     *
     * Example: TC_20240610_143200.m4a → "Recording – Jun 10, 14:32"
     */
    private fun suggestedTitleFrom(file: File): String {
        val stamp = file.nameWithoutExtension.removePrefix("TC_")
        return runCatching {
            val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(stamp)!!
            "Recording – " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(date)
        }.getOrElse { file.nameWithoutExtension }
    }

    /**
     * Probes [file] with [MediaMetadataRetriever] and returns its audio duration
     * in milliseconds, or 0 if the file cannot be opened or yields no duration.
     *
     * A return value of 0 strongly implies that MediaRecorder.stop() was never
     * called: the MPEG-4 MOOV atom (index + metadata) is absent, so the file
     * contains raw MDAT audio data that standard decoders cannot seek into.
     *
     * ── Repair insertion point ────────────────────────────────────────────────
     * Files returning 0 here are candidates for MOOV reconstruction. A future
     * implementation could attempt repair before giving up on playability:
     *
     *   1. Use a native library (e.g. mp4recover, untrunc-ng) or a bundled
     *      ffmpeg binary to reconstruct the MOOV atom from the raw MDAT data.
     *      The reference track for untrunc-ng would be any healthy TC_*.m4a.
     *
     *   2. Write the repaired output to a sibling file (e.g. TC_*_recovered.m4a)
     *      so the original is preserved during the attempt.
     *
     *   3. Re-run probePlayable() on the repaired file. If it now returns a
     *      positive duration, surface it to the user as "recovered (may be
     *      incomplete at the end)" via a distinct label in the recovery dialog.
     *
     *   4. If repair fails, fall through to the existing "corrupt — delete?"
     *      flow below.
     *
     * Until this is implemented, corrupt orphans are shown with a "Incomplete"
     * label and a Delete option in [OrphanRecoveryDialogFragment].
     * ─────────────────────────────────────────────────────────────────────────
     */
    private fun probePlayable(file: File): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: 0L
        } catch (_: Exception) {
            // File could not be opened at all — treat as corrupt.
            0L
        } finally {
            runCatching { mmr.release() }
        }
    }
}