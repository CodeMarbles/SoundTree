package app.soundtree.topics

import app.soundtree.data.entities.TopicEntity

/**
 * Computes topic score deltas when a topic is selected in the picker.
 *
 * The selected topic receives [DIRECT_WEIGHT]. Each ancestor up the tree
 * receives a progressively smaller vote via [PROPAGATION_FALLOFF], up to
 * [MAX_ANCESTOR_LEVELS] levels above the selected topic.
 *
 * Returns a map of topicId → score delta. The caller is responsible for
 * persisting these to the database. No Android dependencies — this class
 * is safe to unit-test without Robolectric.
 */
object TopicScoringManager {

    /** Score added to the directly selected topic. */
    const val DIRECT_WEIGHT: Double = 1.0

    /**
     * Multiplier applied at each ancestor level.
     * Level 1 (parent) = 1.0 * 0.25 = 0.25
     * Level 2 (grandparent) = 0.25 * 0.25 = 0.0625
     * Level 3 (great-grandparent) = 0.0625 * 0.25 ≈ 0.016
     */
    const val PROPAGATION_FALLOFF: Double = 0.25

    /** How many ancestor levels above the selected topic receive a vote. */
    const val MAX_ANCESTOR_LEVELS: Int = 3

    /**
     * Computes score deltas for [topicId] and its ancestors.
     *
     * @param topicId   The topic that was selected in the picker.
     * @param allTopics The full flat topic list (as held in the ViewModel).
     * @return A map of topicId → delta to add to that topic's score.
     *         Will contain between 1 and [MAX_ANCESTOR_LEVELS] + 1 entries.
     *         Returns an empty map if [topicId] is not found in [allTopics].
     */
    fun computeDeltas(topicId: Long, allTopics: List<TopicEntity>): Map<Long, Double> {
        val topicMap = allTopics.associateBy { it.id }
        val selected = topicMap[topicId] ?: return emptyMap()

        val deltas = mutableMapOf<Long, Double>()
        deltas[selected.id] = DIRECT_WEIGHT

        var current = selected
        var weight = DIRECT_WEIGHT
        repeat(MAX_ANCESTOR_LEVELS) {
            val parentId = current.parentId ?: return@repeat
            val parent = topicMap[parentId] ?: return@repeat
            weight *= PROPAGATION_FALLOFF
            deltas[parent.id] = (deltas[parent.id] ?: 0.0) + weight
            current = parent
        }

        return deltas
    }
}