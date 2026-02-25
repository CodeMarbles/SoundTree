package com.treecast.app.data.dao

import androidx.room.*
import com.treecast.app.data.entities.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    /** All categories — used to reconstruct the full tree in-memory */
    @Query("SELECT * FROM categories ORDER BY sort_order ASC, name ASC")
    fun getAllCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sort_order ASC, name ASC")
    suspend fun getAllCategoriesOnce(): List<CategoryEntity>

    /** Direct children of a given parent */
    @Query("SELECT * FROM categories WHERE parent_id = :parentId ORDER BY sort_order ASC, name ASC")
    fun getChildren(parentId: Long): Flow<List<CategoryEntity>>

    /** Root categories (no parent) */
    @Query("SELECT * FROM categories WHERE parent_id IS NULL ORDER BY sort_order ASC, name ASC")
    fun getRoots(): Flow<List<CategoryEntity>>

    @Query("UPDATE categories SET is_collapsed = :collapsed WHERE id = :id")
    suspend fun setCollapsed(id: Long, collapsed: Boolean)

    @Query("UPDATE categories SET updated_at = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long = System.currentTimeMillis())
}
