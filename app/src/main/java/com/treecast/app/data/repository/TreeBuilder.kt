package com.treecast.app.data.repository

import com.treecast.app.data.entities.RecordingEntity
import com.treecast.app.data.entities.TopicEntity

// ─────────────────────────────────────────────────────────
// Domain models used by the UI layer
// ─────────────────────────────────────────────────────────

/** A node in the in-memory podcast tree */
data class TreeNode(
    val topic: TopicEntity,
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
     * Recordings without a topicId go to [inbox].
     */
    fun build(
        topics: List<TopicEntity>,
        recordings: List<RecordingEntity>
    ): List<TreeNode> {
        val nodeMap = topics.associate { it.id to TreeNode(topic = it) }

        // Assign recordings to their topic nodes
        for (rec in recordings) {
            val topicId = rec.topicId ?: continue
            nodeMap[topicId]?.recordings?.add(rec)
        }

        // Wire parent-child relationships
        val roots = mutableListOf<TreeNode>()
        for (t in topics) {
            val node = nodeMap[t.id] ?: continue
            val parentId = t.parentId
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
        roots.sortBy { it.topic.sortOrder }
        nodeMap.values.forEach { node ->
            node.children.sortBy { it.topic.sortOrder }
            node.recordings.sortBy { it.sortOrder }
        }

        return roots
    }

    /**
     * Flatten the tree into a RecyclerView-compatible list,
     * respecting each node's collapsed state from [collapsedIds].
     */
    fun flatten(
        roots: List<TreeNode>,
        collapsedIds: Set<Long> = emptySet()
    ): List<TreeItem> {
        val result = mutableListOf<TreeItem>()
        fun visit(node: TreeNode) {
            val isCollapsed = node.topic.id in collapsedIds
            result.add(
                TreeItem.Node(
                    treeNode = node,
                    depth = node.depth,
                    hasChildren = node.children.isNotEmpty() || node.recordings.isNotEmpty(),
                    isCollapsed = isCollapsed
                )
            )
            if (!isCollapsed) {
                node.children.forEach { visit(it) }
                node.recordings.forEach { rec ->
                    result.add(TreeItem.Leaf(recording = rec, depth = node.depth + 1))
                }
            }
        }
        roots.forEach { visit(it) }
        return result
    }
}