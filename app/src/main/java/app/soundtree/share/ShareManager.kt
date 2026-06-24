package app.soundtree.share

import android.content.Context
import android.content.Intent
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import app.soundtree.data.entities.MarkEntity
import app.soundtree.data.entities.RecordingEntity
import app.soundtree.data.entities.TopicEntity
import app.soundtree.export.RecordingExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Prepares and fires Android share intents for recordings.
 *
 * ## No-copy audio sharing
 * Recording files can be hundreds of megabytes. Rather than copying audio to
 * a cache directory and renaming it, [ShareManager] serves the original on-disk
 * file directly via [SoundTreeFileProvider] and registers the user's chosen
 * display name in [SoundTreeFileProvider.displayNameOverrides]. The receiving
 * app sees the human-readable name; the file is never duplicated.
 *
 * ## JSON metadata
 * JSON exports are always written to [shareDir] because they do not exist on
 * disk beforehand. They are small (typically a few KB) so write time is
 * negligible regardless of recording size.
 *
 * ## Cache lifecycle
 * [pruneShareCache] is called at the start of every [prepareIntent]:
 *   - JSON files older than [CACHE_TTL_MS] are deleted from [shareDir].
 *   - Stale display name overrides are pruned from [SoundTreeFileProvider].
 * Both use the same TTL constant for consistency.
 *
 * ## Threading
 * [prepareIntent] performs file I/O and must be called from a coroutine.
 * It returns a ready-to-fire [Intent]; the caller dispatches it with
 * [Context.startActivity].
 *
 * ## FileProvider authority
 * `app.soundtree.fileprovider` — declared in AndroidManifest.xml, backed by
 * `res/xml/file_provider_paths.xml`. Covers:
 *   - `external-files-path` → all mounted volumes where .m4a files live
 *   - `cache-path "share_cache"` → cacheDir/share/ for ephemeral JSON exports
 *
 * ## Future extension — clips
 * Add new [ShareContent] subtypes and corresponding `when` branches in
 * [buildIntent]. No other changes needed here.
 */
object ShareManager {

    const val AUTHORITY = "app.soundtree.fileprovider"

    /** Subdirectory of [Context.cacheDir] used for ephemeral JSON exports. */
    private const val SHARE_CACHE_SUBDIR = "share"

    /** How long ephemeral JSON files and display name overrides are kept. */
    private const val CACHE_TTL_MS = 60 * 60 * 1000L  // 1 hour

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Prepares a share [Intent] for the given recording and parameters.
     *
     * All file I/O runs on [Dispatchers.IO]. Returns null if the audio file
     * does not exist on disk — caller should show an error and dismiss.
     *
     * @param context     Application context; used for FileProvider and cache.
     * @param recording   The recording being shared.
     * @param marks       Marks for this recording (written into JSON if included).
     * @param allTopics   Full topic list (used to build topic path in JSON).
     * @param content     What to include: audio, audio+JSON, or JSON only.
     * @param outputStem  Desired filename stem (no extension), fully resolved by
     *                    the dialog (sanitized, date-injected, etc.).
     * @return            A chooser-wrapped [Intent], or null if audio is missing.
     */
    suspend fun prepareIntent(
        context:    Context,
        recording:  RecordingEntity,
        marks:      List<MarkEntity>,
        allTopics:  List<TopicEntity>,
        content:    ShareContent,
        outputStem: String,
    ): Intent? = withContext(Dispatchers.IO) {

        pruneShareCache(context)

        val audioFile = File(recording.filePath)
        if (content != ShareContent.MetadataOnly && !audioFile.exists()) {
            return@withContext null
        }

        buildIntent(context, recording, marks, allTopics, content, outputStem, audioFile)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Intent builders
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildIntent(
        context:    Context,
        recording:  RecordingEntity,
        marks:      List<MarkEntity>,
        allTopics:  List<TopicEntity>,
        content:    ShareContent,
        outputStem: String,
        audioFile:  File,
    ): Intent = when (content) {

        ShareContent.AudioOnly -> {
            val audioUri = uriForAudio(context, audioFile, "$outputStem.m4a")
            ShareCompat.IntentBuilder(context)
                .setType("audio/mp4")
                .addStream(audioUri)
                .createChooserIntent()
        }

        ShareContent.AudioWithMetadata -> {
            val audioUri = uriForAudio(context, audioFile, "$outputStem.m4a")
            val jsonUri  = uriForJson(context, recording, marks, allTopics, outputStem)

            // ACTION_SEND_MULTIPLE requires */* when MIME types differ.
            ShareCompat.IntentBuilder(context)
                .setType("*/*")
                .addStream(audioUri)
                .addStream(jsonUri)
                .createChooserIntent()
        }

        ShareContent.MetadataOnly -> {
            val jsonUri = uriForJson(context, recording, marks, allTopics, outputStem)
            ShareCompat.IntentBuilder(context)
                .setType("application/json")
                .addStream(jsonUri)
                .createChooserIntent()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // URI helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a [FileProvider] URI for [audioFile], registering [displayName]
     * as the name the receiving app will see via [SoundTreeFileProvider.query].
     *
     * The audio file is served directly from its on-disk location — no copy.
     */
    private fun uriForAudio(
        context:     Context,
        audioFile:   File,
        displayName: String,
    ) = FileProvider.getUriForFile(context, AUTHORITY, audioFile).also { uri ->
        SoundTreeFileProvider.registerDisplayName(uri, displayName)
    }

    /**
     * Exports recording metadata to [shareDir]/[outputStem].json and returns
     * a [FileProvider] URI for the result.
     *
     * The JSON file is written fresh on every share — it reflects the current
     * state of the recording's metadata and marks at share time.
     */
    private fun uriForJson(
        context:    Context,
        recording:  RecordingEntity,
        marks:      List<MarkEntity>,
        allTopics:  List<TopicEntity>,
        outputStem: String,
    ): android.net.Uri {
        val dir          = shareDir(context)
        val exportedFile = RecordingExporter.exportToDir(recording, marks, allTopics, dir)

        // exportToDir names the file after the recording's on-disk stem.
        // Rename to the user's chosen outputStem so JSON and audio share a name.
        val destFile = File(dir, "$outputStem.json")
        if (exportedFile.canonicalPath != destFile.canonicalPath) {
            exportedFile.renameTo(destFile)
        }

        return FileProvider.getUriForFile(context, AUTHORITY, destFile)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cache management
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns (and creates if needed) the ephemeral JSON share cache directory. */
    private fun shareDir(context: Context): File =
        File(context.cacheDir, SHARE_CACHE_SUBDIR).also { it.mkdirs() }

    /**
     * Removes stale JSON files from [shareDir] and stale display name
     * overrides from [SoundTreeFileProvider].
     *
     * Called at the start of every [prepareIntent]. Failures are silently
     * swallowed — stale files are harmless and will be caught next time.
     */
    private fun pruneShareCache(context: Context) {
        val cutoff = System.currentTimeMillis() - CACHE_TTL_MS
        runCatching {
            shareDir(context).listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) file.delete()
            }
        }
        SoundTreeFileProvider.pruneStaleOverrides()
    }
}