package app.soundtree.util

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

    /**
     * Returns true if [name] is a SoundTree recording filename —
     * i.e. it starts with a recognised prefix and has an `.m4a` extension.
     */
    fun isRecordingFile(name: String): Boolean =
        name.endsWith(".m4a") && PREFIXES.any { name.startsWith(it) }

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
}