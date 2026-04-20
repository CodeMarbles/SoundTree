package app.soundtree.util

import app.soundtree.data.dao.RecordingDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One-time utility that migrates recordings from a flat `recordings/` layout
 * to the canonical `recordings/YYYY/MM/` structure used by [RecordingService].
 *
 * Intended to fix devices that have recordings stored in the old flat layout
 * before the subdirectory structure was introduced. Gated behind Future Mode
 * in Settings → Tools so it can be quietly removed once no longer needed.
 *
 * ── Safe move sequence (per file) ────────────────────────────────────────────
 *   1. Derive target YYYY/MM directory from the TC_ filename timestamp.
 *   2. Copy file to the new location.
 *   3. Verify copied file size matches source.
 *   4. Update the DB file_path (point of no return).
 *   5. Delete the original.
 *
 * If copy or verification fails the partial copy is cleaned up and the
 * original is left untouched. If the DB update fails after a successful copy
 * the copy is also removed and the original preserved — the recording stays
 * reachable. We never delete the source until the DB has been updated.
 */
object RecordingStructureMigrator {

    data class Result(
        val moved: Int,
        val failed: Int,
        val alreadyCorrect: Int,
    ) {
        val totalExamined: Int get() = moved + failed + alreadyCorrect
    }

    /**
     * Runs the migration on the IO dispatcher.
     *
     * [onProgress] is called after each successful move with the running
     * moved count and the filename that was just moved. Use it to push
     * updates to a StateFlow for the UI.
     */
    suspend fun migrate(
        recordingDao: RecordingDao,
        onProgress: suspend (movedSoFar: Int, filename: String) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        val recordings = recordingDao.getAllOnce()
        var moved         = 0
        var failed        = 0
        var alreadyCorrect = 0

        for (recording in recordings) {
            val file = File(recording.filePath)

            if (!file.exists()) {
                // Missing file — not our concern here; orphan scanner handles it.
                alreadyCorrect++
                continue
            }

            if (isCorrectlyPlaced(file)) {
                alreadyCorrect++
                continue
            }

            val targetDir = targetDirFor(file)
            if (targetDir == null) {
                // Filename doesn't match TC_yyyyMMdd_HHmmss — leave alone.
                failed++
                continue
            }

            targetDir.mkdirs()
            val destFile = File(targetDir, file.name)

            // Guard against a file already existing at the target
            // (shouldn't happen for TC_ names, but be safe).
            if (destFile.exists() && destFile.length() == file.length()) {
                // Destination already has an identical copy — just update DB and
                // remove the stale flat copy without re-copying.
                try {
                    recordingDao.updateFilePath(recording.id, destFile.absolutePath)
                    file.delete()
                    moved++
                    onProgress(moved, file.name)
                } catch (_: Exception) {
                    failed++
                }
                continue
            }

            try {
                file.copyTo(destFile, overwrite = true)

                if (destFile.length() != file.length()) {
                    destFile.delete()
                    failed++
                    continue
                }

                // Update DB before deleting source — never lose the reference.
                try {
                    recordingDao.updateFilePath(recording.id, destFile.absolutePath)
                } catch (_: Exception) {
                    // DB update failed — roll back the copy.
                    destFile.delete()
                    failed++
                    continue
                }

                file.delete()
                moved++
                onProgress(moved, file.name)

            } catch (_: Exception) {
                destFile.delete()
                failed++
            }
        }

        Result(moved = moved, failed = failed, alreadyCorrect = alreadyCorrect)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Returns true if [file] already lives in a `recordings/YYYY/MM/` tree,
     * i.e. its parent directory name is a 2-digit month and its grandparent
     * name is a 4-digit year.
     */
    private fun isCorrectlyPlaced(file: File): Boolean {
        val month = file.parentFile?.name ?: return false
        val year  = file.parentFile?.parentFile?.name ?: return false
        return month.matches(Regex("\\d{2}")) && year.matches(Regex("\\d{4}"))
    }

    /**
     * Derives the correct `recordings/YYYY/MM/` target directory from a
     * `TC_yyyyMMdd_HHmmss.m4a` filename.
     *
     * Returns null if the filename does not match the expected pattern, which
     * means we cannot determine the correct placement without extra metadata.
     *
     * Assumes the file is currently flat inside its `recordings/` root, so
     * [file.parentFile] is the recordings root and the target subdir lives
     * directly beneath it.
     */
    private fun targetDirFor(file: File): File? {
        val stem  = file.nameWithoutExtension.removePrefix("TC_")
        if (stem.length < 8) return null
        val year  = stem.substring(0, 4)
        val month = stem.substring(4, 6)
        if (!year.matches(Regex("\\d{4}")) || !month.matches(Regex("\\d{2}"))) return null
        val recordingsRoot = file.parentFile ?: return null
        return File(recordingsRoot, "$year/$month")
    }
}