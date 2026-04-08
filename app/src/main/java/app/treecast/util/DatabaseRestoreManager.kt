package app.treecast.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.treecast.data.db.AppDatabase
import app.treecast.ui.MainActivity
import app.treecast.util.DatabaseRestoreManager.listSnapshots
import app.treecast.util.DatabaseRestoreManager.restore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Utilities for listing and restoring TreeCast database snapshots.
 *
 * ## Snapshot layout (inside the user-chosen backup root)
 *
 *   db/
 *     treecast_YYYYMMDD_HHmmss.db   ← versioned snapshots (new format)
 *     treecast.db                   ← legacy flat copy (old format, preserved forever)
 *
 * ## Restore sequence (see [restore])
 * 1. Caller provides the exact [DocumentFile] to restore (chosen via [listSnapshots]).
 * 2. Checkpoints the live WAL so the current DB is fully flushed.
 * 3. Closes and nulls the Room singleton so no live handles remain.
 * 4. Deletes stale `-wal` and `-shm` sidecar files at the live DB path.
 * 5. Copies the chosen snapshot file over the live DB path via SAF InputStream.
 * 6. Reopens Room and runs a `file_path` fixup UPDATE to rewrite any
 *    old-namespace paths (`com.treecast.app` → `app.treecast`) present in
 *    backups taken before the package rename.
 * 7. Closes Room again, schedules an app restart via [AlarmManager], then
 *    calls [exitProcess]. Does not return on success.
 *
 * ## Thread safety
 * [restore] must not be called concurrently. Callers should cancel any running
 * [app.treecast.worker.BackupWorker] jobs before invoking this.
 */
object DatabaseRestoreManager {

    sealed class Result {
        /** Restore completed and fixup ran. Call [scheduleRestartAndExit] immediately. */
        object Success : Result()

        /** The provided file was null, missing, or empty. */
        object NoDbFound : Result()

        /** An unexpected error occurred. [reason] is a human-readable description. */
        data class Failed(val reason: String) : Result()
    }

    // ── Snapshot listing ──────────────────────────────────────────────────────

    /**
     * A single restorable database snapshot found in a backup's `db/` directory.
     *
     * @param file        The SAF [DocumentFile] for this snapshot.
     * @param displayName Human-readable label shown in the selection dialog.
     * @param isLegacy    True if this is the old flat `treecast.db` (no timestamp).
     */
    data class DbSnapshot(
        val file: DocumentFile,
        val displayName: String,
        val isLegacy: Boolean,
    )

    /**
     * Scans the `db/` subdirectory of [backupDirUri] and returns all restorable
     * snapshots. Timestamped snapshots are sorted newest-first; the legacy flat
     * `treecast.db`, if present, is appended at the end.
     *
     * Returns an empty list if the `db/` directory does not exist or contains
     * no recognisable snapshot files.
     */
    suspend fun listSnapshots(context: Context, backupDirUri: String): List<DbSnapshot> =
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(
                context.applicationContext, Uri.parse(backupDirUri)
            ) ?: return@withContext emptyList()

            val dbDir = root.findFile("db") ?: return@withContext emptyList()

            val parseFmt   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val displayFmt = SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault())

            val timestamped = mutableListOf<DbSnapshot>()
            var legacy: DbSnapshot? = null

            for (file in dbDir.listFiles()) {
                val name = file.name ?: continue
                if (!file.isFile) continue
                when {
                    name.matches(Regex("treecast_\\d{8}_\\d{6}\\.db")) -> {
                        val raw = name.removePrefix("treecast_").removeSuffix(".db")
                        val label = try {
                            displayFmt.format(parseFmt.parse(raw)!!)
                        } catch (_: Exception) { name }
                        timestamped += DbSnapshot(file, label, isLegacy = false)
                    }
                    name == "treecast.db" -> {
                        legacy = DbSnapshot(file, "Legacy backup (treecast.db)", isLegacy = true)
                    }
                }
            }

            timestamped.sortedByDescending { it.file.name } + listOfNotNull(legacy)
        }

    // ── Restore ───────────────────────────────────────────────────────────────

    /**
     * Executes the full restore sequence on [Dispatchers.IO].
     *
     * @param context    Any context — the application context is used internally.
     * @param backupFile The exact snapshot [DocumentFile] chosen by the user via
     *                   [listSnapshots]. The caller is responsible for validation
     *                   (i.e. only pass files returned by [listSnapshots]).
     */
    suspend fun restore(context: Context, backupFile: DocumentFile): Result =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext

            // ── 1. Validate the chosen snapshot file ──────────────────────────
            if (!backupFile.isFile || backupFile.length() == 0L) {
                return@withContext Result.NoDbFound
            }

            // ── 2. Checkpoint the live WAL (best-effort) ──────────────────────
            try {
                val db = AppDatabase.getInstance(appContext)
                db.openHelper.writableDatabase
                    .query("PRAGMA wal_checkpoint(FULL)")
                    .close()
            } catch (_: Exception) {
                // If the DB is already broken we press on regardless.
            }

            // ── 3. Close and null the Room singleton ──────────────────────────
            AppDatabase.closeAndReset()

            // ── 4. Delete stale WAL / SHM sidecars ───────────────────────────
            val liveDbFile = appContext.getDatabasePath("treecast.db")
            File(liveDbFile.path + "-wal").delete()
            File(liveDbFile.path + "-shm").delete()

            // Ensure the databases/ directory exists (first-run edge case).
            liveDbFile.parentFile?.mkdirs()

            // ── 5. Copy chosen snapshot over the live DB path ─────────────────
            try {
                appContext.contentResolver.openInputStream(backupFile.uri)?.use { input ->
                    liveDbFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: return@withContext Result.Failed(
                    "Could not open backup database for reading."
                )
            } catch (e: Exception) {
                return@withContext Result.Failed("Database copy failed: ${e.message}")
            }

            // ── 6. Reopen Room and run the file_path namespace fixup ──────────
            //
            // Rewrites any file_path values that still carry the old package
            // name from before the com.treecast.app → app.treecast rename.
            // Safe to run unconditionally — REPLACE() is a no-op when the
            // substring is not present.
            try {
                val db = AppDatabase.getInstance(appContext)
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE recordings SET file_path = REPLACE(file_path, " +
                            "'com.treecast.app', 'app.treecast')"
                )
                AppDatabase.closeAndReset()
            } catch (_: Exception) {
                // Fixup failure is non-fatal — Room will still open.
            }

            Result.Success
        }

    // ── Restart ───────────────────────────────────────────────────────────────

    /**
     * Schedules an app restart via [AlarmManager] and kills the current process.
     * Must be called on the main thread. Does not return.
     */
    fun scheduleRestartAndExit(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.RTC, System.currentTimeMillis() + 500L, pi)
        exitProcess(0)
    }
}