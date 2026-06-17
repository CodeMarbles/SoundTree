package app.soundtree.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.soundtree.data.db.AppDatabase
import java.util.concurrent.TimeUnit

/**
 * Applies a daily exponential decay to all topic scores, implementing the
 * slow fade that keeps the "Frequent Topics" section relevant without
 * permanently pinning topics that were once heavily used.
 *
 * ## Scheduling
 * Enqueued as a [PeriodicWorkRequest] with a 24-hour interval via [enqueue].
 * WorkManager may fire this up to [FLEX_HOURS] hours early within each period
 * to batch it with other work and reduce wake-ups.
 *
 * ## Idempotency guard
 * The worker checks [PREF_LAST_DECAY_RAN_AT] in SharedPreferences before doing
 * any work. If the last run was fewer than [MIN_INTERVAL_HOURS] hours ago, it
 * exits immediately. This protects against WorkManager firing the job twice in
 * quick succession (possible after an app update or a device restore).
 *
 * ## App-usage gate
 * If [PREF_LAST_APP_FOREGROUND_AT] is older than [MAX_IDLE_DAYS] days, the
 * worker skips the decay pass. This avoids grinding all scores to zero for
 * users who have been away from the app for an extended period — their topic
 * history stays roughly frozen until they return, at which point natural decay
 * resumes on the next use.
 *
 * ## Decay math
 * Each run multiplies every topic's score by [DECAY_FACTOR] (0.90 per day).
 * A topic with a score of 10.0 reaches ~3.5 after 10 days, ~1.2 after 20 days,
 * and drops below the 0.1 display threshold after ~43 days without any use.
 */
class DecayWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        /** Unique WorkManager job name — only one decay job should ever be live. */
        const val WORK_NAME = "topic_score_decay"

        /** Score multiplier applied per day. 0.90 = 10 % decay each 24 hours. */
        const val DECAY_FACTOR = 0.90

        /**
         * How often the periodic job is scheduled.
         * WorkManager enforces a platform minimum of 15 minutes, but 24 hours
         * is the intended cadence for score decay.
         */
        const val INTERVAL_HOURS = 24L

        /**
         * Flex window within the period. WorkManager may fire the job up to this
         * many hours early so it can be batched with other deferred work.
         */
        const val FLEX_HOURS = 4L

        /**
         * Minimum elapsed time between actual decay runs.
         * Prevents double-firing if WorkManager schedules the job twice in a row.
         */
        const val MIN_INTERVAL_HOURS = 20L

        /**
         * If the user hasn't brought the app to the foreground in this many days,
         * skip decay so scores don't silently evaporate during a long absence.
         */
        const val MAX_IDLE_DAYS = 14L

        private const val PREFS_NAME              = "soundtree_settings"
        const val PREF_LAST_DECAY_RAN_AT          = "topic_decay_last_ran_at"
        const val PREF_LAST_APP_FOREGROUND_AT     = "last_app_foreground_at"

        /**
         * Enqueues the periodic decay job, replacing any existing schedule.
         *
         * Safe to call on every app launch — [ExistingPeriodicWorkPolicy.UPDATE]
         * keeps the existing job alive and just refreshes its schedule, avoiding
         * the doubled-up firing that [ExistingPeriodicWorkPolicy.REPLACE] can
         * cause by cancelling and re-enqueueing.
         */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<DecayWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS,
                FLEX_HOURS,     TimeUnit.HOURS,
            ).build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now   = System.currentTimeMillis()

        // ── Idempotency guard ─────────────────────────────────────────────────
        val lastRanAt = prefs.getLong(PREF_LAST_DECAY_RAN_AT, 0L)
        val minIntervalMs = TimeUnit.HOURS.toMillis(MIN_INTERVAL_HOURS)
        if (now - lastRanAt < minIntervalMs) return Result.success()

        // ── App-usage gate ────────────────────────────────────────────────────
        val lastForegroundAt = prefs.getLong(PREF_LAST_APP_FOREGROUND_AT, 0L)
        val maxIdleMs = TimeUnit.DAYS.toMillis(MAX_IDLE_DAYS)
        if (lastForegroundAt > 0L && now - lastForegroundAt > maxIdleMs) return Result.success()

        // ── Decay ─────────────────────────────────────────────────────────────
        AppDatabase.getInstance(applicationContext)
            .topicDao()
            .decayAllScores(DECAY_FACTOR)

        prefs.edit().putLong(PREF_LAST_DECAY_RAN_AT, now).apply()

        return Result.success()
    }
}