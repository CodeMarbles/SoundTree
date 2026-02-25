package com.treecast.app.data.repository

import com.treecast.app.data.entities.CategoryEntity
import com.treecast.app.data.entities.RecordingEntity

// ─────────────────────────────────────────────────────────
// Domain models used by the UI layer
// ─────────────────────────────────────────────────────────

/** A node in the in-memory podcast tree */
data class TreeNode(
    val category: CategoryEntity,
    val children: MutableList<TreeNode> = mutableListOf(),
    val recordings: MutableList<RecordingEntity> = mutableListOf(),
    var depth: Int = 0
)

/** Flat item sealed class for the RecyclerView tree adapter */
sealed class TreeItem {
    data class Node(
        val treeNode: TreeNode,
        val depth: Int,
        val hasChildren: Boolean,
        val isCollapsed: Boolean
    ) : TreeItem()

    data class Leaf(
        val recording: RecordingEntity,
        val depth: Int
    ) : TreeItem()
}

// ─────────────────────────────────────────────────────────
// Tree builder: converts flat DB lists → tree structure
// ─────────────────────────────────────────────────────────
object TreeBuilder {

    /**
     * Build a forest (list of root TreeNodes) from flat DB lists.
     * Recordings without a categoryId go to [inbox].
     */
    fun build(
        categories: List<CategoryEntity>,
        recordings: List<RecordingEntity>
    ): List<TreeNode> {
        val nodeMap = categories.associate { it.id to TreeNode(category = it) }

        // Assign recordings to their category nodes
        for (rec in recordings) {
            val catId = rec.categoryId ?: continue
            nodeMap[catId]?.recordings?.add(rec)
        }

        // Wire parent-child relationships
        val roots = mutableListOf<TreeNode>()
        for (cat in categories) {
            val node = nodeMap[cat.id] ?: continue
            val parentId = cat.parentId
            if (parentId == null) {
                node.depth = 0
                roots.add(node)
            } else {
                val parent = nodeMap[parentId]
                if (parent != null) {
                    node.depth = parent.depth + 1
                    parent.children.add(node)
                } else {
                    // Orphan → promote to root
                    node.depth = 0
                    roots.add(node)
                }
            }
        }

        // Sort each level
        roots.sortBy { it.category.sortOrder }
        nodeMap.values.forEach { node ->
            node.children.sortBy { it.category.sortOrder }
            node.recordings.sortBy { it.sortOrder }
        }

        return roots
    }

    /**
     * Flatten the tree into a RecyclerView-compatible list,
     * respecting each node's [isCollapsed] flag.
     */
    fun flatten(
        roots: List<TreeNode>,
        collapsedIds: Set<Long> = emptySet()
    ): List<TreeItem> {
        val result = mutableListOf<TreeItem>()
        for (root in roots) {
            flattenNode(root, result, collapsedIds)
        }
        return result
    }

    private fun flattenNode(
        node: TreeNode,
        result: MutableList<TreeItem>,
        collapsedIds: Set<Long>
    ) {
        val isCollapsed = node.category.id in collapsedIds
        result.add(
            TreeItem.Node(
                treeNode = node,
                depth = node.depth,
                hasChildren = node.children.isNotEmpty() || node.recordings.isNotEmpty(),
                isCollapsed = isCollapsed
            )
        )
        if (!isCollapsed) {
            for (child in node.children) {
                flattenNode(child, result, collapsedIds)
            }
            for (rec in node.recordings) {
                result.add(TreeItem.Leaf(recording = rec, depth = node.depth + 1))
            }
        }
    }
}
