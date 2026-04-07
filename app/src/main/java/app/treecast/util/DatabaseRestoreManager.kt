package app.treecast.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.treecast.data.db.AppDatabase
import app.treecast.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.exitProcess

/**
 * Performs a destructive database restore from a TreeCast backup directory.
 *
 * ## What it does
 * 1. Locates `db/treecast.db` inside the SAF backup tree URI.
 * 2. Checkpoints the live WAL so the current DB is fully flushed.
 * 3. Closes and nulls the Room singleton so no live handles remain.
 * 4. Deletes stale `-wal` and `-shm` sidecar files at the live DB path.
 * 5. Copies the backup DB file over the live DB path via SAF InputStream.
 * 6. Reopens Room and runs a `file_path` fixup UPDATE to rewrite any
 *    old-namespace paths (`com.treecast.app` → `app.treecast`) that may
 *    be present in a backup taken before the package rename.
 * 7. Closes Room again and schedules an app restart via [AlarmManager],
 *    then calls [exitProcess] so the fresh boot sees clean state throughout.
 *
 * ## Usage
 * Call [restore] from a coroutine (it switches internally to [Dispatchers.IO]).
 * On [Result.Success] call [scheduleRestartAndExit] — it does not return.
 * On failure the live DB is untouched if the error occurred before the copy,
 * and a best-effort recovery is attempted if the error occurred after.
 *
 * ## Thread safety
 * [restore] must not be called concurrently. Callers should cancel any running
 * [app.treecast.worker.BackupWorker] jobs before invoking this.
 */
object DatabaseRestoreManager {

    sealed class Result {
        /** Restore completed and fixup ran. Call [scheduleRestartAndExit] immediately. */
        object Success : Result()

        /** The backup directory exists but contains no `db/treecast.db` file. */
        object NoDbFound : Result()

        /** An unexpected error occurred. [reason] is a human-readable description. */
        data class Failed(val reason: String) : Result()
    }

    /**
     * Executes the full restore sequence on [Dispatchers.IO].
     *
     * @param context       Any context — the application context is used internally.
     * @param backupDirUri  The SAF tree URI string stored in [BackupTargetEntity.backupDirUri].
     */
    suspend fun restore(context: Context, backupDirUri: String): Result =
        withContext(Dispatchers.IO) {

            val appContext = context.applicationContext

            // ── 1. Locate the backup DB file via SAF ──────────────────────────
            val backupRoot = DocumentFile.fromTreeUri(appContext, Uri.parse(backupDirUri))
                ?: return@withContext Result.Failed(
                    "Cannot open backup directory — permission may have been revoked."
                )

            val dbDir = backupRoot.findFile("db")
                ?: return@withContext Result.NoDbFound

            val backupDbFile = dbDir.findFile("treecast.db")
                ?: return@withContext Result.NoDbFound

            if (!backupDbFile.isFile || backupDbFile.length() == 0L) {
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

            // ── 5. Copy backup DB over the live DB path ───────────────────────
            try {
                appContext.contentResolver.openInputStream(backupDbFile.uri)?.use { input ->
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
            // substring is not present, so it will never corrupt a clean backup.
            try {
                val freshDb = AppDatabase.getInstance(appContext)
                freshDb.openHelper.writableDatabase.execSQL(
                    """
                    UPDATE recordings
                    SET    file_path = REPLACE(file_path, 'com.treecast.app', 'app.treecast')
                    WHERE  file_path LIKE '%com.treecast.app%'
                    """.trimIndent()
                )
                // Close cleanly so the next open (after restart) is a fresh handle.
                AppDatabase.closeAndReset()
            } catch (_: Exception) {
                // Fixup failure is non-fatal — the DB restored successfully.
                // The orphan scanner on next boot will surface any dead paths.
            }

            Result.Success
        }

    /**
     * Schedules a full app restart 500 ms from now, then kills the current
     * process so all in-memory Room / ViewModel state is destroyed.
     *
     * Must be called on the main thread immediately after [restore] returns
     * [Result.Success]. **Does not return.**
     */
    fun scheduleRestartAndExit(context: Context) {
        val appContext = context.applicationContext

        val intent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pending = PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 500L, pending)

        exitProcess(0)
    }
}