package app.soundtree.util

/**
 * Metadata written to [FILENAME] at the backup root by [BackupWorker] after
 * each successful run.
 *
 * Read by [DatabaseRestoreManager.readManifest] to populate the restore
 * wizard's Step 0 summary before the user commits to a restore. Nothing in
 * the restore sequence depends on this file being present — a missing or
 * malformed manifest is silently ignored and the wizard falls back to showing
 * no preview.
 *
 * @param appVersion     Version name of the build that wrote this manifest.
 * @param lastBackupAt   ISO-8601 UTC timestamp of when this file was written.
 * @param recordingCount Number of recordings in the library at backup time.
 * @param topicCount     Number of topics in the library at backup time.
 * @param markCount      Number of marks in the library at backup time.
 * @param snapshotCount  Number of `.db` snapshot files in `db/` at backup time.
 */
data class BackupManifest(
    val appVersion    : String,
    val lastBackupAt  : String,
    val recordingCount: Int,
    val topicCount    : Int,
    val markCount     : Int,
    val snapshotCount : Int,
) {
    companion object {
        const val FILENAME       = "soundtree-backup.json"
        const val SCHEMA_VERSION = 1

        /**
         * Parses a manifest from a raw JSON string.
         * Returns null if the string is missing, malformed, or missing required fields.
         */
        fun fromJson(json: String): BackupManifest? = runCatching {
            val o = org.json.JSONObject(json)
            BackupManifest(
                appVersion     = o.getString("appVersion"),
                lastBackupAt   = o.getString("lastBackupAt"),
                recordingCount = o.getInt("recordingCount"),
                topicCount     = o.getInt("topicCount"),
                markCount      = o.getInt("markCount"),
                snapshotCount  = o.getInt("snapshotCount"),
            )
        }.getOrNull()

        fun toJson(manifest: BackupManifest): String =
            org.json.JSONObject().apply {
                put("schemaVersion",   SCHEMA_VERSION)
                put("appVersion",      manifest.appVersion)
                put("lastBackupAt",    manifest.lastBackupAt)
                put("recordingCount",  manifest.recordingCount)
                put("topicCount",      manifest.topicCount)
                put("markCount",       manifest.markCount)
                put("snapshotCount",   manifest.snapshotCount)
            }.toString()
    }
}