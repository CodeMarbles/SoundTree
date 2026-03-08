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
    version = 5,
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
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
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
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE recordings ADD COLUMN waveform_status INTEGER NOT NULL DEFAULT 0"
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
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}