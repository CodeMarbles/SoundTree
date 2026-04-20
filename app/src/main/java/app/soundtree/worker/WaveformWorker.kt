package app.soundtree.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.soundtree.data.db.AppDatabase
import app.soundtree.storage.StorageVolumeHelper
import app.soundtree.util.WaveformCache
import app.soundtree.util.WaveformExtractor
import app.soundtree.util.WaveformStatus

/**
 * WorkManager worker that generates a real PCM-decoded waveform for a single
 * recording and persists the result to [WaveformCache].
 *
 * Design goals:
 *   - **Idempotent**: if a cache file already exists at the canonical YYYY/MM
 *     path, the worker returns [Result.success] immediately without re-decoding.
 *     Note that a flat legacy file does NOT satisfy this check — the worker
 *     will re-decode and save to the correct location, migrating the file as
 *     a side effect of normal background processing.
 *   - **Survivable**: WorkManager persists the queue across process death.
 *     If the app is killed mid-decode, the job is re-run on the next launch.
 *   - **Deduplicated**: enqueued via [enqueue] which uses [ExistingWorkPolicy.KEEP].
 *   - **Volume-aware**: the cache is written to appdata/waveforms/YYYY/MM/ on
 *     the same volume as the source recording. If the volume is unmounted when
 *     the job runs, the worker returns [Result.failure] — WorkManager will not
 *     retry, and the recording's status stays FAILED until the next reprocess.
 *
 * Tags applied to every job:
 *   - [TAG] ("waveform") — used to observe all waveform jobs as a group.
 *   - "rid:<recordingId>" — used by the ViewModel to cross-reference the
 *     recording title from its in-memory list, since WorkInfo does not expose
 *     inputData to observers.
 */
class WaveformWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {

        /** WorkManager tag applied to every waveform job — use for group observation. */
        const val TAG = "waveform"

        /** Tag prefix for the per-job recording ID. Format: "rid:<id>" */
        const val TAG_RECORDING_ID_PREFIX = "rid:"

        const val KEY_RECORDING_ID        = "recording_id"
        const val KEY_FILE_PATH           = "file_path"
        const val KEY_STORAGE_VOLUME_UUID = "storage_volume_uuid"
        /** Epoch-ms creation timestamp; used to resolve the YYYY/MM cache subdirectory. */
        const val KEY_CREATED_AT          = "created_at"

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
         *
         * [storageVolumeUuid] is required so the worker can locate the correct
         * appdata/waveforms/ directory on the recording's volume.
         *
         * [createdAt] is the recording's epoch-ms creation timestamp, used to
         * resolve the YYYY/MM subdirectory within the waveform cache.
         */
        fun enqueue(
            context: Context,
            recordingId: Long,
            filePath: String,
            storageVolumeUuid: String,
            createdAt: Long,
        ) {
            val request = OneTimeWorkRequestBuilder<WaveformWorker>()
                .setInputData(
                    workDataOf(
                        KEY_RECORDING_ID        to recordingId,
                        KEY_FILE_PATH           to filePath,
                        KEY_STORAGE_VOLUME_UUID to storageVolumeUuid,
                        KEY_CREATED_AT          to createdAt,
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
        val recordingId       = inputData.getLong(KEY_RECORDING_ID, -1L)
        val filePath          = inputData.getString(KEY_FILE_PATH)           ?: return Result.failure()
        val storageVolumeUuid = inputData.getString(KEY_STORAGE_VOLUME_UUID) ?: return Result.failure()
        val createdAt         = inputData.getLong(KEY_CREATED_AT, -1L)
        if (recordingId < 0L || createdAt < 0L) return Result.failure()

        // Resolve the volume. If unmounted, we cannot read the audio file or
        // write the cache — return failure without marking IN_PROGRESS so the
        // recording stays PENDING and will be retried on next app launch when
        // the volume may be available again.
        val volume = StorageVolumeHelper.getVolumeByUuid(applicationContext, storageVolumeUuid)
            ?: return Result.failure()

        val db    = AppDatabase.getInstance(applicationContext)
        val cache = WaveformCache(volume.rootDir)

        // ── Idempotency check ─────────────────────────────────────────────────
        // exists() only checks the canonical YYYY/MM path. A surviving flat legacy
        // file returns false, causing re-decode + save to the canonical location —
        // effectively migrating the file as a background side effect.
        if (cache.exists(recordingId, createdAt)) {
            db.recordingDao().updateWaveformStatus(recordingId, WaveformStatus.DONE)
            return Result.success()
        }

        // ── Decode ────────────────────────────────────────────────────────────
        db.recordingDao().updateWaveformStatus(recordingId, WaveformStatus.IN_PROGRESS)

        return try {
            val amplitudes = WaveformExtractor.extract(filePath)
            cache.save(recordingId, amplitudes, createdAt)
            db.recordingDao().updateWaveformStatus(recordingId, WaveformStatus.DONE)
            Result.success()
        } catch (e: Exception) {
            db.recordingDao().updateWaveformStatus(recordingId, WaveformStatus.FAILED)
            Result.failure()
        }
    }
}