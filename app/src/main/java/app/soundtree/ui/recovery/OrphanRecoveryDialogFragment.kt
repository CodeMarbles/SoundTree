package app.soundtree.ui.recovery

import android.content.DialogInterface
import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import app.soundtree.ui.MainViewModel
import app.soundtree.ui.common.TopicPickerBottomSheet
import app.soundtree.ui.saveRecordingWithMarks
import app.soundtree.storage.AppVolume
import app.soundtree.util.OrphanRecording
import app.soundtree.storage.StorageVolumeHelper
import app.soundtree.worker.WaveformWorker
import app.soundtree.R
import app.soundtree.util.RecordingFileHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Full-screen dialog that lists orphaned recording files found on disk
 * (files with no matching database row) and lets the user recover or
 * delete each one.
 *
 * Shown automatically at startup by [app.soundtree.ui.MainActivity]
 * when [app.soundtree.util.OrphanRecordingScanner] finds orphans, and
 * also manually via the "Review orphaned recordings" button in Settings.
 *
 * Orphan recordings have no DB rows so they cannot use the app's
 * [app.soundtree.service.PlaybackService] / ExoPlayer path. Only one
 * item plays at a time; the player is released when the dialog dismisses.
 *
 * The dialog dismisses itself automatically once every item has been
 * acted on.
 */
class OrphanRecoveryDialogFragment : DialogFragment() {

    // Use the full app theme so MaterialButton styles and custom colour
    // attrs resolve correctly.
    override fun getTheme(): Int = R.style.Theme_SoundTree_BottomSheet

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

    private var scrubTickerJob: Job? = null

    // ── Dialog window setup ───────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            setWindowAnimations(R.style.Animation_SoundTree_SlideUpDown)
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

        disableFastScrollerAutoHide(view.findViewById(R.id.recyclerOrphans))
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
            val title = RecordingFileHelper.suggestedTitle(file)
            items += Item.Playable(
                file           = file,
                suggestedTitle = title,
                durationMs     = playableDurations.getOrElse(i) { 0L },
                editedTitle    = title,
            )
        }
        corruptPaths.forEach { path ->
            val file = File(path)
            items += Item.Corrupt(file = file, suggestedTitle = RecordingFileHelper.suggestedTitle(file))
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

            viewLifecycleOwner.lifecycleScope.launch {
                // If topicId is null (Unsorted), we don't need to wait for anything.
                val label = if (topicId == null) {
                    "📥  Unsorted"
                } else {
                    // Wait until allTopics contains the new topic — it may not be
                    // in the StateFlow yet if this result arrived before Room's
                    // insert propagated (e.g. when the user created a new topic
                    // from the picker's + button).
                    val topic = viewModel.allTopics
                        .first { topics -> topics.any { it.id == topicId } }
                        .first { it.id == topicId }
                    "${topic.icon}  ${topic.name}"
                }
                adapter.notifyItemChanged(idx, label)
            }
        }
    }

    // ── Actions ────────────────────────────────────────────────────────

    private fun onRecover(index: Int) {
        val item = items[index] as? Item.Playable ?: return
        if (currentlyPlayingPath == item.file.absolutePath) stopPreview()

        val title      = item.editedTitle.trim().ifEmpty { item.suggestedTitle }
        val volumeUuid = volumeUuidForFile(item.file)
        val createdAt  = RecordingFileHelper.createdAtFromFile(item.file)

        viewLifecycleOwner.lifecycleScope.launch {
            val recordingId = viewModel.saveRecordingWithMarks(
                filePath          = item.file.absolutePath,
                durationMs        = item.durationMs,
                fileSizeBytes     = item.file.length(),
                title             = title,
                topicId           = item.selectedTopicId,
                markTimestamps    = emptyList(),
                storageVolumeUuid = volumeUuid,
                createdAt         = createdAt,
            ).await()

            WaveformWorker.enqueue(
                context           = requireContext(),
                recordingId       = recordingId,
                filePath          = item.file.absolutePath,
                storageVolumeUuid = volumeUuid,
                createdAt         = createdAt,
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
        startScrubTicker(path)
    }

    private fun startScrubTicker(path: String) {
        scrubTickerJob?.cancel()
        scrubTickerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val player = mediaPlayer ?: break
                if (currentlyPlayingPath != path) break
                adapter.updateScrubPosition(path, player.currentPosition.toLong())
                delay(200L)
            }
        }
    }

    private fun stopPreview() {
        scrubTickerJob?.cancel()
        scrubTickerJob = null
        // Reset scrub on whatever was playing before clearing the path
        currentlyPlayingPath?.let { adapter.updateScrubPosition(it, -1L) }
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
        private val PAYLOAD_SCRUB = "scrub"

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

        /** posMs == -1 signals "stop and hide the scrub bar". */
        fun updateScrubPosition(path: String, posMs: Long) {
            val idx = items.indexOfFirst { it is Item.Playable && it.file.absolutePath == path }
            if (idx >= 0) notifyItemChanged(idx, PAYLOAD_SCRUB to posMs)
        }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
            payloads: List<Any>,
        ) {
            if (payloads.isNotEmpty() && holder is PlayableVH) {
                val first = payloads.first()
                // Existing topic-label payload
                if (first is String) {
                    holder.itemView.findViewById<MaterialButton>(R.id.btnPickTopic)?.text = first
                    return
                }
                // New scrub-position payload
                @Suppress("UNCHECKED_CAST")
                (first as? Pair<String, Long>)?.let { (_, posMs) ->
                    val item = items[position] as? Item.Playable ?: return
                    holder.updateScrub(item.durationMs, posMs)
                    return
                }
            }
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    // ── ViewHolders ────────────────────────────────────────────────────

    private inner class PlayableVH(view: View) : RecyclerView.ViewHolder(view) {
        private val scrubBar = itemView.findViewById<SeekBar>(R.id.scrubBar)

        fun bind(item: Item.Playable, index: Int) {
            val btnPlay    = itemView.findViewById<ImageView>(R.id.btnPlay)
            val tvDuration = itemView.findViewById<TextView>(R.id.tvDuration)
            val tvFileSize = itemView.findViewById<TextView>(R.id.tvFileSize)
            val tvPath     = itemView.findViewById<TextView>(R.id.tvFilePath)
            val etTitle    = itemView.findViewById<android.widget.EditText>(R.id.etTitle)
            val btnTopic   = itemView.findViewById<MaterialButton>(R.id.btnPickTopic)
            val btnDelete  = itemView.findViewById<MaterialButton>(R.id.btnDelete)
            val btnRecover = itemView.findViewById<MaterialButton>(R.id.btnRecover)

            tvDuration.text = formatDuration(item.durationMs)
            tvFileSize.text = AppVolume.formatBytes(item.file.length())
            tvPath.text = item.file.absolutePath
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

            // ── Scrub bar initial state ────────────────────────────────
            updateScrub(item.durationMs, if (isPlaying) mediaPlayer?.currentPosition?.toLong() ?: 0L else -1L)

            scrubBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) mediaPlayer?.seekTo(progress)
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }

        /** posMs == -1 → hide the bar and reset. */
        fun updateScrub(durationMs: Long, posMs: Long) {
            if (posMs < 0L || durationMs <= 0L) {
                scrubBar.visibility = View.GONE
                scrubBar.progress = 0
                itemView.findViewById<ImageView>(R.id.btnPlay)
                    .setImageResource(R.drawable.ic_play)
                return
            }
            scrubBar.visibility = View.VISIBLE
            scrubBar.max = durationMs.toInt()
            scrubBar.progress = posMs.toInt().coerceIn(0, durationMs.toInt())
        }
    }

    private inner class CorruptVH(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(item: Item.Corrupt, index: Int) {
            itemView.findViewById<TextView>(R.id.tvFilename).text = item.suggestedTitle
            itemView.findViewById<MaterialButton>(
                R.id.btnDelete
            ).setOnClickListener { onDelete(index) }
            itemView.findViewById<TextView>(R.id.tvFilePath).text = item.file.absolutePath
        }
    }

    private fun disableFastScrollerAutoHide(recyclerView: RecyclerView) {
        try {
            val fastScrollerField = RecyclerView::class.java
                .getDeclaredField("mFastScroller")
                .also { it.isAccessible = true }
            val fastScroller = fastScrollerField.get(recyclerView) ?: return

            fastScroller.javaClass
                .getDeclaredField("mHideRunnable")
                .also { it.isAccessible = true }
                .set(fastScroller, Runnable { /* no-op: prevent auto-hide */ })
        } catch (_: Exception) {
            // Reflection broke — scrollbar will auto-hide again, nothing worse
        }
    }
}