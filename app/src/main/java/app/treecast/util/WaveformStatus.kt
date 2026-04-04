package app.treecast.util

/**
 * Integer constants for the [app.treecast.data.entities.RecordingEntity.waveformStatus]
 * column. Using plain ints rather than an enum keeps Room happy with the
 * DEFAULT clause in the migration and avoids a custom TypeConverter.
 *
 * Lifecycle:
 *   PENDING → IN_PROGRESS → DONE
 *                         → FAILED   (worker retries are disabled; re-enqueue manually if needed)
 *
 * All rows created before this column existed are migrated to PENDING so they
 * get picked up and processed on the first launch after the update.
 */
object WaveformStatus {
    const val PENDING     = 0
    const val IN_PROGRESS = 1
    const val DONE        = 2
    const val FAILED      = 3
}