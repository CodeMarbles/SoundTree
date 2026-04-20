package app.soundtree.ui.common

import android.content.Context
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleOwner
import app.soundtree.data.entities.RecordingEntity
import app.soundtree.ui.MainViewModel
import app.soundtree.ui.moveRecording
import app.soundtree.ui.play
import app.soundtree.ui.togglePlayPause
import app.soundtree.ui.topics.RecordingsAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Centralises the recording-list wiring that is identical across
 * [AllRecordingsFragment], [UnsortedTileFragment], and [TopicDetailsFragment].
 *
 * ## Lifecycle
 *
 * Each fragment creates one instance in [onViewCreated], passing everything
 * except the adapter (which doesn't exist yet at that point).  After the
 * adapter is constructed the fragment calls [setup], which injects
 * SharedPreferences and registers the move-result listener.  Finally, inside
 * the fragment's own `repeatOnLifecycle(STARTED)` block, the fragment calls
 * [CoroutineScope.launchSharedObservers] alongside any fragment-specific
 * launches.
 *
 * ## What is centralised here
 *
 * - **Move flow** — [requestMove] shows [TopicPickerBottomSheet] and stores
 *   [pendingMoveRecordingId]; the result listener commits the move via the
 *   ViewModel and clears the pending ID.  The unique [moveRequestKey] and
 *   [movePickerTag] passed by each fragment prevent cross-fragment result
 *   delivery inside the shared ViewPager2.
 *
 * - **SharedPreferences injection** — `adapter.prefs` is set once in [setup].
 *
 * - **Five shared observer coroutines** launched by [launchSharedObservers]:
 *   1. `allTopics          → adapter.topics`
 *   2. `playheadVisEnabled + playheadVisIntensity → adapter.*`
 *   3. `nowPlaying         → adapter.updateNowPlayingProgress / nowPlayingId / isPlaying`
 *   4. `selectedRecordingId → adapter.selectedRecordingId`
 *   5. `orphanVolumeUuids  → adapter.orphanVolumeUuids`
 *   6. `nearEndEnabled → adapter.notifyDataSetChanged()`
 *
 * - **Standard onPlayPause callback** — [buildOnPlayPause] returns the lambda
 *   that is identical across all three fragments; callers supply a
 *   [navigateToListen] thunk so the controller never needs a Fragment reference.
 *
 * ## What stays in each fragment
 *
 * - RecyclerView binding (property names differ; TopicDetails needs
 *   `isNestedScrollingEnabled = false`)
 * - The data-source observer (`allRecordings`, `unsortedRecordings`, or the
 *   topicId-filtered flow)
 * - Any fragment-specific UI (sort button, empty-state label, stats header…)
 */
class RecordingListController(
    private val viewModel: MainViewModel,
    private val fragmentManager: FragmentManager,
    private val lifecycleOwner: LifecycleOwner,
    private val context: Context,
    private val moveRequestKey: String,
    private val movePickerTag: String,
) {
    private var pendingMoveRecordingId: Long = -1L

    // Lateinit so fragments can create the controller before they build the adapter.
    private lateinit var adapter: RecordingsAdapter

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Call once after the adapter is constructed.
     * Injects SharedPreferences and registers the move-result listener.
     */
    fun setup(adapter: RecordingsAdapter) {
        this.adapter = adapter
        adapter.prefs = context.getSharedPreferences("soundtree_settings", Context.MODE_PRIVATE)

        fragmentManager.setFragmentResultListener(moveRequestKey, lifecycleOwner) { _, bundle ->
            val topicId = TopicPickerBottomSheet.topicIdFromBundle(bundle)
            val recId   = pendingMoveRecordingId.takeIf { it != -1L } ?: return@setFragmentResultListener
            viewModel.moveRecording(recId, topicId)
            pendingMoveRecordingId = -1L
        }
    }

    // ── Move flow ─────────────────────────────────────────────────────────────

    /**
     * Wire this into the adapter's [onMoveRequested] callback.
     * Stores the in-flight recording ID and opens [TopicPickerBottomSheet].
     */
    fun requestMove(recordingId: Long, currentTopicId: Long?) {
        pendingMoveRecordingId = recordingId
        TopicPickerBottomSheet.newInstance(
            selectedTopicId = currentTopicId,
            requestKey      = moveRequestKey,
        ).show(fragmentManager, movePickerTag)
    }

    // ── onPlayPause helper ────────────────────────────────────────────────────

    /**
     * Returns the standard play/pause callback used by all three recording-list
     * fragments.  Pass a [navigateToListen] lambda so the controller never
     * needs a direct Fragment or Activity reference.
     *
     * Usage:
     * ```kotlin
     * onPlayPause = recordingListController.buildOnPlayPause {
     *     (requireActivity() as? MainActivity)?.navigateTo(MainActivity.PAGE_LISTEN)
     * }
     * ```
     */
    fun buildOnPlayPause(navigateToListen: () -> Unit): (RecordingEntity) -> Unit = { rec ->
        val nowPlaying = viewModel.nowPlaying.value
        if (nowPlaying?.recording?.id == rec.id) {
            viewModel.togglePlayPause()
        } else {
            viewModel.play(rec)
            if (viewModel.autoNavigateToListen.value) navigateToListen()
        }
    }

    // ── Shared observers ──────────────────────────────────────────────────────

    /**
     * Launches the five adapter-state observer coroutines that are identical
     * across all three recording-list fragments.
     *
     * **Must be called inside an active `repeatOnLifecycle(STARTED)` block**
     * so all child coroutines are automatically cancelled when the lifecycle
     * drops below STARTED and restarted when it returns.
     *
     * Call alongside fragment-specific launches in the same scope:
     * ```kotlin
     * viewLifecycleOwner.lifecycleScope.launch {
     *     viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
     *         with(recordingListController) { launchSharedObservers() }
     *         launch { viewModel.myFlow.collect { ... } } // fragment-specific
     *     }
     * }
     * ```
     */
    fun CoroutineScope.launchSharedObservers() {
        // 1. Topic list — drives emoji icons and topic-detail navigation
        launch {
            viewModel.allTopics.collect { adapter.topics = it }
        }

        // 2. Playhead visualisation settings — full rebind only when toggled
        launch {
            combine(
                viewModel.playheadVisEnabled,
                viewModel.playheadVisIntensity,
            ) { enabled, intensity -> enabled to intensity }
                .collect { (enabled, intensity) ->
                    adapter.playheadVisEnabled   = enabled
                    adapter.playheadVisIntensity = intensity
                }
        }

        // 3. Live playback position — partial bind (PAYLOAD_PROGRESS) on each
        //    tick, touching only the now-playing row's split background.
        launch {
            viewModel.nowPlaying.collect { state ->
                adapter.updateNowPlayingProgress(state?.positionMs ?: 0L)
                adapter.nowPlayingId = state?.recording?.id ?: -1L
                adapter.isPlaying    = state?.isPlaying ?: false
            }
        }

        // 4. Selection highlight
        launch {
            viewModel.selectedRecordingId.collect { id ->
                adapter.selectedRecordingId = id
            }
        }

        // 5. Orphan (storage-offline) rows
        launch {
            viewModel.orphanVolumeUuids.collect { uuids ->
                adapter.orphanVolumeUuids = uuids
            }
        }

        // 6. Near-end reset toggle — fractions computed by PlaybackPositionHelper
        //    read the pref directly, so a full rebind is all that's needed when
        //    the master switch changes.
        launch {
            viewModel.nearEndEnabled.collect { adapter.notifyDataSetChanged() }
        }
    }
}