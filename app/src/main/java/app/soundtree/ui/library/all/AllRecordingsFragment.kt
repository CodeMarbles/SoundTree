package app.soundtree.ui.library.all

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
import app.soundtree.R
import app.soundtree.databinding.FragmentAllRecordingsBinding
import app.soundtree.data.entities.RecordingEntity
import app.soundtree.ui.MainActivity
import app.soundtree.ui.MainViewModel
import app.soundtree.ui.common.RecordingListController
import app.soundtree.ui.deleteRecording
import app.soundtree.ui.recording.RecordingDetailsDialogFragment
import app.soundtree.ui.refreshStorageVolumes
import app.soundtree.ui.renameRecording
import app.soundtree.ui.selectRecording
import app.soundtree.ui.topics.RecordingsAdapter
import kotlinx.coroutines.launch

/**
 * ALL tab — flat chronological list of every recording.
 *
 * Uses [RecordingsAdapter] with showTopicIcon = true so each row shows
 * the topic icon to the left of the recording info.
 *
 * Move flow: the adapter fires [onMoveRequested]; [RecordingListController]
 * stores the pending recording ID, shows [TopicPickerBottomSheet], and handles
 * the result via [MOVE_REQUEST_KEY]. The unique request key prevents cross-
 * fragment result delivery in the shared ViewPager2.
 */
class AllRecordingsFragment : Fragment() {

    private var _binding: FragmentAllRecordingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: RecordingsAdapter
    private lateinit var recordingListController: RecordingListController

    /** true = newest first (default), false = oldest first */
    private var newestFirst = true

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

        // 1. Create the controller first — adapter references it via requestMove.
        recordingListController = RecordingListController(
            viewModel       = viewModel,
            fragmentManager = childFragmentManager,
            lifecycleOwner  = viewLifecycleOwner,
            context         = requireContext(),
            moveRequestKey  = MOVE_REQUEST_KEY,
            movePickerTag   = "recording_move_picker_all",
        )

        // 2. Build the adapter.
        setupAdapter()

        // 3. Wire prefs + move-result listener into the controller.
        recordingListController.setup(adapter)

        setupSortButton()
        setupObservers()
    }

    // ── Adapter setup ─────────────────────────────────────────────────────────

    private fun setupAdapter() {
        adapter = RecordingsAdapter(
            showTopicIcon           = true,
            showTopicDetails        = true,
            onPlayPause             = recordingListController.buildOnPlayPause {
                (requireActivity() as? MainActivity)?.navigateTo(MainActivity.PAGE_LISTEN)
            },
            onRename                = { id, title -> viewModel.renameRecording(id, title) },
            onMoveRequested         = { recId, topicId -> recordingListController.requestMove(recId, topicId) },
            onDelete                = { rec -> viewModel.deleteRecording(rec) },
            onTopicDetailsRequested = { topicId ->
                val activity = requireActivity() as? MainActivity ?: return@RecordingsAdapter
                if (topicId == null) activity.navigateToLibraryUnsorted()
                else activity.navigateToTopicDetails(topicId)
            },
            onSelect                = { id ->
                viewModel.selectRecording(id)
                RecordingDetailsDialogFragment.newInstance(id)
                    .show(childFragmentManager, RecordingDetailsDialogFragment.TAG)
            },
        )

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
                // Shared: allTopics, playheadVis, nowPlaying, selectedRecordingId, orphanVolumeUuids
                with(recordingListController) { launchSharedObservers() }

                // Fragment-specific: data source
                launch {
                    viewModel.allRecordings.collect { recordings ->
                        submitSorted(recordings)
                    }
                }
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        viewModel.refreshStorageVolumes()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}