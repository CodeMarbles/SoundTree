package app.soundtree.share

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * A [FileProvider] subclass that allows [ShareManager] to override the
 * display name a receiving app sees for a shared file — without copying
 * the file to a temporary location.
 *
 * ## Problem
 * The standard [FileProvider] derives [OpenableColumns.DISPLAY_NAME] from
 * the actual filename on disk. SoundTree recording files use an internal
 * stem format (`ST_20260623_111213.m4a`) that is meaningless to recipients.
 * The share dialog lets users choose a human-readable name, but renaming
 * the source file is destructive, and copying large audio files to cache
 * before sharing is impractical for recordings that may exceed 500 MB.
 *
 * ## Solution
 * Before building a share intent, [ShareManager] registers the desired
 * display name in [displayNameOverrides] keyed by the file's [Uri].
 * When the receiving app calls [query] to resolve [OpenableColumns.DISPLAY_NAME],
 * this provider intercepts the call and returns the registered name instead
 * of the real filename.
 *
 * All other [FileProvider] behaviour (URI generation, permission grants,
 * [openFile]) is inherited unchanged.
 *
 * ## Thread safety
 * [displayNameOverrides] is a [ConcurrentHashMap] — safe for concurrent
 * reads from the system's binder thread pool.
 *
 * ## Lifetime
 * Entries are registered just before a share intent is fired and pruned
 * by [ShareManager.pruneShareCache] after [OVERRIDE_TTL_MS]. The window
 * between registration and the receiving app's [query] call is always
 * sub-second, so the TTL only exists to prevent unbounded growth in
 * pathological cases (e.g. user repeatedly opens the share dialog without
 * completing a share).
 *
 * ## Manifest
 * Declared in AndroidManifest.xml with authority `app.soundtree.fileprovider`
 * and backed by `res/xml/file_provider_paths.xml`.
 *
 * ## Future extension — clips
 * No changes needed here when clip sharing is added. [ShareManager] registers
 * clip file URIs the same way as recording URIs.
 */
class SoundTreeFileProvider : FileProvider() {

    companion object {

        /** How long a display name override is considered valid. */
        const val OVERRIDE_TTL_MS = 60 * 60 * 1000L  // 1 hour

        /**
         * Registered display name overrides.
         *
         * Key:   The `content://` URI for the file (as returned by
         *        [FileProvider.getUriForFile]).
         * Value: The desired display name (e.g. "Interview with Dad - 2026-06-23.m4a")
         *        and the wall-clock time the entry was registered.
         *
         * Populated by [ShareManager.registerDisplayName].
         * Pruned by [ShareManager.pruneShareCache].
         * Read by [query].
         */
        internal val displayNameOverrides = ConcurrentHashMap<Uri, DisplayNameEntry>()

        /**
         * Registers a display name override for [uri].
         *
         * Call this before building the share intent so the entry is in place
         * when the receiving app resolves [OpenableColumns.DISPLAY_NAME].
         * Overwrites any existing entry for the same URI.
         */
        fun registerDisplayName(uri: Uri, displayName: String) {
            displayNameOverrides[uri] = DisplayNameEntry(
                displayName   = displayName,
                registeredAt  = System.currentTimeMillis(),
            )
        }

        /**
         * Removes all display name overrides older than [OVERRIDE_TTL_MS].
         *
         * Called by [ShareManager.pruneShareCache] at the start of each
         * share operation.
         */
        fun pruneStaleOverrides() {
            val cutoff = System.currentTimeMillis() - OVERRIDE_TTL_MS
            val stale  = displayNameOverrides.entries
                .filter { it.value.registeredAt < cutoff }
                .map    { it.key }
            stale.forEach { displayNameOverrides.remove(it) }
        }
    }

    /**
     * Intercepts [OpenableColumns.DISPLAY_NAME] queries to return the
     * registered override name when one exists for the requested URI.
     *
     * Falls through to the standard [FileProvider.query] implementation
     * for all other queries and for URIs with no registered override.
     *
     * The receiving app typically queries only [OpenableColumns.DISPLAY_NAME]
     * and [OpenableColumns.SIZE]. We only override the name column; size is
     * always served from the real file via the super implementation.
     */
    override fun query(
        uri:            Uri,
        projection:     Array<out String>?,
        selection:      String?,
        selectionArgs:  Array<out String>?,
        sortOrder:      String?,
    ): Cursor {
        val override = displayNameOverrides[uri]
            ?: return super.query(uri, projection, selection, selectionArgs, sortOrder)

        // Determine which columns are being requested.
        // A null projection means "all columns" — we treat that as both columns.
        val requestedColumns = projection ?: arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
        )

        val wantsName = OpenableColumns.DISPLAY_NAME in requestedColumns
        val wantsSize = OpenableColumns.SIZE in requestedColumns

        if (!wantsName) {
            // Caller only wants SIZE (or something else entirely).
            // Delegate to super — we have nothing to override.
            return super.query(uri, projection, selection, selectionArgs, sortOrder)
        }

        if (!wantsSize) {
            // Caller only wants DISPLAY_NAME — answer entirely from the registry.
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
                addRow(arrayOf(override.displayName))
            }
        }

        // Caller wants both NAME and SIZE.
        // Get the real cursor for SIZE, then substitute our display name.
        val realCursor = super.query(uri, projection, selection, selectionArgs, sortOrder)

        val sizeIndex = realCursor.getColumnIndex(OpenableColumns.SIZE)
        val size: Long? = if (realCursor.moveToFirst() && sizeIndex >= 0) {
            realCursor.getLong(sizeIndex)
        } else null
        realCursor.close()

        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf(override.displayName, size))
        }
    }
}

/**
 * A registered display name override and the time it was registered.
 *
 * @property displayName   The name to return for [OpenableColumns.DISPLAY_NAME].
 * @property registeredAt  Wall-clock ms when this entry was added; used for TTL pruning.
 */
data class DisplayNameEntry(
    val displayName:  String,
    val registeredAt: Long,
)