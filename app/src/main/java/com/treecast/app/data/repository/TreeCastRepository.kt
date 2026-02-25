package com.treecast.app.data.repository

import android.content.Context
import com.treecast.app.data.db.AppDatabase
import com.treecast.app.data.entities.CategoryEntity
import com.treecast.app.data.entities.MarkEntity
import com.treecast.app.data.entities.RecordingEntity
import com.treecast.app.data.entities.SessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class TreeCastRepository(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val sessionDao = db.sessionDao()
    private val categoryDao = db.categoryDao()
    private val recordingDao = db.recordingDao()
    private val markDao = db.markDao()

    // ── Session ──────────────────────────────────────────────────────
    suspend fun openSession(): Long {
        val session = SessionEntity()
        return sessionDao.insert(session)
    }

    suspend fun closeSession(id: Long) {
        val now = System.currentTimeMillis()
        // Inline lookup — avoids the broken inner extension function pattern
        val session = sessionDao.getLastSession()?.takeIf { it.id == id } ?: return
        sessionDao.closeSession(id, now, now - session.openedAt)
    }

    suspend fun getLastSession(): SessionEntity? = sessionDao.getLastSession()

    // ── Categories ──────────────────────────────────────────────────
    suspend fun createCategory(
        name: String,
        parentId: Long? = null,
        icon: String = "🎙️",
        color: String = "#6C63FF"
    ): Long = categoryDao.insert(CategoryEntity(name = name, parentId = parentId, icon = icon, color = color))

    suspend fun updateCategory(category: CategoryEntity) = categoryDao.update(category)
    suspend fun deleteCategory(category: CategoryEntity) = categoryDao.delete(category)
    suspend fun toggleCollapse(id: Long, collapsed: Boolean) = categoryDao.setCollapsed(id, collapsed)
    fun getAllCategories() = categoryDao.getAllCategories()

    // ── Recordings ──────────────────────────────────────────────────
    suspend fun saveRecording(
        filePath: String,
        durationMs: Long,
        fileSizeBytes: Long,
        title: String,
        categoryId: Long? = null
    ): Long = recordingDao.insert(
        RecordingEntity(
            filePath = filePath,
            durationMs = durationMs,
            fileSizeBytes = fileSizeBytes,
            title = title,
            categoryId = categoryId
        )
    )

    suspend fun updateRecording(recording: RecordingEntity) = recordingDao.update(recording)
    suspend fun deleteRecording(recording: RecordingEntity) = recordingDao.delete(recording)
    suspend fun moveRecording(id: Long, categoryId: Long?) = recordingDao.moveToCategory(id, categoryId)
    suspend fun renameRecording(id: Long, title: String) = recordingDao.rename(id, title)
    suspend fun setFavourite(id: Long, fav: Boolean) = recordingDao.setFavourite(id, fav)
    suspend fun updatePlayback(id: Long, posMs: Long, listened: Boolean) =
        recordingDao.updatePlaybackState(id, posMs, listened)

    fun getAllRecordings(): Flow<List<RecordingEntity>> = recordingDao.getAll()
    fun getInbox(): Flow<List<RecordingEntity>> = recordingDao.getInbox()
    fun getFavourites(): Flow<List<RecordingEntity>> = recordingDao.getFavourites()
    fun searchRecordings(q: String): Flow<List<RecordingEntity>> = recordingDao.search(q)

    // ── Combined tree flow ───────────────────────────────────────────
    fun getTreeFlow(): Flow<List<TreeNode>> = combine(
        categoryDao.getAllCategories(),
        recordingDao.getAll()
    ) { cats, recs ->
        TreeBuilder.build(cats, recs)
    }

    companion object {
        @Volatile private var INSTANCE: TreeCastRepository? = null
        fun getInstance(context: Context): TreeCastRepository =
            INSTANCE ?: synchronized(this) {
                TreeCastRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
    // ── Marks ──────────────────────────────────────────────────────────
    fun getMarksForRecording(recordingId: Long) = markDao.getMarksForRecording(recordingId)
    suspend fun addMark(recordingId: Long, positionMs: Long) =
        markDao.insert(MarkEntity(recordingId = recordingId, positionMs = positionMs))
    suspend fun deleteMark(id: Long) = markDao.deleteById(id)

}
