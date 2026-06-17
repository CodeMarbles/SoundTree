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

// ── Pref keys ─────────────────────────────────────────────────────────────────

internal const val PREF_FREQUENT_TOPICS_ENABLED       = "frequent_topics_enabled"
internal const val PREF_FREQUENT_TOPICS_LIMIT         = "frequent_topics_limit"
internal const val PREF_FREQUENT_TOPICS_SHOW_LINEAGE  = "frequent_topics_show_lineage"
internal const val PREF_FREQUENT_TOPICS_SHOW_LABELS   = "frequent_topics_show_labels"

// ── Constants ─────────────────────────────────────────────────────────────────

/** Absolute ceiling on the number of frequent-topic entries the DB will return. */
const val FREQUENT_TOPICS_LIMIT_MAX = 10

/** Default number of entries shown in the Frequent Topics section. */
const val FREQUENT_TOPICS_LIMIT_DEFAULT = 5

/** Topics with a score at or below this threshold are excluded from the section. */
const val FREQUENT_TOPICS_MIN_SCORE = 0.1

// ── StateFlows (properties on MainViewModel) ──────────────────────────────────

val MainViewModel.frequentTopicsEnabled: StateFlow<Boolean>
    get() = _frequentTopicsEnabled

val MainViewModel.frequentTopicsLimit: StateFlow<Int>
    get() = _frequentTopicsLimit

val MainViewModel.frequentTopicsShowLineage: StateFlow<Boolean>
    get() = _frequentTopicsShowLineage

val MainViewModel.frequentTopicsShowLabels: StateFlow<Boolean>
    get() = _frequentTopicsShowLabels

// ── Public list ───────────────────────────────────────────────────────────────

/**
 * Ordered list of frequent topics for display in [TopicPickerBottomSheet].
 *
 * The limit used by the underlying DB query is [FREQUENT_TOPICS_LIMIT_MAX].
 * The UI trims the list at runtime to [frequentTopicsLimit] so that changing
 * the limit setting takes effect immediately without a new DB query.
 *
 * Emits an empty list when:
 *   - No topics have a score above [FREQUENT_TOPICS_MIN_SCORE].
 *   - The topics table hasn't loaded yet.
 */
val MainViewModel.frequentTopics: StateFlow<List<FrequentTopic>>
    get() = _frequentTopics

// ── Record a picker use ───────────────────────────────────────────────────────

/**
 * Records a topic selection from the picker for scoring purposes.
 *
 * Fire-and-forget — the picker dismisses immediately; the score update happens
 * in the background. Only call this for [TopicPickerBottomSheet.Mode.PICK].
 */
fun MainViewModel.recordTopicPickerUse(topicId: Long) {
    viewModelScope.launch {
        repo.recordTopicUse(topicId, allTopics.value)
    }
}

// ── Setters ───────────────────────────────────────────────────────────────────

fun MainViewModel.setFrequentTopicsEnabled(enabled: Boolean) {
    _frequentTopicsEnabled.value = enabled
    prefs.edit().putBoolean(PREF_FREQUENT_TOPICS_ENABLED, enabled).apply()
}

fun MainViewModel.setFrequentTopicsLimit(limit: Int) {
    val clamped = limit.coerceIn(1, FREQUENT_TOPICS_LIMIT_MAX)
    _frequentTopicsLimit.value = clamped
    prefs.edit().putInt(PREF_FREQUENT_TOPICS_LIMIT, clamped).apply()
}

fun MainViewModel.setFrequentTopicsShowLineage(show: Boolean) {
    _frequentTopicsShowLineage.value = show
    prefs.edit().putBoolean(PREF_FREQUENT_TOPICS_SHOW_LINEAGE, show).apply()
    // Labels are only meaningful when lineage is on; clear the child pref so
    // re-enabling lineage starts clean (the StateFlow already reflects this via
    // the disabled row in Settings, but belt-and-suspenders here).
    if (!show) setFrequentTopicsShowLabels(false)
}

fun MainViewModel.setFrequentTopicsShowLabels(show: Boolean) {
    _frequentTopicsShowLabels.value = show
    prefs.edit().putBoolean(PREF_FREQUENT_TOPICS_SHOW_LABELS, show).apply()
}