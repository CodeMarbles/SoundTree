package app.soundtree.export

import app.soundtree.data.entities.MarkEntity
import app.soundtree.data.entities.RecordingEntity
import app.soundtree.data.entities.TopicEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Generates a companion .json metadata file alongside a recording's .m4a.
 *
 * ## Output location
 * The JSON file is always written as a sibling of [RecordingEntity.filePath]
 * with the same stem and a .json extension:
 *   TC_20240115_143022.m4a  →  TC_20240115_143022.json
 *
 * ## Threading
 * [export] performs file I/O — always call it from a background coroutine
 * (Dispatchers.IO) or a WorkManager worker.
 *
 * ## Versioning
 * The output format is versioned via [RecordingExportMetadata.SCHEMA_VERSION].
 * Bump that constant and update [buildMetadata] / the JSON helpers whenever a
 * breaking schema change is made.
 */
object RecordingExporter {

    /**
     * Builds and writes the export JSON for [recording].
     *
     * Marks are sorted ascending by position before being written, regardless
     * of the order in [marks].
     *
     * @param recording  The recording to export.
     * @param marks      All marks belonging to this recording.
     * @param allTopics  Full flat topic list — used to walk the ancestor chain.
     * @return           The [File] that was written.
     * @throws IOException if the JSON file cannot be written.
     */
    fun export(
        recording: RecordingEntity,
        marks:     List<MarkEntity>,
        allTopics: List<TopicEntity>,
    ): File {
        val audioFile = File(recording.filePath)
        val jsonFile  = File(audioFile.parent, "${audioFile.nameWithoutExtension}.json")

        val metadata = buildMetadata(recording, marks, allTopics, audioFile.name)
        jsonFile.writeText(metadata.toJson().toString(2))   // 2-space pretty-print
        return jsonFile
    }

    /**
     * Builds and writes the export JSON for [recording] to an explicit
     * [destDir] instead of alongside the audio file.
     *
     * Used by the restore pre-flight safety export, which writes all
     * current recordings' metadata to a timestamped directory in
     * internal storage before the destructive restore begins.
     *
     * The output file is named `{audioStem}.json` (e.g. `TC_20240115_143022.json`),
     * matching the convention of the sibling-file exporter so the output is
     * readable by the same tools.
     *
     * @param recording  The recording to export.
     * @param marks      All marks belonging to this recording.
     * @param allTopics  Full flat topic list — used to walk the ancestor chain.
     * @param destDir    Directory to write the JSON into (must already exist).
     * @return           The [File] that was written.
     * @throws IOException if the JSON file cannot be written.
     */
    fun exportToDir(
        recording: RecordingEntity,
        marks:     List<MarkEntity>,
        allTopics: List<TopicEntity>,
        destDir:   File,
    ): File {
        val stem     = File(recording.filePath).nameWithoutExtension
        val jsonFile = File(destDir, "$stem.json")

        val metadata = buildMetadata(recording, marks, allTopics, File(recording.filePath).name)
        jsonFile.writeText(metadata.toJson().toString(2))
        return jsonFile
    }

    // ── Metadata builders ─────────────────────────────────────────────────────

    private fun buildMetadata(
        recording:     RecordingEntity,
        marks:         List<MarkEntity>,
        allTopics:     List<TopicEntity>,
        audioFilename: String,
    ): RecordingExportMetadata {
        val topicMeta = recording.topicId
            ?.let { id -> allTopics.find { it.id == id } }
            ?.let { leaf -> buildTopicMeta(leaf, allTopics) }

        return RecordingExportMetadata(
            exportedAt    = Instant.now().toString(),
            audioFilename = audioFilename,
            recording     = RecordingMeta(
                title       = recording.title,
                description = recording.description,
                createdAt   = Instant.ofEpochMilli(recording.createdAt).toString(),
                durationMs  = recording.durationMs,
                tags        = recording.tags
                    .split(",")
                    .map    { it.trim() }
                    .filter { it.isNotEmpty() },
                isFavourite = recording.isFavourite,
            ),
            topic = topicMeta,
            marks = marks
                .sortedBy { it.positionMs }
                .map { MarkMeta(positionMs = it.positionMs, label = formatMark(it.positionMs)) },
        )
    }

    /**
     * Walks the topic tree upward from [leaf] to build the full ancestor path.
     *
     * Includes a cycle guard (shouldn't occur with correct DB constraints, but
     * belt-and-suspenders — a cycle would otherwise loop forever here).
     *
     * The returned [TopicMeta.path] runs root → leaf, leaf-inclusive,
     * e.g. ["Work", "Meetings", "Q1"].
     */
    private fun buildTopicMeta(leaf: TopicEntity, allTopics: List<TopicEntity>): TopicMeta {
        val path = mutableListOf<String>()
        val seen = mutableSetOf<Long>()
        var node: TopicEntity? = leaf

        while (node != null && node.id !in seen) {
            seen  += node.id
            path.add(0, node.name)   // prepend → root ends up at index 0
            node = node.parentId?.let { pid -> allTopics.find { it.id == pid } }
        }

        return TopicMeta(
            name        = leaf.name,
            emoji       = leaf.icon,
            color       = leaf.color,
            description = leaf.description,
            path        = path,
        )
    }

    /**
     * Formats a mark position as a human-readable timestamp string.
     *
     * - Under one hour : MM:SS.t  (e.g. "01:23.4")
     * - One hour or more: H:MM:SS.t  (e.g. "1:23:45.6")
     *
     * The tenths digit gives enough sub-second precision for navigation
     * without overwhelming readers of the raw JSON.
     */
    private fun formatMark(positionMs: Long): String {
        val tenths       = (positionMs / 100) % 10
        val totalSeconds = positionMs / 1000
        val seconds      = totalSeconds % 60
        val totalMinutes = totalSeconds / 60
        val minutes      = totalMinutes % 60
        val hours        = totalMinutes / 60

        return if (hours > 0) {
            "%d:%02d:%02d.%d".format(hours, minutes, seconds, tenths)
        } else {
            "%02d:%02d.%d".format(minutes, seconds, tenths)
        }
    }

    // ── JSON serialisation ────────────────────────────────────────────────────
    //
    // Using org.json (always available on Android) rather than a third-party
    // serialisation library, keeping the export package dependency-free.

    private fun RecordingExportMetadata.toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("generator",     generator)
        put("exportedAt",    exportedAt)
        put("audioFilename", audioFilename)
        put("recording",     recording.toJson())
        put("topic",         topic?.toJson() ?: JSONObject.NULL)
        put("marks",         JSONArray().also { arr -> marks.forEach { arr.put(it.toJson()) } })
    }

    private fun RecordingMeta.toJson(): JSONObject = JSONObject().apply {
        put("title",       title)
        put("description", description)
        put("createdAt",   createdAt)
        put("durationMs",  durationMs)
        put("tags",        JSONArray(tags))
        put("isFavourite", isFavourite)
    }

    private fun TopicMeta.toJson(): JSONObject = JSONObject().apply {
        put("name",        name)
        put("emoji",       emoji)
        put("color",       color)
        put("description", description)
        put("path",        JSONArray(path))
    }

    private fun MarkMeta.toJson(): JSONObject = JSONObject().apply {
        put("positionMs", positionMs)
        put("label",      label)
    }
}