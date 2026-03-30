package com.treecast.app.ui.library.details

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import androidx.core.view.ViewCompat
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
import com.treecast.app.ui.common.TopicPickerBottomSheet
import com.treecast.app.ui.library.LibraryFragment
import com.treecast.app.ui.topics.NewTopicDialog
import com.treecast.app.ui.topics.RecordingsAdapter
import com.treecast.app.util.AppVolume
import com.treecast.app.util.emojiToColor
import com.treecast.app.util.themeColor
import kotlinx.coroutines.launch

/**
 * DETAILS tab — shows details for a selected topic.
 *
 * Sections:
 *  1. Header: topic icon (always tappable → emoji picker) +
 *             topic name (always tappable → rename dialog) +
 *             stats column (recording count / total duration / total size)
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

    private var currentTopic: TopicEntity? = null
    private var newestFirst = true

    /** Local collapse state for the hierarchy widget — independent of the main tree. */
    private val hierarchyCollapsedIds = mutableSetOf<Long>()
    private var hierarchyInitializedForTopicId: Long? = null

    /**
     * ID of the topic whose Move action is in flight from a hierarchy row.
     * Uses a separate pending ID and request key from [REQUEST_REPARENT]
     * (btnMoveTopic) so the two pickers never collide.
     */
    private var pendingHierarchyReparentTopicId: Long = -1L

    private var previousTopicId: Long? = null

    /**
     * ID of the recording whose Move action is in flight.
     * Set when the user taps "Move to topic…" on a recording row; cleared
     * after the bottom sheet result is delivered.
     */
    private var pendingMoveRecordingId: Long = -1L

    companion object {
        private const val REQUEST_REPARENT           = "TopicDetails_reparent"
        private const val MOVE_RECORDING_REQUEST     = "RecordingMove_Details"
        private const val REQUEST_HIERARCHY_REPARENT = "TopicDetails_hierarchy_reparent"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTopicDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecordingMoveResultListener()
        setupRecordingsAdapter()
        setupSortButton()
        setupHeaderInteractions()           // replaces setupEditButton()
        setupMoveButton()
        setupHierarchyReparentResultListener()

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
                        updateTopicStats(filtered)
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

    // ── Header interactions ────────────────────────────────────────────────────

    /**
     * Wires the always-active header controls:
     *   • Icon tap  → emoji picker
     *   • Name tap  → rename dialog
     * Replaces the old edit-mode toggle approach.
     */
    private fun setupHeaderInteractions() {
        binding.tvTopicIcon.setOnClickListener {
            val topic = currentTopic ?: return@setOnClickListener
            EmojiPickerBottomSheet { emoji ->
                viewModel.updateTopic(topic.copy(icon = emoji, color = emojiToColor(emoji)))
            }.show(childFragmentManager, "emoji_picker_details")
        }

        binding.tvTopicName.setOnClickListener {
            showRenameTopicDialog()
        }
    }

    private fun showRenameTopicDialog() {
        val topic = currentTopic ?: return
        val editText = EditText(requireContext()).apply {
            setText(topic.name)
            selectAll()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(requireContext()).apply {
            setPadding(padding, 0, padding, 0)
            addView(editText)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.topic_dialog_rename_title)
            .setView(container)
            .setPositiveButton(R.string.common_btn_ok) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty() && newName != topic.name) {
                    viewModel.updateTopic(topic.copy(name = newName))
                }
            }
            .setNegativeButton(R.string.common_btn_cancel, null)
            .show()
    }

    // ── Rendering ─────────────────────────────────────────────────────

    private fun renderForTopic(topicId: Long?, allTopics: List<TopicEntity>) {
        if (topicId == null) return
        val topic = allTopics.find { it.id == topicId } ?: return

        // Reset hierarchy collapse state when the selected topic changes
        if (topic.id != currentTopic?.id) {
            previousTopicId = currentTopic?.id
            hierarchyCollapsedIds.clear()
            hierarchyInitializedForTopicId = null
        }

        currentTopic = topic

        // Header
        binding.tvTopicIcon.text = topic.icon
        binding.tvTopicName.text = topic.name

        // Hierarchy map
        buildHierarchyMap(topicId, allTopics)

        // Recordings will be re-submitted by the allRecordings collector
        val topicRecordings = viewModel.allRecordings.value.filter { it.topicId == topicId }
        submitSortedRecordings(topicRecordings)
        updateTopicStats(topicRecordings)
    }

    // ── Stats column ───────────────────────────────────────────────────

    private fun updateTopicStats(recordings: List<RecordingEntity>) {
        val count           = recordings.size
        val totalDurationMs = recordings.sumOf { it.durationMs }
        val totalSizeBytes  = recordings.sumOf { it.fileSizeBytes }

        binding.tvTopicRecordingCount.text =
            resources.getQuantityString(R.plurals.common_label_recording_count, count, count)
        binding.tvTopicTotalDuration.text = formatDuration(totalDurationMs)
        binding.tvTopicTotalSize.text     = AppVolume.formatBytes(totalSizeBytes)
    }

    // ── Move / reparent (btnMoveTopic) ─────────────────────────────────

    private fun setupMoveButton() {
        childFragmentManager.setFragmentResultListener(
            REQUEST_REPARENT, viewLifecycleOwner
        ) { _, bundle ->
            val newParentId = TopicPickerBottomSheet.topicIdFromBundle(bundle)
            val topicId = viewModel.libraryDetailsTopicId.value ?: return@setFragmentResultListener
            viewModel.reparentTopic(topicId, newParentId)
        }

        binding.btnMoveTopic.setOnClickListener {
            val topicId = viewModel.libraryDetailsTopicId.value ?: return@setOnClickListener
            val excluded = viewModel.getTopicWithDescendantIds(topicId)
            TopicPickerBottomSheet.newInstance(
                selectedTopicId = currentTopic?.parentId,
                requestKey      = REQUEST_REPARENT,
                excludedIds     = excluded,
                mode            = TopicPickerBottomSheet.Mode.REPARENT
            ).show(childFragmentManager, "move_topic_picker")
        }
    }

    private fun setupHierarchyReparentResultListener() {
        childFragmentManager.setFragmentResultListener(
            REQUEST_HIERARCHY_REPARENT, viewLifecycleOwner
        ) { _, bundle ->
            val newParentId = TopicPickerBottomSheet.topicIdFromBundle(bundle)
            val topicId = pendingHierarchyReparentTopicId.takeIf { it != -1L }
                ?: return@setFragmentResultListener
            viewModel.reparentTopic(topicId, newParentId)
            pendingHierarchyReparentTopicId = -1L
        }
    }

    private fun showHierarchyTopicOptionsMenu(topicId: Long, topicName: String, anchor: View) {
        val isEmpty = viewModel.allTopics.value.none { it.parentId == topicId } &&
                viewModel.allRecordings.value.none { it.topicId == topicId }

        PopupMenu(anchor.context, anchor).apply {
            menuInflater.inflate(R.menu.menu_topic_options, menu)
            menu.findItem(R.id.action_delete)?.isVisible = isEmpty
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_new_subtopic -> { showHierarchyNewSubtopicDialog(topicId); true }
                    R.id.action_move         -> { showHierarchyMovePicker(topicId); true }
                    R.id.action_rename       -> { showHierarchyRenameDialog(topicId, topicName); true }
                    R.id.action_icon         -> { showHierarchyIconPicker(topicId); true }
                    R.id.action_delete       -> { showHierarchyDeleteDialog(topicId, topicName); true }
                    else                     -> false
                }
            }
            show()
        }
    }

    private fun showHierarchyNewSubtopicDialog(parentId: Long) {
        NewTopicDialog(parentId = parentId) { name, icon, color ->
            viewModel.createTopic(name, parentId, icon, color)
        }.show(childFragmentManager, "hierarchy_new_subtopic")
    }

    private fun showHierarchyMovePicker(topicId: Long) {
        pendingHierarchyReparentTopicId = topicId
        val excluded = viewModel.getTopicWithDescendantIds(topicId)
        TopicPickerBottomSheet.newInstance(
            selectedTopicId = viewModel.allTopics.value.find { it.id == topicId }?.parentId,
            requestKey      = REQUEST_HIERARCHY_REPARENT,
            excludedIds     = excluded,
            mode            = TopicPickerBottomSheet.Mode.REPARENT
        ).show(childFragmentManager, "hierarchy_reparent_picker")
    }

    private fun showHierarchyRenameDialog(topicId: Long, currentName: String) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            setText(currentName)
            selectAll()
            setPadding(48, 24, 48, 8)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.topic_dialog_rename_title)
            .setView(input)
            .setPositiveButton(R.string.common_btn_ok) { _, _ ->
                val newName = input.text.toString().trim()
                val topic = viewModel.allTopics.value.find { it.id == topicId }
                    ?: return@setPositiveButton
                if (newName.isNotEmpty() && newName != topic.name) {
                    viewModel.updateTopic(topic.copy(name = newName))
                }
            }
            .setNegativeButton(R.string.common_btn_cancel, null)
            .show()
    }

    private fun showHierarchyIconPicker(topicId: Long) {
        EmojiPickerBottomSheet { emoji ->
            val topic = viewModel.allTopics.value.find { it.id == topicId } ?: return@EmojiPickerBottomSheet
            viewModel.updateTopic(topic.copy(icon = emoji, color = emojiToColor(emoji)))
        }.show(childFragmentManager, "hierarchy_icon_picker")
    }

    private fun showHierarchyDeleteDialog(topicId: Long, topicName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.topic_dialog_delete_title, topicName))
            .setMessage(R.string.topic_dialog_delete_message)
            .setPositiveButton(R.string.common_btn_delete) { _, _ ->
                val topic = viewModel.allTopics.value.find { it.id == topicId }
                    ?: return@setPositiveButton
                viewModel.deleteTopic(topic)

                // If the deleted topic is the one currently on display,
                // clear the selection and navigate back to the Topics tab.
                if (topicId == viewModel.libraryDetailsTopicId.value) {
                    viewModel.setLibraryDetailsTopic(null)
                    (requireParentFragment() as? LibraryFragment)?.navigateToTopics()
                }
            }
            .setNegativeButton(R.string.common_btn_cancel, null)
            .show()
    }

    // ── Recordings list ────────────────────────────────────────────────

    private fun setupRecordingMoveResultListener() {
        childFragmentManager.setFragmentResultListener(
            MOVE_RECORDING_REQUEST, viewLifecycleOwner
        ) { _, bundle ->
            val topicId = TopicPickerBottomSheet.topicIdFromBundle(bundle)
            val recId = pendingMoveRecordingId.takeIf { it != -1L } ?: return@setFragmentResultListener
            viewModel.moveRecording(recId, topicId)
            pendingMoveRecordingId = -1L
        }
    }

    private fun requestRecordingMove(recordingId: Long, currentTopicId: Long?) {
        pendingMoveRecordingId = recordingId
        TopicPickerBottomSheet.newInstance(
            selectedTopicId = currentTopicId,
            requestKey      = MOVE_RECORDING_REQUEST
        ).show(childFragmentManager, "recording_move_picker_details")
    }

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
            onMoveRequested = { recordingId, currentTopicId ->
                requestRecordingMove(recordingId, currentTopicId)
            },
            onDelete = { rec -> viewModel.deleteRecording(rec) },
            onTopicDetailsRequested = {},   // hidden on this tab
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
            binding.btnSortRecordings.text = getString(
                if (newestFirst) R.string.library_sort_newest_first
                else             R.string.library_sort_oldest_first
            )
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
        recordingsAdapter.topics = viewModel.allTopics.value
        recordingsAdapter.submitList(sorted) {
            val selectedId = viewModel.selectedRecordingId.value
            if (selectedId != -1L) {
                val pos = sorted.indexOfFirst { it.id == selectedId }
                if (pos != -1) {
                    binding.recyclerTopicRecordings.post {
                        val itemView = (binding.recyclerTopicRecordings.layoutManager as? LinearLayoutManager)
                            ?.findViewByPosition(pos)
                        if (itemView != null) {
                            val scrollY = binding.recyclerTopicRecordings.top + itemView.top
                            binding.nestedScrollView.smoothScrollTo(0, scrollY)
                        }
                    }
                }
            }
        }
        binding.tvNoRecordings.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
    }

    // ── Hierarchy map ──────────────────────────────────────────────────

    private fun buildHierarchyMap(topicId: Long, allTopics: List<TopicEntity>) {
        binding.hierarchyContainer.removeAllViews()

        // ── 1. Ancestor chain: walk parentId up to root ───────────────
        val ancestors = mutableListOf<TopicEntity>()
        var cursor = allTopics.find { it.id == topicId }?.parentId
            ?.let { pid -> allTopics.find { it.id == pid } }
        while (cursor != null) {
            ancestors.add(0, cursor)
            cursor = cursor.parentId?.let { pid -> allTopics.find { it.id == pid } }
        }

        ancestors.forEachIndexed { index, topic ->
            addHierarchyRow(
                icon = topic.icon, name = topic.name,
                depth = index, topicId = topic.id,
                isCurrent = false, hasChildren = false,
                onOptionsRequested = { anchor ->
                    showHierarchyTopicOptionsMenu(topic.id, topic.name, anchor)
                }
            )
        }

        // ── 2. Current topic row ──────────────────────────────────────
        val currentDepth = ancestors.size
        val currentNode = allTopics.find { it.id == topicId } ?: return
        val directChildren = allTopics.filter { it.parentId == topicId }

        addHierarchyRow(
            icon = currentNode.icon, name = currentNode.name,
            depth = currentDepth, topicId = topicId,
            isCurrent = true, hasChildren = directChildren.isNotEmpty(),
            isCollapsed = topicId in hierarchyCollapsedIds,
            onChevronClick = {
                if (topicId in hierarchyCollapsedIds) hierarchyCollapsedIds.remove(topicId)
                else hierarchyCollapsedIds.add(topicId)
                buildHierarchyMap(topicId, viewModel.allTopics.value)
            },
            onOptionsRequested = { anchor ->
                showHierarchyTopicOptionsMenu(topicId, currentNode.name, anchor)
            }
        )

        // ── 3. Descendant subtree — start collapsed ───────────────────
        if (hierarchyInitializedForTopicId != topicId && directChildren.isNotEmpty()) {
            hierarchyInitializedForTopicId = topicId

            // Default: immediate children visible, grandchildren collapsed
            directChildren.forEach { child ->
                if (allTopics.any { it.parentId == child.id })
                    hierarchyCollapsedIds.add(child.id)
            }

            // If we navigated here from a descendant, re-expand the path to it
            val prevId = previousTopicId
            if (prevId != null) {
                findPathDown(topicId, prevId, allTopics)
                    .dropLast(1)  // The leaf itself doesn't need to be un-collapsed
                    .forEach { hierarchyCollapsedIds.remove(it) }
            }
        }

        if (topicId !in hierarchyCollapsedIds) {
            addDescendantRows(topicId, currentDepth + 1, allTopics)
        }
    }

    private fun findPathDown(fromId: Long, toId: Long, allTopics: List<TopicEntity>): List<Long> {
        val path = mutableListOf<Long>()
        var cursor = allTopics.find { it.id == toId }
        while (cursor != null && cursor.id != fromId) {
            path.add(0, cursor.id)
            cursor = cursor.parentId?.let { pid -> allTopics.find { it.id == pid } }
        }
        return if (cursor?.id == fromId) path else emptyList()
    }

    /**
     * Recursively adds descendant topic rows to hierarchyContainer.
     * Each row that has children gets a chevron; collapsed state is tracked
     * in [hierarchyCollapsedIds] by the *parent* topic's ID.
     */
    private fun addDescendantRows(parentId: Long, depth: Int, allTopics: List<TopicEntity>) {
        val children = allTopics.filter { it.parentId == parentId }
        children.forEach { child ->
            val grandchildren = allTopics.filter { it.parentId == child.id }
            val isCollapsed = child.id in hierarchyCollapsedIds

            addHierarchyRow(
                icon = child.icon, name = child.name,
                depth = depth, topicId = child.id,
                isCurrent = false, hasChildren = grandchildren.isNotEmpty(),
                isCollapsed = isCollapsed,
                onChevronClick = {
                    if (isCollapsed) hierarchyCollapsedIds.remove(child.id)
                    else             hierarchyCollapsedIds.add(child.id)
                    val topicId = viewModel.libraryDetailsTopicId.value ?: return@addHierarchyRow
                    buildHierarchyMap(topicId, viewModel.allTopics.value)
                },
                onOptionsRequested = { anchor ->
                    showHierarchyTopicOptionsMenu(child.id, child.name, anchor)
                }
            )

            if (!isCollapsed) {
                addDescendantRows(child.id, depth + 1, allTopics)
            }
        }
    }

    private fun addHierarchyRow(
        icon: String,
        name: String,
        depth: Int,
        topicId: Long?,
        isCurrent: Boolean,
        hasChildren: Boolean,
        isCollapsed: Boolean = false,
        onChevronClick: (() -> Unit)? = null,
        onOptionsRequested: ((anchor: View) -> Unit)? = null
    ) {
        val density  = resources.displayMetrics.density
        val indentPx = (depth * 16 * density).toInt()

        val row = layoutInflater.inflate(
            R.layout.item_hierarchy_row, binding.hierarchyContainer, false)

        val tvConnector = row.findViewById<android.widget.TextView>(R.id.tvConnector)
        val tvIcon      = row.findViewById<android.widget.TextView>(R.id.tvHierarchyIcon)
        val tvName      = row.findViewById<android.widget.TextView>(R.id.tvHierarchyName)
        val ivOverflow  = row.findViewById<ImageView>(R.id.ivHierarchyOverflow)
        val ivChevron   = row.findViewById<ImageView>(R.id.ivHierarchyChevron)

        row.setPaddingRelative(indentPx, 0, 0, 0)

        tvConnector.visibility = if (depth > 0) View.VISIBLE else View.GONE
        tvIcon.text = icon
        tvName.text = name

        // ── Row style and navigation click ────────────────────────────────
        when {
            isCurrent -> {
                tvName.setTypeface(null, android.graphics.Typeface.BOLD)
                tvName.setTextColor(requireContext().themeColor(R.attr.colorAccent))
                row.isClickable = false
            }
            topicId != null -> {
                tvName.setTypeface(null, android.graphics.Typeface.NORMAL)
                tvName.setTextColor(requireContext().themeColor(R.attr.colorTextPrimary))
                row.isClickable = true
                row.isFocusable = true
                row.setOnClickListener {
                    (requireParentFragment() as? LibraryFragment)?.navigateToTopicDetails(topicId)
                }
            }
            else -> {
                tvName.setTypeface(null, android.graphics.Typeface.NORMAL)
                tvName.setTextColor(requireContext().themeColor(R.attr.colorTextSecondary))
                row.isClickable = false
            }
        }

        // ── Overflow ⋮ and long-press ─────────────────────────────────────
        if (onOptionsRequested != null) {
            ivOverflow.visibility = View.VISIBLE
            ivOverflow.contentDescription = getString(R.string.topic_cd_overflow, name)
            ivOverflow.setOnClickListener { onOptionsRequested(ivOverflow) }
            row.setOnLongClickListener {
                onOptionsRequested(ivOverflow)
                true
            }

            // Accessibility actions
            if (topicId != null) {
                val isEmpty = viewModel.allTopics.value.none { it.parentId == topicId } &&
                        viewModel.allRecordings.value.none { it.topicId == topicId }

                ViewCompat.addAccessibilityAction(row, getString(R.string.topic_action_new_subtopic)) { _, _ ->
                    showHierarchyNewSubtopicDialog(topicId); true
                }
                ViewCompat.addAccessibilityAction(row, getString(R.string.topic_cd_move)) { _, _ ->
                    showHierarchyMovePicker(topicId); true
                }
                ViewCompat.addAccessibilityAction(row, getString(R.string.topic_cd_rename)) { _, _ ->
                    showHierarchyRenameDialog(topicId, name); true
                }
                ViewCompat.addAccessibilityAction(row, getString(R.string.topic_cd_change_icon)) { _, _ ->
                    showHierarchyIconPicker(topicId); true
                }
                if (isEmpty) {
                    ViewCompat.addAccessibilityAction(row, getString(R.string.topic_cd_delete)) { _, _ ->
                        showHierarchyDeleteDialog(topicId, name); true
                    }
                }
            }
        } else {
            ivOverflow.visibility = View.GONE
        }

        // ── Chevron ───────────────────────────────────────────────────────
        if (hasChildren && onChevronClick != null) {
            ivChevron.visibility = View.VISIBLE
            ivChevron.rotation = if (isCollapsed) -90f else 0f
            ivChevron.contentDescription =
                if (isCollapsed) getString(R.string.topic_cd_expand, name)
                else             getString(R.string.topic_cd_collapse, name)
            ivChevron.setOnClickListener { onChevronClick() }
        } else {
            ivChevron.visibility = View.GONE
        }

        binding.hierarchyContainer.addView(row)
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
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