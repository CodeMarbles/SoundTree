package com.treecast.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun topicDao(): TopicDao
    abstract fun recordingDao(): RecordingDao
    abstract fun markDao(): MarkDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "treecast.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}