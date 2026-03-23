package com.treecast.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.treecast.app.data.dao.MarkDao
import com.treecast.app.data.dao.RecordingDao
import com.treecast.app.data.dao.SessionDao
import com.treecast.app.data.dao.TopicDao
import com.treecast.app.data.entities.MarkEntity
import com.treecast.app.data.entities.RecordingEntity
import com.treecast.app.data.entities.SessionEntity
import com.treecast.app.data.entities.TopicEntity

@Database(
    entities = [
        SessionEntity::class,
        TopicEntity::class,
        RecordingEntity::class,
        MarkEntity::class,
    ],
    version = 7,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun topicDao(): TopicDao
    abstract fun recordingDao(): RecordingDao
    abstract fun markDao(): MarkDao

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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "treecast.db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}