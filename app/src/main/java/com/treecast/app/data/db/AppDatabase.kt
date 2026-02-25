package com.treecast.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.treecast.app.data.dao.CategoryDao
import com.treecast.app.data.dao.MarkDao
import com.treecast.app.data.dao.RecordingDao
import com.treecast.app.data.dao.SessionDao
import com.treecast.app.data.entities.CategoryEntity
import com.treecast.app.data.entities.MarkEntity
import com.treecast.app.data.entities.RecordingEntity
import com.treecast.app.data.entities.SessionEntity

@Database(
    entities = [
        SessionEntity::class,
        CategoryEntity::class,
        RecordingEntity::class,
        MarkEntity::class,
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun categoryDao(): CategoryDao
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
