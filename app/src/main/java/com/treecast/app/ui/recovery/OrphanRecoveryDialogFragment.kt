package com.treecast.app.ui.recovery

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.treecast.app.R
import com.treecast.app.ui.MainViewModel
import com.treecast.app.ui.common.TopicPickerBottomSheet
import com.treecast.app.util.AppVolume
import com.treecast.app.util.OrphanRecording
import com.treecast.app.util.StorageVolumeHelper
import com.treecast.app.worker.WaveformWorker
import kotlinx.coroutines.launch
import java.io.File

/**
 * Bottom sheet shown on first launch after a crash or interrupted save.
 *
 * Receives two parallel lists from [com.treecast.app.ui.MainActivity]:
 *   - Playable orphans:  fully-finalised M4A files with no DB row.
 *                        Offered with an editable title, a topic picker,
 *                        a Save button and a Delete button.
 *   - Corrupt orphans:   files whose MOOV atom is absent (MediaRecorder.stop()
 *                        was never called). Only a Delete button is offered.
 *                        See [com.treecast.app.util.OrphanRecordingScanner.probePlayable]
 *                        for the future repair insertion point.
 *
 * The sheet starts fully expanded when there are items to handle.
 * It dismisses itself automatically once every item has been acted on.
 *
 * Topic picker flow:
 * The sheet uses a single [TopicPickerBottomSheet] result listener keyed by
 * [TOPIC_REQUEST_KEY]. Before showing the picker, [pendingPickerIndex] is set
 * to the adapter position of the item that triggered it, so the result can be
 * routed back to the correct row.
 */
class OrphanRecoveryDialogFragment : BottomSheetDialogFragment() {

    // ── Construction ──────────────────────────────────────────────────

    companion object {
        private const val ARG_PLAYABLE_PATHS     = "playable_paths"
        private const val ARG_PLAYABLE_DURATIONS = "playable_durations_ms"
        private const val ARG_CORRUPT_PATHS      = "corrupt_paths"

        const val TOPIC_REQUEST_KEY = "OrphanRecoveryTopicPicker"

        /**
         * Build the fragment from the scan results emitted by
         * [com.treecast.app.util.OrphanRecordingScanner.scan].
         */
        fun newInstance(orphans: List<OrphanRecording>): OrphanRecoveryDialogFragment {
            val playable = orphans.filter { it.isPlayable }
            val corrupt  = orphans.filter { !it.isPlayable }
            return OrphanRecoveryDialogFragment().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_PLAYABLE_PATHS,
                        ArrayList(playable.map { it.file.absolutePath }))
                    putLongArray(ARG_PLAYABLE_DURATIONS,
                        playable.map { it.durationMs }.toLongArray())
                    putStringArrayList(ARG_CORRUPT_PATHS,
                        ArrayList(corrupt.map { it.file.absolutePath }))
                }
            }
        }
    }

    // ── State ─────────────────────────────────────────────────────────

    private val viewModel: MainViewModel by activityViewModels()

    /** Combined adapter items — sealed union of playable and corrupt rows. */
    private sealed class Item {
        /** A playable orphan: finalised M4A with no DB row. */
        data class Playable(
            val file: File,
            val suggestedTitle: String,
            val durationMs: Long,
            /** Mutable: updated when user types or picks a topic. */
            var editedTitle: String,
            var selectedTopicId: Long? = null,
        ) : Item()

        /** A corrupt orphan: MOOV atom absent, not directly recoverable. */
        data class Corrupt(
            val file: File,
            val suggestedTitle: String,
        ) : Item()
    }

    private val items = mutableListOf<Item>()
    private lateinit var adapter: OrphanAdapter

    /** Position of the playable row currently waiting on a topic picker result. */
    private var pendingPickerIndex: Int = -1

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_orphan_recovery, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Start fully expanded — there is work to do.
        (dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet))?.let {
            BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED
        }

        buildItemList()

        adapter = OrphanAdapter()
        view.findViewById<RecyclerView>(R.id.recyclerOrphans).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter       = this@OrphanRecoveryDialogFragment.adapter
            isNestedScrollingEnabled = true
        }

        updateSubtitle(view)
        setupTopicPickerResult()
    }

    // ── Item list construction ─────────────────────────────────────────

    private fun buildItemList() {
        val args = requireArguments()

        val playablePaths     = args.getStringArrayList(ARG_PLAYABLE_PATHS).orEmpty()
        val playableDurations = args.getLongArray(ARG_PLAYABLE_DURATIONS) ?: LongArray(0)
        val corruptPaths      = args.getStringArrayList(ARG_CORRUPT_PATHS).orEmpty()

        playablePaths.forEachIndexed { i, path ->
            val file     = File(path)
            val duration = playableDurations.getOrElse(i) { 0L }
            val title    = suggestedTitleFrom(file)
            items += Item.Playable(
                file           = file,
                suggestedTitle = title,
                durationMs     = duration,
                editedTitle    = title,
            )
        }

        corruptPaths.forEach { path ->
            items += Item.Corrupt(
                file           = File(path),
                suggestedTitle = suggestedTitleFrom(File(path)),
            )
        }
    }

    // ── Topic picker wiring ────────────────────────────────────────────

    private fun setupTopicPickerResult() {
        childFragmentManager.setFragmentResultListener(
            TOPIC_REQUEST_KEY, viewLifecycleOwner
        ) { _, bundle ->
            val topicId = TopicPickerBottomSheet.topicIdFromBundle(bundle)
            val idx = pendingPickerIndex.takeIf { it in items.indices } ?: return@setFragmentResultListener
            pendingPickerIndex = -1

            val item = items[idx] as? Item.Playable ?: return@setFragmentResultListener
            item.selectedTopicId = topicId

            // Update the topic button label to reflect the selection.
            val topic = viewModel.allTopics.value.firstOrNull { it.id == topicId }
            val label = if (topic != null) "${topic.icon}  ${topic.name}" else "📥  Unsorted"
            adapter.notifyItemChanged(idx, label)  // payload = new label string
        }
    }

    // ── Actions ────────────────────────────────────────────────────────

    private fun onRecover(index: Int) {
        val item = items[index] as? Item.Playable ?: return
        val title = item.editedTitle.trim().ifEmpty { item.suggestedTitle }

        val volumeUuid = volumeUuidForFile(item.file)

        viewLifecycleOwner.lifecycleScope.launch {
            val recordingId = viewModel.saveRecordingWithMarks(
                filePath          = item.file.absolutePath,
                durationMs        = item.durationMs,
                fileSizeBytes     = item.file.length(),
                title             = title,
                topicId           = item.selectedTopicId,
                markTimestamps    = emptyList(),   // marks are lost on crash
                storageVolumeUuid = volumeUuid,
            ).await()

            WaveformWorker.enqueue(
                context     = requireContext(),
                recordingId = recordingId,
                filePath    = item.file.absolutePath,
            )

            removeItem(index)
        }
    }

    private fun onDelete(index: Int) {
        val file = when (val item = items[index]) {
            is Item.Playable -> item.file
            is Item.Corrupt  -> item.file
        }
        file.delete()
        removeItem(index)
    }

    private fun removeItem(index: Int) {
        if (index !in items.indices) return
        items.removeAt(index)
        adapter.notifyItemRemoved(index)
        adapter.notifyItemRangeChanged(index, items.size - index)
        view?.let { updateSubtitle(it) }
        if (items.isEmpty()) dismissAllowingStateLoss()
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun updateSubtitle(root: View) {
        val playableCount = items.count { it is Item.Playable }
        val corruptCount  = items.count { it is Item.Corrupt }
        val parts = buildList {
            if (playableCount > 0) add(
                resources.getQuantityString(
                    R.plurals.orphan_subtitle_recoverable, playableCount, playableCount
                )
            )
            if (corruptCount > 0) add(
                resources.getQuantityString(
                    R.plurals.orphan_subtitle_corrupt, corruptCount, corruptCount
                )
            )
        }
        root.findViewById<TextView>(R.id.tvSubtitle).text = parts.joinToString(" · ")
    }

    private fun suggestedTitleFrom(file: File): String {
        val stamp = file.nameWithoutExtension.removePrefix("TC_")
        return runCatching {
            val date = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).parse(stamp)!!
            "Recording – " + java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(date)
        }.getOrElse { file.nameWithoutExtension }
    }

    private fun volumeUuidForFile(file: File): String =
        StorageVolumeHelper.getVolumes(requireContext())
            .firstOrNull { runCatching { file.canonicalPath.startsWith(it.rootDir.canonicalPath) }.getOrDefault(false) }
            ?.uuid
            ?: StorageVolumeHelper.UUID_PRIMARY

    private fun formatDuration(ms: Long): String {
        val totalSecs = ms / 1000
        val hours     = totalSecs / 3600
        val minutes   = (totalSecs % 3600) / 60
        val secs      = totalSecs % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
        else "%d:%02d".format(minutes, secs)
    }

    // ── Adapter ────────────────────────────────────────────────────────

    private inner class OrphanAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_PLAYABLE = 0
        private val TYPE_CORRUPT  = 1

        override fun getItemCount(): Int = items.size

        override fun getItemViewType(position: Int): Int =
            if (items[position] is Item.Playable) TYPE_PLAYABLE else TYPE_CORRUPT

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_PLAYABLE) {
                PlayableVH(inflater.inflate(R.layout.item_orphan_playable, parent, false))
            } else {
                CorruptVH(inflater.inflate(R.layout.item_orphan_corrupt, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Playable -> (holder as PlayableVH).bind(item, position)
                is Item.Corrupt  -> (holder as CorruptVH).bind(item, position)
            }
        }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
            payloads: List<Any>,
        ) {
            if (payloads.isNotEmpty() && holder is PlayableVH) {
                // Partial rebind: just update the topic button label.
                (payloads.first() as? String)?.let { label ->
                    holder.btnPickTopic.text = label
                }
                return
            }
            onBindViewHolder(holder, position)
        }

        // ── ViewHolders ───────────────────────────────────────────────

        inner class PlayableVH(v: View) : RecyclerView.ViewHolder(v) {
            val etTitle:      EditText      = v.findViewById(R.id.etTitle)
            val tvDuration:   TextView      = v.findViewById(R.id.tvDuration)
            val tvFileSize:   TextView      = v.findViewById(R.id.tvFileSize)
            val btnPickTopic: MaterialButton = v.findViewById(R.id.btnPickTopic)
            val btnDelete:    MaterialButton = v.findViewById(R.id.btnDelete)
            val btnRecover:   MaterialButton = v.findViewById(R.id.btnRecover)

            fun bind(item: Item.Playable, position: Int) {
                tvDuration.text = formatDuration(item.durationMs)
                tvFileSize.text = AppVolume.formatBytes(item.file.length())

                etTitle.setText(item.editedTitle)
                etTitle.setOnFocusChangeListener { _, _ ->
                    item.editedTitle = etTitle.text.toString()
                }

                val topic = viewModel.allTopics.value.firstOrNull { it.id == item.selectedTopicId }
                btnPickTopic.text = if (topic != null) "${topic.icon}  ${topic.name}" else "📥  Unsorted"

                btnPickTopic.setOnClickListener {
                    item.editedTitle    = etTitle.text.toString()
                    pendingPickerIndex  = position
                    TopicPickerBottomSheet.newInstance(
                        selectedTopicId = item.selectedTopicId,
                        requestKey      = TOPIC_REQUEST_KEY,
                    ).show(childFragmentManager, "orphan_topic_picker")
                }

                btnRecover.setOnClickListener {
                    item.editedTitle = etTitle.text.toString()
                    onRecover(bindingAdapterPosition)
                }

                btnDelete.setOnClickListener {
                    onDelete(bindingAdapterPosition)
                }
            }
        }

        inner class CorruptVH(v: View) : RecyclerView.ViewHolder(v) {
            val tvFilename: TextView      = v.findViewById(R.id.tvFilename)
            val btnDelete:  MaterialButton = v.findViewById(R.id.btnDelete)

            fun bind(item: Item.Corrupt, position: Int) {
                tvFilename.text = item.suggestedTitle

                btnDelete.setOnClickListener {
                    onDelete(bindingAdapterPosition)
                }
            }
        }
    }
}