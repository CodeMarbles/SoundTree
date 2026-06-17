package app.soundtree.topics

import app.soundtree.data.entities.TopicEntity

/**
 * A single entry in the "Frequent Topics" section of the topic picker.
 *
 * @param topic       The topic itself.
 * @param lineage     Ordered ancestor chain, root first, not including [topic].
 *                    Empty for root-level topics.
 * @param hasChildren True if this topic has at least one child topic — controls
 *                    whether a chevron is shown for inline expansion.
 */
data class FrequentTopic(
    val topic:       TopicEntity,
    val lineage:     List<TopicEntity>,
    val hasChildren: Boolean,
)