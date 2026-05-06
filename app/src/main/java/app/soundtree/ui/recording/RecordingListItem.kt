// app/src/main/java/app/soundtree/ui/topics/RecordingListItem.kt
package app.soundtree.ui.recording

import app.soundtree.data.entities.RecordingEntity

/**
 * Sealed list item type for [RecordingsAdapter].
 *
 * [Header] rows carry a formatted date label (e.g. "May 2026") and are
 * injected by the host fragment before calling [RecordingsAdapter.submitList].
 * [Recording] rows wrap the entity as before.
 *
 * Separating the type from [RecordingEntity] means the adapter can support
 * date-section headers without knowing anything about grouping logic — that
 * stays in the fragment.
 */
sealed class RecordingListItem {
    data class Header(val label: String) : RecordingListItem()
    data class Recording(val entity: RecordingEntity) : RecordingListItem()
}