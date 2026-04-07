package app.treecast.ui.library.all

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import app.treecast.R
import app.treecast.databinding.FragmentAllRecordingsBinding
import app.treecast.data.entities.RecordingEntity
import app.treecast.ui.MainActivity
import app.treecast.ui.MainViewModel
import app.treecast.ui.common.TopicPickerBottomSheet
import app.treecast.ui.deleteRecording
import app.treecast.ui.moveRecording
import app.treecast.ui.play
import app.treecast.ui.recording.RecordingDetailsDialogFragment
import app.treecast.ui.refreshStorageVolumes
import app.treecast.ui.renameRecording
import app.treecast.ui.selectRecording
import app.treecast.ui.togglePlayPause
import app.treecast.ui.topics.RecordingsAdapter
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * ALL tab — flat chronological list of every recording.
 *
 * Uses [RecordingsAdapter] with showTopicIcon = true so each row shows
 * the topic icon to the left of the recording info.
 *
 * Move flow: the adapter fires [onMoveRequested]; this fragment stores the
 * pending recording ID, shows [TopicPickerBottomSheet], and handles the
 * result via [MOVE_REQUEST_KEY]. The unique request key prevents cross-
 * fragment result delivery in the shared ViewPager2.
 */
class AllRecordingsFragment : Fragment() {

    private var _binding: FragmentAllRecordingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: RecordingsAdapter

    /** true = newest first (default), false = oldest first */
    private var newestFirst = true

    /**
     * ID of the recording whose Move action is in flight.
     * Set when the user taps "Move to topic…"; cleared after the result
     * is delivered (or implicitly replaced by a subsequent move request).
     */
    private var pendingMoveRecordingId: Long = -1L

    companion object {
        private const val MOVE_REQUEST_KEY = "RecordingMove_All"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAllRecordingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupMoveResultListener()
        setupAdapter()
        setupSortButton()
        setupObservers()
    }

    // ── Move flow ─────────────────────────────────────────────────────────────

    private fun setupMoveResultListener() {
        childFragmentManager.setFragmentResultListener(
            MOVE_REQUEST_KEY, viewLifecycleOwner
        ) { _, bundle ->
            val topicId = TopicPickerBottomSheet.topicIdFromBundle(bundle)
            val recId = pendingMoveRecordingId.takeIf { it != -1L } ?: return@setFragmentResultListener
            viewModel.moveRecording(recId, topicId)
            pendingMoveRecordingId = -1L
        }
    }

    private fun requestMove(recordingId: Long, currentTopicId: Long?) {
        pendingMoveRecordingId = recordingId
        TopicPickerBottomSheet.newInstance(
            selectedTopicId = currentTopicId,
            requestKey      = MOVE_REQUEST_KEY
        ).show(childFragmentManager, "recording_move_picker_all")
    }

    // ── Adapter setup ─────────────────────────────────────────────────────────

    private fun setupAdapter() {
        adapter = RecordingsAdapter(
            showTopicIcon            = true,
            showTopicDetails         = true,
            onPlayPause              = { rec ->
                val nowPlaying = viewModel.nowPlaying.value
                if (nowPlaying?.recording?.id == rec.id) {
                    viewModel.togglePlayPause()
                } else {
                    viewModel.play(rec)
                    if (viewModel.autoNavigateToListen.value) {
                        (requireActivity() as? MainActivity)?.navigateTo(MainActivity.PAGE_LISTEN)
                    }
                }
            },
            onRename                 = { id, title -> viewModel.renameRecording(id, title) },
            onMoveRequested          = { recordingId, currentTopicId -> requestMove(recordingId, currentTopicId) },
            onDelete                 = { rec -> viewModel.deleteRecording(rec) },
            onTopicDetailsRequested  = { topicId ->                                 // ← new
                val activity = requireActivity() as? MainActivity ?: return@RecordingsAdapter
                if (topicId == null) activity.navigateToLibraryUnsorted()
                else activity.navigateToTopicDetails(topicId)
            },
            onSelect = { id ->
                viewModel.selectRecording(id)
                RecordingDetailsDialogFragment.newInstance(id)
                    .show(childFragmentManager, RecordingDetailsDialogFragment.TAG)
            },
        )
        adapter.prefs = requireContext().getSharedPreferences("treecast_settings", Context.MODE_PRIVATE)

        binding.recyclerAllRecordings.apply {
            this.adapter = this@AllRecordingsFragment.adapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    // ── Sort button ───────────────────────────────────────────────────────────

    private fun setupSortButton() {
        binding.btnSortOrder.setOnClickListener {
            newestFirst = !newestFirst
            updateSortButtonLabel()
            submitSorted(viewModel.allRecordings.value)
        }
    }

    private fun updateSortButtonLabel() {
        binding.btnSortOrder.text = getString(
            if (newestFirst) R.string.library_sort_newest_first
            else             R.string.library_sort_oldest_first
        )
    }

    private fun submitSorted(recordings: List<RecordingEntity>) {
        val sorted = if (newestFirst) recordings.sortedByDescending { it.createdAt }
        else             recordings.sortedBy { it.createdAt }
        adapter.submitList(sorted)
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.allTopics.collect { topics ->
                        adapter.topics = topics
                    }
                }
                launch {
                    viewModel.allRecordings.collect { recordings ->
                        submitSorted(recordings)
                    }
                }
                // Playhead visualisation settings — full rebind only when toggled
                launch {
                    combine(
                        viewModel.playheadVisEnabled,
                        viewModel.playheadVisIntensity
                    ) { enabled, intensity -> Pair(enabled, intensity) }
                        .collect { (enabled, intensity) ->
                            adapter.playheadVisEnabled   = enabled
                            adapter.playheadVisIntensity = intensity
                        }
                }
                // Live playback position — partial bind (PAYLOAD_PROGRESS) on each tick,
                // touching only the now-playing row's split background.
                launch {
                    viewModel.nowPlaying.collect { state ->
                        adapter.nowPlayingId = state?.recording?.id ?: -1L
                        adapter.isPlaying    = state?.isPlaying ?: false
                        // Push the position — updateNowPlayingProgress handles the targeted notify.
                        adapter.updateNowPlayingProgress(state?.positionMs ?: 0L)
                    }
                }
//                launch {
//                    viewModel.nowPlaying.collect { state ->
//                        adapter.nowPlayingId = state?.recording?.id ?: -1L
//                        adapter.isPlaying    = state?.isPlaying ?: false
//                    }
//                }
                launch {
                    viewModel.selectedRecordingId.collect { id ->
                        adapter.selectedRecordingId = id
                    }
                }
                launch {
                    viewModel.orphanVolumeUuids.collect { uuids ->
                        adapter.orphanVolumeUuids = uuids
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStorageVolumes()
    }
}