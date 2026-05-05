package app.soundtree.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Shared helpers for SoundTree recording filenames.
 *
 * ## Filename conventions
 * New recordings use the `ST_` prefix (SoundTree).
 * Recordings created before the rename use `TC_` (TreeCast, legacy).
 * Both prefixes must be accepted everywhere files are read, scanned,
 * or copied — only [RecordingService.createOutputFile] writes new files,
 * and it now uses `ST_`.
 *
 * Canonical format: `{PREFIX}yyyyMMdd_HHmmss.m4a`
 *   e.g.  ST_20250420_143022.m4a
 *         TC_20240115_091500.m4a   ← legacy, still valid
 */
object RecordingFileHelper {

    /** Both recognised filename prefixes, current first. */
    val PREFIXES = listOf("ST_", "TC_")

    /** The prefix written by [app.soundtree.service.RecordingService] for new recordings. */
    const val CURRENT_PREFIX = "ST_"

    // ── Filename predicates ───────────────────────────────────────────────────

    /**
     * Returns true if [name] starts with a recognised recording prefix
     * (`ST_` or `TC_`), regardless of extension.
     *
     * Use this when the caller needs to match multiple file types that
     * share a recording stem — for example, the backup destination index
     * which must accept both `.m4a` audio files and `.json` sidecars under
     * a single outer guard, with the inner [when] branch discriminating
     * by extension.
     */
    fun hasRecordingPrefix(name: String): Boolean =
        PREFIXES.any { name.startsWith(it) }

    /**
     * Returns true if [name] is a SoundTree recording audio file —
     * i.e. it starts with a recognised prefix and has an `.m4a` extension.
     *
     * Use this when only the `.m4a` itself is relevant (e.g. the copy loop
     * in BackupWorker). For cases where multiple companion file types share
     * the same stem (e.g. `.json` sidecars alongside `.m4a` files), use
     * [hasRecordingPrefix] and let the caller discriminate by extension.
     */
    fun isRecordingFile(name: String): Boolean =
        name.endsWith(".m4a") && hasRecordingPrefix(name)

    // ── Stem parsing ──────────────────────────────────────────────────────────

    /**
     * Strips the recognised prefix from a recording filename stem so the
     * remaining `yyyyMMdd_HHmmss` timestamp can be parsed uniformly.
     *
     * Works on both the bare stem (`ST_20250420_143022`) and the full
     * filename with extension (`TC_20240115_091500.m4a`).
     *
     * Returns the input unchanged if no recognised prefix is present.
     */
    fun stemWithoutPrefix(nameOrStem: String): String {
        val stem = nameOrStem.removeSuffix(".m4a")
        return PREFIXES.fold(stem) { acc, prefix -> acc.removePrefix(prefix) }
    }

    /**
     * Extracts the `YYYY` and `MM` components from a recording filename stem
     * (with or without prefix), returning them as a [Pair] or `null` if the
     * stem does not match the expected `yyyyMMdd_HHmmss` format.
     *
     * Centralises the YYYY/MM routing logic used by [BackupWorker]'s
     * recording-copy and metadata-export steps, preventing hardcoded prefix
     * assumptions from creeping back in at each call site.
     *
     * Example:
     * ```
     * yearMonthFromStem("ST_20250420_143022") == ("2025", "04")
     * yearMonthFromStem("TC_20240115_091500") == ("2024", "01")
     * yearMonthFromStem("garbage")            == null
     * ```
     */
    fun yearMonthFromStem(stem: String): Pair<String, String>? {
        val raw  = stemWithoutPrefix(stem)
        val yyyy = raw.take(4)
        val mm   = raw.drop(4).take(2)
        return if (yyyy.matches(Regex("\\d{4}")) && mm.matches(Regex("\\d{2}"))) yyyy to mm else null
    }

    // ── File-derived metadata ─────────────────────────────────────────────────

    /**
     * Parses the recording's creation timestamp (epoch milliseconds) from its
     * `ST_yyyyMMdd_HHmmss` or `TC_yyyyMMdd_HHmmss` filename.
     *
     * Falls back to [File.lastModified] if parsing fails (e.g. the file was
     * renamed), and to [System.currentTimeMillis] if `lastModified` returns 0.
     * This means callers always get a plausible `createdAt` value — never a
     * sentinel zero — regardless of how the file arrived on disk.
     */
    fun createdAtFromFile(file: File): Long =
        runCatching {
            val stamp = stemWithoutPrefix(file.nameWithoutExtension)
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(stamp)!!.time
        }.getOrElse { file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis() }

    /**
     * Derives a human-readable suggested title from an `ST_` or `TC_`
     * recording filename.
     *
     * Produces a string of the form `"Recording – MMM d, HH:mm"` (e.g.
     * `"Recording – Apr 20, 14:30"`). Falls back to the bare filename stem
     * (without extension) if the timestamp cannot be parsed.
     *
     * This is the single source of truth for orphan title generation —
     * [OrphanRecordingScanner] and [OrphanRecoveryDialogFragment] both
     * delegate here rather than maintaining their own copies.
     */
    fun suggestedTitle(file: File): String =
        runCatching {
            val stamp = stemWithoutPrefix(file.nameWithoutExtension)
            val date  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(stamp)!!
            "Recording – " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(date)
        }.getOrElse { file.nameWithoutExtension }
}