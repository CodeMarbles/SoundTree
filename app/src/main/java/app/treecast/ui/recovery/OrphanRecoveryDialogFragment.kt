package app.treecast.ui.recovery

import android.content.DialogInterface
import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import app.treecast.ui.MainViewModel
import app.treecast.ui.common.TopicPickerBottomSheet
import app.treecast.ui.saveRecordingWithMarks
import app.treecast.storage.AppVolume
import app.treecast.util.OrphanRecording
import app.treecast.storage.StorageVolumeHelper
import app.treecast.worker.WaveformWorker
import app.treecast.R
import kotlinx.coroutines.launch
import java.io.File

/**
 * Full-screen dialog that lists orphaned recording files found on disk
 * (files with no matching database row) and lets the user recover or
 * delete each one.
 *
 * Shown automatically at startup by [app.treecast.ui.MainActivity]
 * when [app.treecast.util.OrphanRecordingScanner] finds orphans, and
 * also manually via the "Review orphaned recordings" button in Settings.
 *
 * Orphan recordings have no DB rows so they cannot use the app's
 * [app.treecast.service.PlaybackService] / ExoPlayer path. Only one
 * item plays at a time; the player is released when the dialog dismisses.
 *
 * The dialog dismisses itself automatically once every item has been
 * acted on.
 */
class OrphanRecoveryDialogFragment : DialogFragment() {

    // Use the full app theme so MaterialButton styles and custom colour
    // attrs resolve correctly.
    override fun getTheme(): Int = R.style.Theme_TreeCast_BottomSheet

    // ── Construction ──────────────────────────────────────────────────

    companion object {
        const val TAG = "orphan_recovery"

        private const val ARG_PLAYABLE_PATHS     = "playable_paths"
        private const val ARG_PLAYABLE_DURATIONS = "playable_durations_ms"
        private const val ARG_CORRUPT_PATHS      = "corrupt_paths"

        const val TOPIC_REQUEST_KEY = "OrphanRecoveryTopicPicker"

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

    private sealed class Item {
        data class Playable(
            val file: File,
            val suggestedTitle: String,
            val durationMs: Long,
            var editedTitle: String,
            var selectedTopicId: Long? = null,
        ) : Item()

        data class Corrupt(
            val file: File,
            val suggestedTitle: String,
        ) : Item()
    }

    private val items = mutableListOf<Item>()
    private lateinit var adapter: OrphanAdapter

    /** Position of the playable row currently waiting on a topic picker result. */
    private var pendingPickerIndex: Int = -1

    // ── Preview playback ───────────────────────────────────────────────

    /** Single MediaPlayer shared across all preview play actions. */
    private var mediaPlayer: MediaPlayer? = null

    /** Absolute path of the file currently loaded into [mediaPlayer], or null. */
    private var currentlyPlayingPath: String? = null

    // ── Dialog window setup ───────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            setWindowAnimations(R.style.Animation_TreeCast_SlideUpDown)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_orphan_recovery, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<ImageView>(R.id.btnClose).setOnClickListener {
            dismissAllowingStateLoss()
        }

        buildItemList()

        adapter = OrphanAdapter()
        view.findViewById<RecyclerView>(R.id.recyclerOrphans).apply {
            layoutManager            = LinearLayoutManager(requireContext())
            adapter                  = this@OrphanRecoveryDialogFragment.adapter
            isNestedScrollingEnabled = true
        }

        updateSubtitle(view)
        setupTopicPickerResult()
    }

    override fun onDismiss(dialog: DialogInterface) {
        stopPreview()
        viewModel.rescanOrphans()
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        stopPreview()
        super.onDestroyView()
    }

    // ── Item list construction ─────────────────────────────────────────

    private fun buildItemList() {
        val args = requireArguments()
        val playablePaths     = args.getStringArrayList(ARG_PLAYABLE_PATHS).orEmpty()
        val playableDurations = args.getLongArray(ARG_PLAYABLE_DURATIONS) ?: LongArray(0)
        val corruptPaths      = args.getStringArrayList(ARG_CORRUPT_PATHS).orEmpty()

        playablePaths.forEachIndexed { i, path ->
            val file  = File(path)
            val title = suggestedTitleFrom(file)
            items += Item.Playable(
                file           = file,
                suggestedTitle = title,
                durationMs     = playableDurations.getOrElse(i) { 0L },
                editedTitle    = title,
            )
        }
        corruptPaths.forEach { path ->
            val file = File(path)
            items += Item.Corrupt(file = file, suggestedTitle = suggestedTitleFrom(file))
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
            val topic = viewModel.allTopics.value.firstOrNull { it.id == topicId }
            val label = if (topic != null) "${topic.icon}  ${topic.name}" else "📥  Unsorted"
            adapter.notifyItemChanged(idx, label)
        }
    }

    // ── Actions ────────────────────────────────────────────────────────

    private fun onRecover(index: Int) {
        val item = items[index] as? Item.Playable ?: return
        if (currentlyPlayingPath == item.file.absolutePath) stopPreview()

        val title      = item.editedTitle.trim().ifEmpty { item.suggestedTitle }
        val volumeUuid = volumeUuidForFile(item.file)

        viewLifecycleOwner.lifecycleScope.launch {
            val recordingId = viewModel.saveRecordingWithMarks(
                filePath          = item.file.absolutePath,
                durationMs        = item.durationMs,
                fileSizeBytes     = item.file.length(),
                title             = title,
                topicId           = item.selectedTopicId,
                markTimestamps    = emptyList(),
                storageVolumeUuid = volumeUuid,
                createdAt         = recordedAtFromFile(item.file),
            ).await()

            WaveformWorker.enqueue(
                context           = requireContext(),
                recordingId       = recordingId,
                filePath          = item.file.absolutePath,
                storageVolumeUuid = volumeUuid,
            )
            removeItem(index)
        }
    }

    private fun onDelete(index: Int) {
        val file = when (val item = items[index]) {
            is Item.Playable -> item.file.also {
                if (currentlyPlayingPath == it.absolutePath) stopPreview()
            }
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

    // ── Preview playback ───────────────────────────────────────────────

    private fun togglePreview(path: String, onStopped: () -> Unit, onStarted: () -> Unit) {
        if (currentlyPlayingPath == path) {
            stopPreview()
            onStopped()
            return
        }
        stopPreview()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(path)
            prepare()
            start()
            setOnCompletionListener { stopPreview(); onStopped() }
        }
        currentlyPlayingPath = path
        onStarted()
    }

    private fun stopPreview() {
        mediaPlayer?.runCatching { stop(); release() }
        mediaPlayer = null
        currentlyPlayingPath = null
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
            ?.uuid ?: StorageVolumeHelper.UUID_PRIMARY

    /**
     * Parses the epoch-millis recording date from a TC_yyyyMMdd_HHmmss filename.
     * Falls back to the file's last-modified time if parsing fails, so we always
     * have a better answer than System.currentTimeMillis().
     */
    private fun recordedAtFromFile(file: File): Long =
        runCatching {
            val stamp = file.nameWithoutExtension.removePrefix("TC_")
            java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).parse(stamp)!!.time
        }.getOrElse { file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis() }

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
            return if (viewType == TYPE_PLAYABLE)
                PlayableVH(inflater.inflate(R.layout.item_orphan_playable, parent, false))
            else
                CorruptVH(inflater.inflate(R.layout.item_orphan_corrupt, parent, false))
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
                (payloads.first() as? String)?.let { label ->
                    holder.itemView.findViewById<MaterialButton>(
                        R.id.btnPickTopic
                    )?.text = label
                }
                return
            }
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    // ── ViewHolders ────────────────────────────────────────────────────

    private inner class PlayableVH(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(item: Item.Playable, index: Int) {
            val btnPlay    = itemView.findViewById<ImageView>(R.id.btnPlay)
            val tvDuration = itemView.findViewById<TextView>(R.id.tvDuration)
            val tvFileSize = itemView.findViewById<TextView>(R.id.tvFileSize)
            val etTitle    = itemView.findViewById<android.widget.EditText>(R.id.etTitle)
            val btnTopic   = itemView.findViewById<MaterialButton>(R.id.btnPickTopic)
            val btnDelete  = itemView.findViewById<MaterialButton>(R.id.btnDelete)
            val btnRecover = itemView.findViewById<MaterialButton>(R.id.btnRecover)

            tvDuration.text = formatDuration(item.durationMs)
            tvFileSize.text = AppVolume.formatBytes(item.file.length())
            etTitle.setText(item.editedTitle)
            etTitle.setOnFocusChangeListener { _, _ -> item.editedTitle = etTitle.text.toString() }

            val topicLabel = viewModel.allTopics.value
                .firstOrNull { it.id == item.selectedTopicId }
                ?.let { "${it.icon}  ${it.name}" } ?: "📥  Unsorted"
            btnTopic.text = topicLabel

            val isPlaying = currentlyPlayingPath == item.file.absolutePath
            btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

            btnPlay.setOnClickListener {
                togglePreview(
                    path      = item.file.absolutePath,
                    onStopped = { btnPlay.setImageResource(R.drawable.ic_play) },
                    onStarted = { btnPlay.setImageResource(R.drawable.ic_pause) },
                )
            }

            btnTopic.setOnClickListener {
                item.editedTitle = etTitle.text.toString()
                pendingPickerIndex = index
                TopicPickerBottomSheet.newInstance(
                    selectedTopicId = item.selectedTopicId,
                    requestKey      = TOPIC_REQUEST_KEY,
                ).show(childFragmentManager, "orphan_topic_picker")
            }

            btnDelete.setOnClickListener  { onDelete(index) }
            btnRecover.setOnClickListener { item.editedTitle = etTitle.text.toString(); onRecover(index) }
        }
    }

    private inner class CorruptVH(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(item: Item.Corrupt, index: Int) {
            itemView.findViewById<TextView>(R.id.tvFilename).text = item.suggestedTitle
            itemView.findViewById<MaterialButton>(
                R.id.btnDelete
            ).setOnClickListener { onDelete(index) }
        }
    }
}