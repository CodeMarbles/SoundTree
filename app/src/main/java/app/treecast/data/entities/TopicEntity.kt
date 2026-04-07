package app.treecast.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.treecast.util.Icons

/**
 * Represents a node (folder) in the topic tree.
 * parentId == null means this is a root-level topic.
 *
 * The full tree is reconstructed in-memory from a flat list of TopicEntities
 * by [app.treecast.data.repository.TreeBuilder].
 *
 * Depth is unlimited — the only constraint is that cycles must not be created.
 *
 * NOTE: sort_order is a property of this node's position among its siblings
 * under its current single parent. When the data model is migrated to a
 * DAG (multi-parent) structure, sort_order will move to the edge table.
 */
@Entity(
    tableName = "topics",
    foreignKeys = [
        ForeignKey(
            entity = TopicEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("parent_id")]
)
data class TopicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Null = root node */
    @ColumnInfo(name = "parent_id") val parentId: Long? = null,

    @ColumnInfo(name = "name") val name: String,

    @ColumnInfo(name = "description") val description: String = "",

    /** Emoji or icon identifier shown on tiles/tree nodes */
    @ColumnInfo(name = "icon") val icon: String = Icons.DEFAULT_TOPIC,

    /** UI color accent hex string e.g. "#FF6B6B" */
    @ColumnInfo(name = "color") val color: String = "#6C63FF",

    /** Display order among siblings under the same parent */
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()

    // isCollapsed removed in DB v6 — collapse state is now UI-only,
    // held in MainViewModel._collapsedIds and persisted via SharedPreferences.
)