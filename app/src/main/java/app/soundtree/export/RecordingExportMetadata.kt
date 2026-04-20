package app.soundtree.export

/**
 * Root model for a SoundTree recording export JSON file (schema version 1).
 *
 * One of these is serialised as a .json sibling alongside each exported .m4a,
 * e.g. TC_20240115_143022.json lives next to TC_20240115_143022.m4a.
 *
 * ## Stability contract
 * [schemaVersion] must be bumped whenever a field is removed or its semantics
 * change in a breaking way. Additive changes (new optional fields) do not
 * require a bump — importers should ignore unknown keys.
 *
 * @property schemaVersion  Incremented on breaking schema changes. Start at 1.
 * @property generator      Constant identifying the producing app ("soundtree").
 * @property exportedAt     ISO-8601 instant when this file was written.
 * @property audioFilename  Bare filename of the companion audio file (no path).
 * @property recording      Core recording metadata.
 * @property topic          Topic context, or null if the recording is Unsorted.
 * @property marks          Ordered list of mark positions (ascending).
 */
data class RecordingExportMetadata(
    val schemaVersion: Int    = SCHEMA_VERSION,
    val generator:     String = GENERATOR,
    val exportedAt:    String,
    val audioFilename: String,
    val recording:     RecordingMeta,
    val topic:         TopicMeta?,
    val marks:         List<MarkMeta>,
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val GENERATOR      = "soundtree"
    }
}

/**
 * Core metadata fields drawn from [app.soundtree.data.entities.RecordingEntity].
 *
 * Intentionally excludes device-specific or internal fields:
 * filePath, storageVolumeUuid, waveformStatus, playbackPositionMs, sortOrder.
 *
 * @property title       User-facing recording title.
 * @property description User-supplied description (may be empty).
 * @property createdAt   ISO-8601 instant when the recording was saved.
 * @property durationMs  Total audio duration in milliseconds.
 * @property tags        Tags as a proper list (split from the DB's comma-joined string).
 * @property isFavourite Whether the user has starred this recording.
 */
data class RecordingMeta(
    val title:       String,
    val description: String,
    val createdAt:   String,
    val durationMs:  Long,
    val tags:        List<String>,
    val isFavourite: Boolean,
)

/**
 * Topic context for the recording.
 *
 * [path] is the full ancestor chain from root to this topic (leaf-inclusive),
 * e.g. ["Work", "Meetings", "Q1"]. This preserves hierarchy even if the
 * import target has a different tree structure.
 *
 * @property name        Leaf topic name.
 * @property emoji       Topic icon emoji (e.g. "📅").
 * @property color       Accent color as a hex string (e.g. "#FF6B6B").
 * @property description Topic description (may be empty).
 * @property path        Full ancestor chain, root → leaf, leaf-inclusive.
 */
data class TopicMeta(
    val name:        String,
    val emoji:       String,
    val color:       String,
    val description: String,
    val path:        List<String>,
)

/**
 * A single mark position, expressed in two complementary forms.
 *
 * Both forms encode the same instant — [positionMs] is the authoritative
 * value for programmatic use; [label] is a human-readable convenience for
 * anyone reading the raw JSON.
 *
 * @property positionMs  Position in the recording timeline, in milliseconds.
 * @property label       Formatted timestamp string, e.g. "01:23.4" or "1:23:45.6".
 *                       Format: MM:SS.t under one hour; H:MM:SS.t at or over one hour.
 *                       The tenths digit (.t) gives sub-second precision without clutter.
 */
data class MarkMeta(
    val positionMs: Long,
    val label:      String,
)