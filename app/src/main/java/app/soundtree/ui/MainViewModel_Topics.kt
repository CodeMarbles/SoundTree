package app.soundtree.ui

// ─────────────────────────────────────────────────────────────────────────────
// MainViewModel_Topics.kt
//
// Extension functions on MainViewModel covering the topic tree
// ─────────────────────────────────────────────────────────────────────────────

import androidx.lifecycle.viewModelScope
import app.soundtree.data.entities.TopicEntity
import app.soundtree.ui.MainViewModel.Companion.PREF_COLLAPSED_TOPIC_IDS
import app.soundtree.util.Icons
import kotlinx.coroutines.launch

// ── Topic CRUD ────────────────────────────────────────────────────────────────

fun MainViewModel.createTopic(
    name: String,
    parentId: Long?,
    icon: String = Icons.DEFAULT_TOPIC,
    color: String = "#6C63FF",
) = viewModelScope.launch { repo.createTopic(name, parentId, icon, color) }

fun MainViewModel.updateTopic(t: TopicEntity) =
    viewModelScope.launch { repo.updateTopic(t) }

fun MainViewModel.deleteTopic(t: TopicEntity) =
    viewModelScope.launch { repo.deleteTopic(t) }

/**
 * Reparents [topicId] to [newParentId] (null = make it a root topic).
 * The topic's name, icon, color and other fields are preserved.
 */
fun MainViewModel.reparentTopic(topicId: Long, newParentId: Long?) {
    val topic = allTopics.value.find { it.id == topicId } ?: return
    viewModelScope.launch {
        repo.updateTopic(topic.copy(parentId = newParentId))
    }
}

/**
 * Returns the ID of [topicId] itself plus every descendant topic ID.
 * Used to build the exclusion set for the reparent topic picker, so the
 * topic being moved and all its children are hidden from the picker.
 */
fun MainViewModel.getTopicWithDescendantIds(topicId: Long): Set<Long> {
    val topics = allTopics.value
    val result = mutableSetOf<Long>()

    fun collect(id: Long) {
        result.add(id)
        topics.filter { it.parentId == id }.forEach { collect(it.id) }
    }

    collect(topicId)
    return result
}

// ── Collapse state ────────────────────────────────────────────────────────────

fun MainViewModel.toggleCollapse(topicId: Long, currentlyCollapsed: Boolean) {
    val updated = if (currentlyCollapsed)
        _collapsedIds.value - topicId
    else
        _collapsedIds.value + topicId
    _collapsedIds.value = updated
    prefs.edit()
        .putString(PREF_COLLAPSED_TOPIC_IDS, updated.joinToString(","))
        .apply()
}