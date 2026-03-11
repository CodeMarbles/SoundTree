package com.treecast.app.ui.library.details

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.treecast.app.R
import com.treecast.app.data.entities.RecordingEntity
import com.treecast.app.data.entities.TopicEntity
import com.treecast.app.databinding.FragmentTopicDetailsBinding
import com.treecast.app.ui.MainActivity
import com.treecast.app.ui.MainViewModel
import com.treecast.app.ui.common.EmojiPickerBottomSheet
import com.treecast.app.ui.library.LibraryFragment
import com.treecast.app.ui.topics.RecordingsAdapter
import com.treecast.app.util.themeColor
import kotlinx.coroutines.launch

/**
 * DETAILS tab — shows details for a selected topic.
 *
 * Sections:
 *  1. Header: topic icon (tappable in edit mode) + name (editable in edit mode) + pencil/✓ button
 *  2. Hierarchy map: ancestor chain from root → this topic, each row clickable
 *  3. Recordings in this topic (direct only), with newest/oldest sort toggle
 *
 * This fragment observes [MainViewModel.libraryDetailsTopicId] to know which
 * topic to display. The DETAILS tab is greyed out until [openTopicDetails] is
 * called from [TopicsManageFragment].
 */
class TopicDetailsFragment : Fragment() {

    private var _binding: FragmentTopicDetailsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var recordingsAdapter: RecordingsAdapter

    private var isEditing = false
    private var currentTopic: TopicEntity? = null
    private var newestFirst = true

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTopicDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecordingsAdapter()
        setupSortButton()
        setupEditButton()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Re-render whenever the selected topic changes OR topics list changes
                launch {
                    viewModel.libraryDetailsTopicId.collect { topicId ->
                        val topics = viewModel.allTopics.value
                        renderForTopic(topicId, topics)
                    }
                }
                launch {
                    viewModel.allTopics.collect { topics ->
                        val topicId = viewModel.libraryDetailsTopicId.value
                        renderForTopic(topicId, topics)
                    }
                }
                launch {
                    viewModel.allRecordings.collect { recordings ->
                        val topicId = viewModel.libraryDetailsTopicId.value ?: return@collect
                        val filtered = recordings.filter { it.topicId == topicId }
                        submitSortedRecordings(filtered)
                    }
                }
                launch {
                    viewModel.nowPlaying.collect { state ->
                        recordingsAdapter.nowPlayingId = state?.recording?.id ?: -1L
                        recordingsAdapter.isPlaying    = state?.isPlaying ?: false
                    }
                }
                launch {
                    viewModel.orphanVolumeUuids.collect { uuids ->
                        recordingsAdapter.orphanVolumeUuids = uuids
                    }
                }
                launch {
                    viewModel.selectedRecordingId.collect { id ->
                        recordingsAdapter.selectedRecordingId = id
                    }
                }
            }
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────

    private fun renderForTopic(topicId: Long?, allTopics: List<TopicEntity>) {
        if (topicId == null) return
        val topic = allTopics.find { it.id == topicId } ?: return
        currentTopic = topic

        // Header
        binding.tvTopicIcon.text = topic.icon
        binding.tvTopicName.text = topic.name

        // If we were editing a different topic, exit edit mode
        if (isEditing) exitEditMode(save = false)

        // Hierarchy map
        buildHierarchyMap(topicId, allTopics)

        // Recordings will be re-submitted by the allRecordings collector
        val topicRecordings = viewModel.allRecordings.value.filter { it.topicId == topicId }
        submitSortedRecordings(topicRecordings)
    }

    private fun buildHierarchyMap(topicId: Long, allTopics: List<TopicEntity>) {
        // Build ancestor chain: walk parentId up to root
        val chain = mutableListOf<TopicEntity>()
        var cursor = allTopics.find { it.id == topicId }
        while (cursor != null) {
            chain.add(0, cursor) // prepend so root is first
            cursor = cursor.parentId?.let { pid -> allTopics.find { it.id == pid } }
        }

        binding.hierarchyContainer.removeAllViews()

        if (chain.size <= 1) {
            // Root-level topic — show a "Root" label
            addHierarchyRow(
                icon    = "🌳",
                name    = "Root",
                depth   = 0,
                topicId = null,
                isCurrent = false
            )
        }

        chain.forEachIndexed { index, topic ->
            val isCurrent = topic.id == topicId
            addHierarchyRow(
                icon      = topic.icon,
                name      = topic.name,
                depth     = index,
                topicId   = if (isCurrent) null else topic.id, // current row not clickable
                isCurrent = isCurrent
            )
        }
    }

    private fun addHierarchyRow(
        icon: String, name: String, depth: Int,
        topicId: Long?, isCurrent: Boolean
    ) {
        val density = resources.displayMetrics.density
        val indentPx = (depth * 20 * density).toInt()

        val row = layoutInflater.inflate(R.layout.item_hierarchy_row, binding.hierarchyContainer, false)

        val tvConnector = row.findViewById<android.widget.TextView>(R.id.tvConnector)
        val tvIcon      = row.findViewById<android.widget.TextView>(R.id.tvHierarchyIcon)
        val tvName      = row.findViewById<android.widget.TextView>(R.id.tvHierarchyName)

        row.setPaddingRelative(indentPx, 0, 0, 0)

        tvConnector.visibility = if (depth > 0) View.VISIBLE else View.GONE
        tvIcon.text = icon
        tvName.text = name

        if (isCurrent) {
            tvName.setTypeface(null, Typeface.BOLD)
            tvName.setTextColor(requireContext().themeColor(R.attr.colorAccent))
            row.isClickable = false
        } else {
            tvName.setTypeface(null, Typeface.NORMAL)
            tvName.setTextColor(requireContext().themeColor(R.attr.colorTextPrimary))
            if (topicId != null) {
                row.isClickable = true
                row.isFocusable = true
                row.setOnClickListener {
                    (requireParentFragment() as? LibraryFragment)?.navigateToTopicDetails(topicId)
                }
            }
        }

        binding.hierarchyContainer.addView(row)
    }

    // ── Edit mode ──────────────────────────────────────────────────────

    private fun setupEditButton() {
        binding.btnEditToggle.setOnClickListener {
            if (isEditing) {
                exitEditMode(save = true)
            } else {
                enterEditMode()
            }
        }

        // Icon tap in edit mode → emoji picker
        binding.tvTopicIcon.setOnClickListener {
            if (isEditing) {
                EmojiPickerBottomSheet { emoji ->
                    val topic = currentTopic ?: return@EmojiPickerBottomSheet
                    viewModel.updateTopic(topic.copy(icon = emoji))
                }.show(childFragmentManager, "emoji_picker_details")
            }
        }

        // Done action on keyboard
        binding.etTopicName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                exitEditMode(save = true)
                true
            } else false
        }
    }

    private fun enterEditMode() {
        isEditing = true
        val topic = currentTopic ?: return

        binding.tvTopicName.visibility = View.GONE
        binding.etTopicName.visibility = View.VISIBLE
        binding.etTopicName.setText(topic.name)
        binding.etTopicName.selectAll()
        binding.etTopicName.requestFocus()

        // Show keyboard
        context?.getSystemService<InputMethodManager>()
            ?.showSoftInput(binding.etTopicName, InputMethodManager.SHOW_IMPLICIT)

        // Switch to checkmark icon
        binding.btnEditToggle.setImageResource(R.drawable.ic_check)
        binding.btnEditToggle.setColorFilter(requireContext().themeColor(R.attr.colorAccent))

        // Add a pulsing outline to the icon to hint it's tappable
        binding.tvTopicIcon.setBackgroundResource(R.drawable.bg_icon_edit_hint)
    }

    private fun exitEditMode(save: Boolean) {
        isEditing = false

        if (save) {
            val newName = binding.etTopicName.text.toString().trim()
            val topic = currentTopic
            if (newName.isNotEmpty() && topic != null && newName != topic.name) {
                viewModel.updateTopic(topic.copy(name = newName))
            }
        }

        binding.tvTopicName.visibility = View.VISIBLE
        binding.etTopicName.visibility = View.GONE

        // Hide keyboard
        context?.getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(binding.etTopicName.windowToken, 0)

        // Restore pencil icon
        binding.btnEditToggle.setImageResource(R.drawable.ic_rename)
        binding.btnEditToggle.clearColorFilter()

        // Remove icon edit hint background
        binding.tvTopicIcon.setBackgroundResource(0)
    }

    // ── Recordings list ────────────────────────────────────────────────

    private fun setupRecordingsAdapter() {
        recordingsAdapter = RecordingsAdapter(
            onPlayPause = { rec ->
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
            onRename = { id, title -> viewModel.renameRecording(id, title) },
            onMove   = { id, topicId -> viewModel.moveRecording(id, topicId) },
            onDelete = { rec -> viewModel.deleteRecording(rec) },
            onSelect = { id -> viewModel.selectRecording(id) },
        )

        binding.recyclerTopicRecordings.apply {
            adapter = recordingsAdapter
            layoutManager = LinearLayoutManager(requireContext())
            isNestedScrollingEnabled = false
        }
    }

    private fun setupSortButton() {
        binding.btnSortRecordings.setOnClickListener {
            newestFirst = !newestFirst
            binding.btnSortRecordings.text = if (newestFirst) "↓ NEWEST FIRST" else "↑ OLDEST FIRST"
            val topicId = viewModel.libraryDetailsTopicId.value ?: return@setOnClickListener
            val recordings = viewModel.allRecordings.value.filter { it.topicId == topicId }
            submitSortedRecordings(recordings)
        }
    }

    private fun submitSortedRecordings(recordings: List<RecordingEntity>) {
        val sorted = if (newestFirst) {
            recordings.sortedByDescending { it.createdAt }
        } else {
            recordings.sortedBy { it.createdAt }
        }
        recordingsAdapter.submitList(sorted)
        recordingsAdapter.topics = viewModel.allTopics.value
        binding.tvNoRecordings.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}