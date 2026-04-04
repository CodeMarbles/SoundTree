package app.treecast.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.treecast.data.db.AppDatabase
import app.treecast.util.WaveformCache
import app.treecast.util.WaveformExtractor
import app.treecast.util.WaveformStatus
import app.treecast.worker.WaveformWorker.Companion.TAG
import app.treecast.worker.WaveformWorker.Companion.enqueue

/**
 * WorkManager worker that generates a real PCM-decoded waveform for a single
 * recording and persists the result to [WaveformCache].
 *
 * Design goals:
 *   - **Idempotent**: if a cache file already exists the worker returns
 *     [Result.success] immediately without re-decoding.
 *   - **Survivable**: WorkManager persists the queue across process death.
 *     If the app is killed mid-decode, the job is re-run on the next launch.
 *   - **Deduplicated**: enqueued via [enqueue] which uses [ExistingWorkPolicy.KEEP].
 *
 * Tags applied to every job:
 *   - [TAG] ("waveform") — used to observe all waveform jobs as a group.
 *   - "rid:<recordingId>" — used by the ViewModel to cross-reference the
 *     recording title from its in-memory list, since WorkInfo does not expose
 *     inputData to observers.
 */
class WaveformWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        /** WorkManager tag applied to every waveform job — use for group observation. */
        const val TAG = "waveform"

        /** Tag prefix for the per-job recording ID. Format: "rid:<id>" */
        const val TAG_RECORDING_ID_PREFIX = "rid:"

        const val KEY_RECORDING_ID = "recording_id"
        const val KEY_FILE_PATH    = "file_path"

        /** Extracts the recording ID embedded in a WorkInfo's tags, or null if absent. */
        fun recordingIdFromTags(tags: Set<String>): Long? =
            tags.firstOrNull { it.startsWith(TAG_RECORDING_ID_PREFIX) }
                ?.removePrefix(TAG_RECORDING_ID_PREFIX)
                ?.toLongOrNull()

        /**
         * Enqueues a waveform generation job for [recordingId].
         *
         * Uses [ExistingWorkPolicy.KEEP] so a second call for the same recording
         * while the first job is still ENQUEUED or RUNNING is a no-op.
         */
        fun enqueue(
            context: Context,
            recordingId: Long,
            filePath: String
        ) {
            val request = OneTimeWorkRequestBuilder<WaveformWorker>()
                .setInputData(
                    workDataOf(
                        KEY_RECORDING_ID to recordingId,
                        KEY_FILE_PATH    to filePath
                    )
                )
                .addTag(TAG)
                .addTag("$TAG_RECORDING_ID_PREFIX$recordingId")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "waveform_$recordingId",
                    ExistingWorkPolicy.KEEP,
                    request
                )
        }
    }

    override suspend fun doWork(): Result {
        val recordingId = inputData.getLong(KEY_RECORDING_ID, -1L)
        val filePath    = inputData.getString(KEY_FILE_PATH) ?: return Result.failure()
        if (recordingId < 0L) return Result.failure()

        // ── Testing delay — remove when no longer needed ──────────────────────
        //delay(500L)

        val db    = AppDatabase.getInstance(applicationContext)
        val cache = WaveformCache(applicationContext)

        // ── Idempotency check ─────────────────────────────────────────────────
        if (cache.exists(recordingId)) {
            db.recordingDao().updateWaveformStatus(recordingId, WaveformStatus.DONE)
            return Result.success()
        }

        // ── Decode ────────────────────────────────────────────────────────────
        db.recordingDao().updateWaveformStatus(recordingId, WaveformStatus.IN_PROGRESS)

        return try {
            val amplitudes = WaveformExtractor.extract(filePath)
            cache.save(recordingId, amplitudes)
            db.recordingDao().updateWaveformStatus(recordingId, WaveformStatus.DONE)
            Result.success()
        } catch (e: Exception) {
            db.recordingDao().updateWaveformStatus(recordingId, WaveformStatus.FAILED)
            Result.failure()
        }
    }
}