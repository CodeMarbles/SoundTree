package com.treecast.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a node (folder) in the podcast tree.
 * parentId == null means this is a root-level category.
 *
 * The full tree is reconstructed in-memory from a flat list of CategoryEntities.
 * Depth is unlimited — the only constraint is that cycles must not be created.
 */
@Entity(
    tableName = "categories",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("parent_id")]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Null = root node */
    @ColumnInfo(name = "parent_id") val parentId: Long? = null,

    @ColumnInfo(name = "name") val name: String,

    @ColumnInfo(name = "description") val description: String = "",

    /** Emoji or icon identifier shown on tiles/tree nodes */
    @ColumnInfo(name = "icon") val icon: String = "🎙️",

    /** UI color accent hex string e.g. "#FF6B6B" */
    @ColumnInfo(name = "color") val color: String = "#6C63FF",

    /** Display order among siblings */
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),

    /** Whether this node is collapsed in the tree view */
    @ColumnInfo(name = "is_collapsed") val isCollapsed: Boolean = false
)
