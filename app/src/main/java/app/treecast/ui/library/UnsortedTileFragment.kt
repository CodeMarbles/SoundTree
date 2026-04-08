package app.treecast.ui.library

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
import app.treecast.databinding.FragmentInboxTileBinding
import app.treecast.ui.MainActivity
import app.treecast.ui.MainViewModel
import app.treecast.ui.common.TopicPickerBottomSheet
import app.treecast.ui.deleteRecording
import app.treecast.ui.moveRecording
import app.treecast.ui.play
import app.treecast.ui.refreshStorageVolumes
import app.treecast.ui.renameRecording
import app.treecast.ui.selectRecording
import app.treecast.ui.togglePlayPause
import app.treecast.ui.topics.RecordingsAdapter
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class UnsortedTileFragment : Fragment() {

    private var _binding: FragmentInboxTileBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: RecordingsAdapter

    /**
     * ID of the recording whose Move action is in flight.
     * Unique request key prevents cross-fragment result delivery in the ViewPager2.
     */
    private var pendingMoveRecordingId: Long = -1L

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

        setupMoveResultListener()
        setupAdapter()
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
        ).show(childFragmentManager, "recording_move_picker_inbox")
    }

    // ── Adapter setup ─────────────────────────────────────────────────────────

    private fun setupAdapter() {
        adapter = RecordingsAdapter(
            onPlayPause     = { rec ->
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
            onRename        = { id, title -> viewModel.renameRecording(id, title) },
            onMoveRequested = { recordingId, currentTopicId -> requestMove(recordingId, currentTopicId) },
            onDelete        = { rec -> viewModel.deleteRecording(rec) },
            onTopicDetailsRequested  = {}, // (hidden on this tab)
            onSelect        = { id -> viewModel.selectRecording(id) },
        )
        adapter.prefs = requireContext().getSharedPreferences("treecast_settings", Context.MODE_PRIVATE)

        binding.recyclerInbox.apply {
            this.adapter = this@UnsortedTileFragment.adapter
            layoutManager = LinearLayoutManager(requireContext())
        }
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
                        // Push the position — updateNowPlayingProgress handles the targeted notify.
                        adapter.updateNowPlayingProgress(state?.positionMs ?: 0L)
                        adapter.nowPlayingId = state?.recording?.id ?: -1L
                        adapter.isPlaying    = state?.isPlaying ?: false
                    }
                }
                launch {
                    viewModel.selectedRecordingId.collect { id ->
                        adapter.selectedRecordingId = id
                    }
                }
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
                        binding.tvCount.text = if (recs.isEmpty()) "" else "${recs.size} unorganised"
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

    override fun onResume() {
        super.onResume()
        viewModel.refreshStorageVolumes()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}