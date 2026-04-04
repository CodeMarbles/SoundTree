package app.treecast.data.repository

import app.treecast.data.entities.RecordingEntity
import app.treecast.data.entities.TopicEntity

// ─────────────────────────────────────────────────────────
// Domain models used by the UI layer
// ─────────────────────────────────────────────────────────

/**
 * A node in the in-memory topic tree.
 *
 * All collections and fields are immutable — this object is a read-only
 * snapshot emitted by [TreeBuilder] into the ViewModel's StateFlow.
 * Never mutate it directly; a new snapshot will arrive on the next DB emission.
 */
data class TreeNode(
    val topic: TopicEntity,
    val children: List<TreeNode> = emptyList(),
    val recordings: List<RecordingEntity> = emptyList(),
    val depth: Int = 0
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
     * Recordings without a topicId are ignored here (they live in the Inbox).
     *
     * Construction uses private [MutableNode] intermediaries so the final
     * [TreeNode] objects handed to the UI are fully immutable.
     */
    fun build(
        topics: List<TopicEntity>,
        recordings: List<RecordingEntity>
    ): List<TreeNode> {
        // ── 1. Build mutable intermediaries ──────────────────────────
        val nodeMap = topics.associate { it.id to MutableNode(topic = it) }

        for (rec in recordings) {
            val topicId = rec.topicId ?: continue
            nodeMap[topicId]?.recordings?.add(rec)
        }

        val roots = mutableListOf<MutableNode>()
        for (t in topics) {
            val node = nodeMap[t.id] ?: continue
            val parentId = t.parentId
            if (parentId == null) {
                roots.add(node)
            } else {
                val parent = nodeMap[parentId]
                if (parent != null) {
                    parent.children.add(node)
                } else {
                    // Orphan → promote to root
                    roots.add(node)
                }
            }
        }

        // ── 2. Assign depths top-down now that the tree is fully wired ─
        // This must happen after all parent-child relationships are set,
        // so that depth is always computed from the actual tree structure
        // regardless of the order topics arrived from the DB.
        fun assignDepths(nodes: List<MutableNode>, depth: Int) {
            for (node in nodes) {
                node.depth = depth
                assignDepths(node.children, depth + 1)
            }
        }
        assignDepths(roots, 0)

        // ── 3. Sort each level ────────────────────────────────────────
        roots.sortBy { it.topic.sortOrder }
        nodeMap.values.forEach { node ->
            node.children.sortBy { it.topic.sortOrder }
            node.recordings.sortBy { it.sortOrder }
        }

        // ── 4. Freeze into immutable TreeNodes ────────────────────────
        return roots.map { it.freeze() }
    }

    /**
     * Flatten the tree into a RecyclerView-compatible list,
     * respecting each node's collapsed state from [collapsedIds].
     *
     * Depth is passed as a traversal parameter rather than read from
     * [TreeNode.depth], so display depth is always correct even if the
     * stored depth were somehow stale.
     */
    fun flatten(
        roots: List<TreeNode>,
        collapsedIds: Set<Long> = emptySet()
    ): List<TreeItem> {
        val result = mutableListOf<TreeItem>()
        fun visit(node: TreeNode, depth: Int) {
            val isCollapsed = node.topic.id in collapsedIds
            result.add(
                TreeItem.Node(
                    treeNode    = node,
                    depth       = depth,
                    hasChildren = node.children.isNotEmpty() || node.recordings.isNotEmpty(),
                    isCollapsed = isCollapsed
                )
            )
            if (!isCollapsed) {
                node.children.forEach { visit(it, depth + 1) }
                node.recordings.forEach { rec ->
                    result.add(TreeItem.Leaf(recording = rec, depth = depth + 1))
                }
            }
        }
        roots.forEach { visit(it, 0) }
        return result
    }

    // ─────────────────────────────────────────────────────
    // Private mutable intermediary — never escapes this object
    // ─────────────────────────────────────────────────────

    private class MutableNode(val topic: TopicEntity) {
        val children: MutableList<MutableNode> = mutableListOf()
        val recordings: MutableList<RecordingEntity> = mutableListOf()
        var depth: Int = 0

        fun freeze(): TreeNode = TreeNode(
            topic      = topic,
            children   = children.map { it.freeze() },
            recordings = recordings.toList(),
            depth      = depth
        )
    }
}