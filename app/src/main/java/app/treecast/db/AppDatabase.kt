package app.treecast.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.treecast.data.dao.BackupLogDao
import app.treecast.data.dao.BackupTargetDao
import app.treecast.data.dao.MarkDao
import app.treecast.data.dao.RecordingDao
import app.treecast.data.dao.TopicDao
import app.treecast.data.entities.BackupLogEntity
import app.treecast.data.entities.BackupLogEventEntity
import app.treecast.data.entities.BackupTargetEntity
import app.treecast.data.entities.MarkEntity
import app.treecast.data.entities.RecordingEntity
import app.treecast.data.entities.TopicEntity

@Database(
    entities = [
        TopicEntity::class,
        RecordingEntity::class,
        MarkEntity::class,
        BackupTargetEntity::class,
        BackupLogEntity::class,
        BackupLogEventEntity::class,
    ],
    version = 11,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun topicDao(): TopicDao
    abstract fun recordingDao(): RecordingDao
    abstract fun markDao(): MarkDao
    abstract fun backupTargetDao(): BackupTargetDao
    abstract fun backupLogDao(): BackupLogDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * v3 → v4: Add storage_volume_uuid column to recordings.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE recordings ADD COLUMN storage_volume_uuid TEXT NOT NULL DEFAULT 'primary'"
                )
            }
        }

        /**
         * v4 → v5: Add waveform_status column to recordings.
         *
         * DEFAULT 0 = WaveformStatus.PENDING — all existing recordings will be
         * picked up and processed by WaveformWorker on the first launch after
         * this migration runs.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE recordings ADD COLUMN waveform_status INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v5 → v6: Remove is_collapsed from topics.
         *
         * Collapse state is now UI-only, stored in SharedPreferences via
         * MainViewModel._collapsedIds. It never belonged in the DB — a topic's
         * collapsed/expanded state is a display preference, not domain data,
         * and needs to be independent per-view as more tree views are added.
         *
         * SQLite < 3.35 does not support ALTER TABLE … DROP COLUMN, so we do
         * the standard recreate-copy-drop-rename dance. Foreign key enforcement
         * is disabled for the duration to avoid constraint errors mid-migration.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys=OFF")

                // No DEFAULT clauses here — Room's entity uses Kotlin-level defaults with no
                // @ColumnInfo(defaultValue=...) annotation, so Room expects defaultValue='undefined'
                // for every column. Explicit SQL DEFAULTs would cause a schema mismatch.
                //
                // The FK references 'topics' (the final name after rename), not 'topics_new'.
                // SQLite doesn't validate FK targets at CREATE time, so this is safe and ensures
                // the stored FK metadata matches what Room expects after the rename.
                db.execSQL("""
                    CREATE TABLE topics_new (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        parent_id   INTEGER,
                        name        TEXT    NOT NULL,
                        description TEXT    NOT NULL,
                        icon        TEXT    NOT NULL,
                        color       TEXT    NOT NULL,
                        sort_order  INTEGER NOT NULL,
                        created_at  INTEGER NOT NULL,
                        updated_at  INTEGER NOT NULL,
                        FOREIGN KEY(parent_id) REFERENCES topics(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO topics_new
                        (id, parent_id, name, description, icon, color, sort_order, created_at, updated_at)
                    SELECT
                        id, parent_id, name, description, icon, color, sort_order, created_at, updated_at
                    FROM topics
                """.trimIndent())

                db.execSQL("DROP TABLE topics")
                db.execSQL("ALTER TABLE topics_new RENAME TO topics")
                db.execSQL("CREATE INDEX index_topics_parent_id ON topics(parent_id)")

                db.execSQL("PRAGMA foreign_keys=ON")
            }
        }

        /**
         * v6 → v7: Add db_inserted_at column to recordings.
         *
         * Backfilled with created_at so existing rows are consistent —
         * for all pre-existing recordings these two values are synonymous.
         * The "NEW" badge (< 30 min threshold) will correctly not fire for
         * any existing row since db_inserted_at will be older than 30 min.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE recordings ADD COLUMN db_inserted_at INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "UPDATE recordings SET db_inserted_at = created_at"
                )
            }
        }

        /**
         * v7 → v8: Remove the sessions table.
         *
         * Session tracking was removed — the table is no longer referenced by
         * any entity, DAO, or repository code.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS sessions")
            }
        }

        /**
         * v8 → v9: Add backup_targets, backup_logs, and backup_log_errors tables.
         *
         * - backup_targets: user-designated backup destination volumes and their
         *   per-target configuration (on-connect trigger, scheduled interval,
         *   SAF directory URI, last backup timestamp, preferences backup toggle).
         * - backup_logs: one row per backup run, recording provenance, outcome,
         *   and aggregate file/byte stats. Denormalizes target fields so log
         *   entries remain readable if the target is later removed.
         * - backup_log_errors: child table for per-file failures and warnings
         *   within a run. Cascade-deleted with their parent log row.
         *
         * No existing data is affected — this is a purely additive migration.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS backup_targets (
                        volume_uuid          TEXT    NOT NULL PRIMARY KEY,
                        on_connect_enabled   INTEGER NOT NULL DEFAULT 1,
                        scheduled_enabled    INTEGER NOT NULL DEFAULT 1,
                        interval_hours       INTEGER NOT NULL DEFAULT 24,
                        last_backup_at       INTEGER,
                        backup_dir_uri       TEXT,
                        backup_preferences   INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS backup_logs (
                        id                              INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        backup_target_uuid              TEXT,
                        volume_uuid                     TEXT    NOT NULL,
                        volume_label                    TEXT    NOT NULL,
                        backup_dir_uri                  TEXT    NOT NULL,
                        trigger                         TEXT    NOT NULL,
                        started_at                      INTEGER NOT NULL,
                        ended_at                        INTEGER,
                        status                          TEXT,
                        error_message                   TEXT,
                        db_backed_up                    INTEGER NOT NULL DEFAULT 0,
                        preferences_backed_up           INTEGER NOT NULL DEFAULT 0,
                        files_examined                  INTEGER NOT NULL DEFAULT 0,
                        files_copied                    INTEGER NOT NULL DEFAULT 0,
                        files_skipped                   INTEGER NOT NULL DEFAULT 0,
                        files_failed                    INTEGER NOT NULL DEFAULT 0,
                        bytes_copied                    INTEGER NOT NULL DEFAULT 0,
                        total_recordings_on_source      INTEGER NOT NULL DEFAULT 0,
                        total_recordings_on_dest        INTEGER NOT NULL DEFAULT 0,
                        total_bytes_on_destination      INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(backup_target_uuid) REFERENCES backup_targets(volume_uuid) ON DELETE SET NULL
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_backup_logs_backup_target_uuid ON backup_logs(backup_target_uuid)"
                )

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS backup_log_errors (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        log_id        INTEGER NOT NULL,
                        severity      TEXT    NOT NULL,
                        source_path   TEXT,
                        error_message TEXT    NOT NULL,
                        occurred_at   INTEGER NOT NULL,
                        FOREIGN KEY(log_id) REFERENCES backup_logs(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_backup_log_errors_log_id ON backup_log_errors(log_id)"
                )
            }
        }

        /**
         * v9 → v10: Rename backup_log_errors → backup_log_events.
         *
         * The child event table is renamed to reflect its expanded purpose —
         * it now stores INFO milestone events (when verbose backup logging is
         * enabled) in addition to WARNING and ERROR incidents. The column
         * schema is unchanged; severity remains a TEXT column that now also
         * accepts the value "INFO". The index is recreated under the new name.
         *
         * Existing WARNING/ERROR rows are preserved and remain fully valid.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE backup_log_errors RENAME TO backup_log_events")
                db.execSQL("DROP INDEX IF EXISTS index_backup_log_errors_log_id")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_backup_log_events_log_id ON backup_log_events(log_id)"
                )
            }
        }

        /**
         * v10 → v11: Add volume_label column to backup_targets.
         *
         * Caches the OS-provided display label for each backup target volume
         * so the Settings tab can show a human-readable name even when the
         * drive is not currently connected.
         *
         * The column is nullable with no default — existing rows will have
         * NULL until the first time a backup runs or the user has the volume
         * mounted, at which point [BackupTargetDao.setVolumeLabel] populates it.
         * The UI falls back through: live OS label → cached label → UUID.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE backup_targets ADD COLUMN volume_label TEXT"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "treecast.db"
                )
                    .addMigrations(
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                    )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}