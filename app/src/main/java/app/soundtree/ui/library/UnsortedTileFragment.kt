package app.soundtree.ui.library

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
import app.soundtree.databinding.FragmentInboxTileBinding
import app.soundtree.ui.MainActivity
import app.soundtree.ui.MainViewModel
import app.soundtree.ui.common.RecordingListController
import app.soundtree.ui.deleteRecording
import app.soundtree.ui.refreshStorageVolumes
import app.soundtree.ui.renameRecording
import app.soundtree.ui.selectRecording
import app.soundtree.ui.topics.RecordingsAdapter
import kotlinx.coroutines.launch

class UnsortedTileFragment : Fragment() {

    private var _binding: FragmentInboxTileBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: RecordingsAdapter
    private lateinit var recordingListController: RecordingListController

    companion object {
        private const val MOVE_REQUEST_KEY = "RecordingMove_Inbox"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInboxTileBinding.inflate(inflater, container, false)
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
            movePickerTag   = "recording_move_picker_inbox",
        )

        // 2. Build the adapter.
        setupAdapter()

        // 3. Wire prefs + move-result listener into the controller.
        recordingListController.setup(adapter)

        setupObservers()
    }

    // ── Adapter setup ─────────────────────────────────────────────────────────

    private fun setupAdapter() {
        adapter = RecordingsAdapter(
            onPlayPause             = recordingListController.buildOnPlayPause {
                (requireActivity() as? MainActivity)?.navigateTo(MainActivity.PAGE_LISTEN)
            },
            onRename                = { id, title -> viewModel.renameRecording(id, title) },
            onMoveRequested         = { recId, topicId -> recordingListController.requestMove(recId, topicId) },
            onDelete                = { rec -> viewModel.deleteRecording(rec) },
            onTopicDetailsRequested = {}, // hidden on this tab
            onSelect                = { id -> viewModel.selectRecording(id) },
        )

        binding.recyclerInbox.apply {
            this.adapter = this@UnsortedTileFragment.adapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Shared: allTopics, playheadVis, nowPlaying, selectedRecordingId, orphanVolumeUuids
                with(recordingListController) { launchSharedObservers() }

                // Fragment-specific: data source + empty-state UI
                launch {
                    viewModel.unsortedRecordings.collect { recs ->
                        adapter.submitList(recs) {
                            val selectedId = viewModel.selectedRecordingId.value
                            if (selectedId != -1L) {
                                val pos = recs.indexOfFirst { it.id == selectedId }
                                if (pos != -1) binding.recyclerInbox.scrollToPosition(pos)
                            }
                        }
                        binding.tvEmpty.visibility = if (recs.isEmpty()) View.VISIBLE else View.GONE
                        binding.tvCount.text       = if (recs.isEmpty()) "" else "${recs.size} unorganised"
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