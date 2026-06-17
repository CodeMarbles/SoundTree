package app.soundtree.ui

// ─────────────────────────────────────────────────────────────────────────────
// MainViewModel_FrequentTopics.kt
//
// Extension functions and StateFlow wiring for the "Frequent Topics" section
// of the topic picker.
// ─────────────────────────────────────────────────────────────────────────────

import androidx.lifecycle.viewModelScope
import app.soundtree.topics.FrequentTopic
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ── Constants ─────────────────────────────────────────────────────────────────

/** Maximum number of entries shown in the Frequent Topics section. */
const val FREQUENT_TOPICS_LIMIT = 5

/** Topics with a score at or below this threshold are excluded from the section. */
const val FREQUENT_TOPICS_MIN_SCORE = 0.1

// ── StateFlow ─────────────────────────────────────────────────────────────────

/**
 * Ordered list of frequent topics for display in [TopicPickerBottomSheet].
 *
 * Derived by combining the live top-scoring topic query with [allTopics]
 * (already in memory) to resolve each topic's full ancestor lineage without
 * any additional DB queries.
 *
 * Emits an empty list when:
 *   - No topics have a score above [FREQUENT_TOPICS_MIN_SCORE] (cold start,
 *     long absence, or all scores decayed to zero).
 *   - The topics table hasn't loaded yet.
 *
 * Because this is a `val` computed at init time it must be initialised inside
 * [MainViewModel]'s init block or as a property. We expose it as a top-level
 * extension property that is lazily initialised the first time it's accessed.
 * In practice the BottomSheet and any future consumers all share the same
 * StateFlow instance via the shared [MainViewModel].
 */
val MainViewModel.frequentTopics: StateFlow<List<FrequentTopic>>
    get() = _frequentTopics

// ── Record a picker use ───────────────────────────────────────────────────────

/**
 * Records a topic selection from the picker for scoring purposes.
 *
 * Computes score deltas for [topicId] and its ancestors and persists them
 * asynchronously. Fire-and-forget — the picker dismisses immediately; the
 * score update happens in the background without blocking the UI.
 *
 * Only call this for [TopicPickerBottomSheet.Mode.PICK] selections.
 */
fun MainViewModel.recordTopicPickerUse(topicId: Long) {
    viewModelScope.launch {
        repo.recordTopicUse(topicId, allTopics.value)
    }
}