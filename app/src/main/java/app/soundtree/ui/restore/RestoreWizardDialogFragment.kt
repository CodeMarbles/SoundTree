package app.soundtree.ui.restore

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.soundtree.R
import app.soundtree.storage.AppVolume
import app.soundtree.storage.StorageVolumeHelper
import app.soundtree.ui.LibrarySummary
import app.soundtree.ui.MainViewModel
import app.soundtree.ui.MilestoneEntry
import app.soundtree.ui.MilestoneState
import app.soundtree.ui.RestorePhase
import app.soundtree.ui.getLibrarySummary
import app.soundtree.ui.listDbSnapshots
import app.soundtree.ui.readBackupManifest
import app.soundtree.ui.resetRestorePhase
import app.soundtree.ui.resolveRecordingVolume
import app.soundtree.ui.restoreFromBackup
import app.soundtree.ui.restorePhase
import app.soundtree.util.BackupManifest
import app.soundtree.util.DatabaseRestoreManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * A four-step "wizard" dialog that guides the user through a database restore.
 *
 * ## Step 0 — Snapshot selection
 * Scans the chosen backup root for available `.db` snapshots and presents
 * them as a radio list. If the backup contains a `soundtree-backup.json`
 * manifest, a summary line (recording count + last backup date) is shown
 * above the list to confirm the user has selected the right folder.
 * The user picks a snapshot and taps Next.
 *
 * ## Step 1 — Library summary
 * Displays the current live library's recording / mark / topic counts and
 * explains that a safety snapshot will be created before anything is
 * overwritten. Intended to give the user a clear picture of what is at stake.
 *
 * ## Step 2 — Confirmation
 * A prominent warning screen explaining the destructive nature of the
 * operation. The Restore button is the commit point — once tapped, the
 * wizard moves to the progress step and the restore cannot be aborted.
 *
 * ## Step 3 — Progress
 * Displays a live progress bar and status label driven by [RestorePhase]
 * updates from the ViewModel. The dialog is non-dismissable at this step.
 * The app process is killed and restarted on success; errors are surfaced
 * with a message.
 *
 * ## Usage
 * ```kotlin
 * RestoreWizardDialogFragment.newInstance(backupRootUri = uri.toString())
 *     .show(parentFragmentManager, RestoreWizardDialogFragment.TAG)
 * ```
 */
class RestoreWizardDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "RestoreWizardDialog"
        private const val ARG_BACKUP_ROOT_URI = "backup_root_uri"

        fun newInstance(backupRootUri: String): RestoreWizardDialogFragment =
            RestoreWizardDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_BACKUP_ROOT_URI, backupRootUri)
                }
            }
    }

    override fun getTheme(): Int = R.style.Theme_SoundTree_FullscreenDialog

    // ── Step identifiers ──────────────────────────────────────────────────────

    private enum class Step(val index: Int) {
        SNAPSHOT_SELECT(0),
        SUMMARY(1),
        VOLUME_SELECT(2),
        CONFIRM(3),
        PROGRESS(4),
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var backupRootUri: String

    private var snapshots: List<DatabaseRestoreManager.DbSnapshot> = emptyList()
    private var selectedSnapshotIndex: Int = 0
    private var librarySummary: LibrarySummary? = null
    private var backupManifest: BackupManifest? = null

    // Volume selection state
    private var availableVolumes: List<AppVolume> = emptyList()
    private var selectedVolumeUuid: String? = null
    private var backupAudioBytes: Long? = null          // null = scan in progress
    private var audioSizeScanJob: Job? = null
    private var volumeStepLoaded = false

    // ── Views ─────────────────────────────────────────────────────────────────

    private lateinit var flipper: ViewFlipper

    // Step 0 — snapshot selection
    private lateinit var tvManifestSummary: TextView
    private lateinit var rgSnapshots: RadioGroup
    private lateinit var tvNoSnapshots: TextView
    private lateinit var tvSnapshotLoading: TextView

    // Step 1 — library summary
    private lateinit var tvSummaryRecordings: TextView
    private lateinit var tvSummaryMarks: TextView
    private lateinit var tvSummaryTopics: TextView
    private lateinit var tvSummaryLoading: TextView

    // Step 2 — volume selection
    private lateinit var tvVolumeLoading: TextView
    private lateinit var layoutSingleVolume: View
    private lateinit var tvSingleVolumeInfo: TextView
    private lateinit var scrollVolumeRadio: View
    private lateinit var rgVolumes: RadioGroup
    private lateinit var layoutSpaceWarning: View
    private lateinit var tvSpaceWarning: TextView

    // Step 3 — confirm
    // (no dynamic views beyond the static layout)

    // Step 3 — progress
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressLabel: TextView
    private lateinit var tvProgressSub: TextView
    private lateinit var tvProgressError: TextView

    // Step 3 — milestone board
    // Each milestone row is an included layout; we bind its child views by ID.
    private lateinit var milestoneViews: List<View>  // indexed by Milestone.ordinal

    // Step 3 — filter chips
    private lateinit var chipGroupFilter: ChipGroup

    // Step 3 — file log
    private lateinit var recyclerProgressLog: RecyclerView
    private lateinit var progressAdapter: RestoreProgressAdapter

    // Auto-scroll: pause auto-scroll while user is reading; resume at bottom.
    private var userScrolledUp = false

    // Navigation buttons (shared across all steps)
    private lateinit var btnBack: MaterialButton
    private lateinit var btnNext: MaterialButton

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backupRootUri = requireArguments().getString(ARG_BACKUP_ROOT_URI)!!
        // Reset any leftover phase from a previous (failed) session.
        viewModel.resetRestorePhase()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.dialog_restore_wizard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── Bind views ────────────────────────────────────────────────────────

        flipper = view.findViewById(R.id.wizardFlipper)

        // Step 0
        tvManifestSummary = view.findViewById(R.id.tvManifestSummary)
        rgSnapshots       = view.findViewById(R.id.rgSnapshots)
        tvNoSnapshots     = view.findViewById(R.id.tvNoSnapshots)
        tvSnapshotLoading = view.findViewById(R.id.tvSnapshotLoading)

        // Step 1
        tvSummaryRecordings = view.findViewById(R.id.tvSummaryRecordings)
        tvSummaryMarks      = view.findViewById(R.id.tvSummaryMarks)
        tvSummaryTopics     = view.findViewById(R.id.tvSummaryTopics)
        tvSummaryLoading    = view.findViewById(R.id.tvSummaryLoading)

        tvVolumeLoading    = view.findViewById(R.id.tvVolumeLoading)
        layoutSingleVolume = view.findViewById(R.id.layoutSingleVolume)
        tvSingleVolumeInfo = view.findViewById(R.id.tvSingleVolumeInfo)
        scrollVolumeRadio  = view.findViewById(R.id.scrollVolumeRadio)
        rgVolumes          = view.findViewById(R.id.rgVolumes)
        layoutSpaceWarning = view.findViewById(R.id.layoutSpaceWarning)
        tvSpaceWarning     = view.findViewById(R.id.tvSpaceWarning)

        progressBar     = view.findViewById(R.id.restoreProgressBar)
        tvProgressLabel = view.findViewById(R.id.tvProgressLabel)
        tvProgressSub   = view.findViewById(R.id.tvProgressSub)
        tvProgressError = view.findViewById(R.id.tvProgressError)

        // Step 3 — milestone board: collect included views in ordinal order.
        milestoneViews = listOf(
            view.findViewById(R.id.milestoneSafety),
            view.findViewById(R.id.milestoneMetadata),
            view.findViewById(R.id.milestoneDbSwap),
            view.findViewById(R.id.milestonePathRemap),
        )
        // Set the static label text on each milestone row immediately.
        val milestoneLabels = RestorePhase.Running.INITIAL_MILESTONES.map { it.label }
        milestoneViews.forEachIndexed { i, rowView ->
            rowView.findViewById<TextView>(R.id.tvMilestoneLabel).text = milestoneLabels[i]
        }

        // Step 3 — filter chips
        chipGroupFilter = view.findViewById(R.id.chipGroupFilter)
        chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                R.id.chipFilterWrites -> RestoreLogFilter.WRITES_AND_FAILS
                R.id.chipFilterFails  -> RestoreLogFilter.FAILURES_ONLY
                else                  -> RestoreLogFilter.ALL
            }
            progressAdapter.setFilter(filter)
            resubmitLogItems()  // rebuild list with new filter
        }

        // Step 3 — file log RecyclerView
        progressAdapter = RestoreProgressAdapter()
        progressAdapter.onHeaderTapped = { _ -> resubmitLogItems() }

        recyclerProgressLog = view.findViewById(R.id.recyclerProgressLog)
        recyclerProgressLog.layoutManager = LinearLayoutManager(requireContext())
        recyclerProgressLog.adapter       = progressAdapter

        // Pause auto-scroll when the user scrolls up; resume when they reach bottom.
        recyclerProgressLog.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    userScrolledUp = true
                }
            }
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!rv.canScrollVertically(1)) {
                    // User has scrolled back to the bottom — resume auto-scroll.
                    userScrolledUp = false
                }
            }
        })

        // Nav buttons
        btnBack = view.findViewById(R.id.btnWizardBack)
        btnNext = view.findViewById(R.id.btnWizardNext)
        btnBack.setOnClickListener { onBackClicked() }
        btnNext.setOnClickListener { onNextClicked() }

        // Show step 0 and kick off snapshot scan + manifest read in parallel.
        showStep(Step.SNAPSHOT_SELECT)
        loadSnapshots()

        // Observe restore progress for step 3.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.restorePhase.collect { phase -> onRestorePhaseChanged(phase) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Make the dialog fill screen width with comfortable horizontal margins.
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun currentStep(): Step =
        Step.values().first { it.index == flipper.displayedChild }

    private fun showStep(step: Step) {
        flipper.displayedChild = step.index
        if (step == Step.VOLUME_SELECT && !volumeStepLoaded) {
            volumeStepLoaded = true
            loadVolumes()
            startAudioSizeScan()
        }
        updateNavButtons(step)
        isCancelable = step != Step.PROGRESS
    }

    private fun updateNavButtons(step: Step) {
        when (step) {
            Step.SNAPSHOT_SELECT -> {
                btnBack.isVisible = false
                btnNext.text      = getString(R.string.wizard_btn_next)
                btnNext.isEnabled = snapshots.isNotEmpty()
            }
            Step.SUMMARY -> {
                btnBack.isVisible = true
                btnBack.text      = getString(R.string.wizard_btn_back)
                btnNext.text      = getString(R.string.wizard_btn_next)
                btnNext.isEnabled = librarySummary != null
            }
            Step.VOLUME_SELECT -> {
                btnBack.isVisible = true
                btnBack.text      = getString(R.string.wizard_btn_back)
                btnNext.text      = getString(R.string.wizard_btn_next)
                btnNext.isEnabled = selectedVolumeUuid != null
            }
            Step.CONFIRM -> {
                btnBack.isVisible = true
                btnBack.text      = getString(R.string.wizard_btn_back)
                btnNext.text      = getString(R.string.wizard_btn_restore)
                btnNext.isEnabled = true
            }
            Step.PROGRESS -> {
                btnBack.isVisible = false
                btnNext.isVisible = false
            }
        }
    }

    private fun onNextClicked() {
        when (currentStep()) {
            Step.SNAPSHOT_SELECT -> {
                showStep(Step.SUMMARY)
                if (librarySummary == null) loadLibrarySummary()
            }
            Step.SUMMARY -> {
                showStep(Step.VOLUME_SELECT)
            }
            Step.VOLUME_SELECT -> {
                showStep(Step.CONFIRM)
            }
            Step.CONFIRM -> {
                // Commit — start the restore
                showStep(Step.PROGRESS)
                val chosen = snapshots[selectedSnapshotIndex]
                viewModel.restoreFromBackup(
                    backupRootDirUri = backupRootUri,
                    backupFile       = chosen.file,
                    targetVolumeUuid = selectedVolumeUuid,
                )
            }
            Step.PROGRESS -> { /* unreachable — buttons hidden */ }
        }
    }

    private fun onBackClicked() {
        when (currentStep()) {
            Step.SUMMARY       -> showStep(Step.SNAPSHOT_SELECT)
            Step.VOLUME_SELECT -> showStep(Step.SUMMARY)
            Step.CONFIRM       -> showStep(Step.VOLUME_SELECT)
            else               -> { /* no-op */ }
        }
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    /**
     * Loads available snapshots and the backup manifest in parallel, then
     * populates the Step 0 UI. The manifest read is best-effort — a missing
     * or malformed manifest simply leaves [tvManifestSummary] hidden.
     */
    private fun loadSnapshots() {
        tvSnapshotLoading.isVisible = true
        tvManifestSummary.isVisible = false
        rgSnapshots.isVisible       = false
        tvNoSnapshots.isVisible     = false

        viewLifecycleOwner.lifecycleScope.launch {
            // Run both IO calls concurrently — manifest read is cheap but no
            // reason to block the snapshot list on it.
            val (result, manifest) = coroutineScope {
                val snapshotsDeferred = async { viewModel.listDbSnapshots(backupRootUri) }
                val manifestDeferred  = async { viewModel.readBackupManifest(backupRootUri) }
                snapshotsDeferred.await() to manifestDeferred.await()
            }

            snapshots      = result
            backupManifest = manifest

            tvSnapshotLoading.isVisible = false
            bindManifestHeader()

            if (result.isEmpty()) {
                tvNoSnapshots.isVisible = true
                btnNext.isEnabled       = false
                return@launch
            }

            rgSnapshots.isVisible = true
            rgSnapshots.removeAllViews()

            result.forEachIndexed { index, snapshot ->
                val rb = RadioButton(requireContext()).apply {
                    id   = View.generateViewId()
                    text = snapshot.displayName
                }
                rgSnapshots.addView(rb)
                if (index == 0) {
                    rgSnapshots.check(rb.id)
                    selectedSnapshotIndex = 0
                }
            }

            rgSnapshots.setOnCheckedChangeListener { group, checkedId ->
                val rb = group.findViewById<RadioButton>(checkedId)
                selectedSnapshotIndex = group.indexOfChild(rb)
            }

            btnNext.isEnabled = true
        }
    }

    /**
     * Populates [tvManifestSummary] from [backupManifest] if present.
     * Shows nothing if the backup pre-dates manifest support — no regression
     * for older backups.
     */
    private fun bindManifestHeader() {
        val m = backupManifest
        tvManifestSummary.isVisible = m != null
        if (m == null) return

        val displayDate = runCatching {
            val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val displayFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            displayFmt.format(isoFmt.parse(m.lastBackupAt)!!)
        }.getOrElse { m.lastBackupAt }

        tvManifestSummary.text =
            "${m.recordingCount} recordings · ${m.topicCount} topics · Last backed up $displayDate"
    }

    private fun loadLibrarySummary() {
        tvSummaryLoading.isVisible     = true
        tvSummaryRecordings.isVisible  = false
        tvSummaryMarks.isVisible       = false
        tvSummaryTopics.isVisible      = false
        btnNext.isEnabled              = false

        viewLifecycleOwner.lifecycleScope.launch {
            val summary = viewModel.getLibrarySummary()
            librarySummary = summary

            tvSummaryLoading.isVisible    = false
            tvSummaryRecordings.isVisible = true
            tvSummaryMarks.isVisible      = true
            tvSummaryTopics.isVisible     = true

            tvSummaryRecordings.text = resources.getQuantityString(
                R.plurals.restore_summary_recordings, summary.recordingCount, summary.recordingCount
            )
            tvSummaryMarks.text = resources.getQuantityString(
                R.plurals.restore_summary_marks, summary.markCount, summary.markCount
            )
            tvSummaryTopics.text = resources.getQuantityString(
                R.plurals.restore_summary_topics, summary.topicCount, summary.topicCount
            )

            btnNext.isEnabled = true
        }
    }

    // ── Volume step ───────────────────────────────────────────────────────────

    /** Populates [availableVolumes], pre-selects the current default, and renders the step. */
    private fun loadVolumes() {
        availableVolumes = StorageVolumeHelper.getVolumes(requireContext())
        // Pre-select the volume that's currently preferred for recording.
        val currentDefault = viewModel.resolveRecordingVolume().uuid
        selectedVolumeUuid = availableVolumes.firstOrNull { it.uuid == currentDefault }?.uuid
            ?: availableVolumes.firstOrNull()?.uuid
        populateVolumeStep()
    }

    private fun populateVolumeStep() {
        tvVolumeLoading.isVisible = false

        if (availableVolumes.size == 1) {
            val vol = availableVolumes[0]
            layoutSingleVolume.isVisible = true
            scrollVolumeRadio.isVisible  = false
            tvSingleVolumeInfo.text = getString(
                R.string.restore_step_volume_single,
                vol.label,
                AppVolume.formatBytes(vol.freeBytes),
            )
        } else {
            layoutSingleVolume.isVisible = false
            scrollVolumeRadio.isVisible  = true
            rgVolumes.removeAllViews()
            availableVolumes.forEach { vol ->
                val rb = RadioButton(requireContext()).apply {
                    id        = View.generateViewId()
                    text      = "${vol.label}\n${AppVolume.formatBytes(vol.freeBytes)} free"
                    isChecked = vol.uuid == selectedVolumeUuid
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                            selectedVolumeUuid = vol.uuid
                            updateNavButtons(currentStep())
                            updateSpaceWarning()
                        }
                    }
                }
                rgVolumes.addView(rb)
            }
        }
        updateSpaceWarning()
    }

    /**
     * Shows or hides the space warning based on [backupAudioBytes] vs the
     * selected volume's free space. No-op if the scan hasn't finished yet.
     */
    private fun updateSpaceWarning() {
        val bytes = backupAudioBytes ?: return         // scan still running
        val vol   = availableVolumes.firstOrNull { it.uuid == selectedVolumeUuid }
            ?: availableVolumes.firstOrNull()
            ?: return
        if (bytes > 0L && bytes > vol.freeBytes) {
            tvSpaceWarning.text = getString(
                R.string.restore_step_volume_space_warning,
                AppVolume.formatBytes(bytes),
            )
            layoutSpaceWarning.isVisible = true
        } else {
            layoutSpaceWarning.isVisible = false
        }
    }

    /**
     * Walks the backup's `recordings/` tree on IO and sums `.m4a` sizes.
     * Updates [backupAudioBytes] and refreshes the warning if the user is
     * still on the volume step when the scan completes.
     */
    private fun startAudioSizeScan() {
        audioSizeScanJob?.cancel()
        audioSizeScanJob = viewLifecycleOwner.lifecycleScope.launch {
            backupAudioBytes = withContext(Dispatchers.IO) {
                val root = DocumentFile.fromTreeUri(
                    requireContext(), Uri.parse(backupRootUri)
                ) ?: return@withContext 0L
                val recordingsDir = root.findFile("recordings")
                    ?.takeIf { it.isDirectory }
                    ?: return@withContext 0L
                var total = 0L
                fun walkDir(dir: DocumentFile) {
                    dir.listFiles().forEach { f ->
                        if (f.isDirectory) walkDir(f)
                        else if (f.name?.endsWith(".m4a") == true) total += f.length()
                    }
                }
                walkDir(recordingsDir)
                total
            }
            if (currentStep() == Step.VOLUME_SELECT) updateSpaceWarning()
        }
    }

    // ── Progress observation ──────────────────────────────────────────────────

    private fun onRestorePhaseChanged(phase: RestorePhase) {
        if (currentStep() != Step.PROGRESS && phase !is RestorePhase.Error) return

        when (phase) {
            is RestorePhase.Idle -> { /* nothing */ }

            is RestorePhase.Running -> {
                lastRunningPhase = phase

                // ── Progress bar ──────────────────────────────────────────────
                tvProgressError.isVisible = false
                tvProgressLabel.text      = phase.label
                if (phase.total > 0) {
                    progressBar.isIndeterminate = false
                    progressBar.max             = phase.total
                    progressBar.progress        = phase.current
                    tvProgressSub.isVisible     = true
                    tvProgressSub.text          = "${phase.current} / ${phase.total}"
                } else {
                    progressBar.isIndeterminate = true
                    tvProgressSub.isVisible     = false
                }

                // ── Milestone board ───────────────────────────────────────────
                phase.milestones.forEachIndexed { i, entry ->
                    bindMilestoneRow(milestoneViews[i], entry)
                }

                // ── Auto-collapse clean sections ──────────────────────────────
                // When a section transitions to complete with no failures, collapse
                // it so failures in the other section are more prominent.
                if (phase.recordingsComplete) {
                    progressAdapter.autoCollapseIfClean(
                        FileCategory.RECORDINGS, phase.recordingCounts
                    )
                }
                if (phase.waveformsComplete) {
                    progressAdapter.autoCollapseIfClean(
                        FileCategory.WAVEFORMS, phase.waveformCounts
                    )
                }

                // ── File log ──────────────────────────────────────────────────
                val hasFileActivity = phase.recordingCounts.hasActivity
                        || phase.waveformCounts.hasActivity
                        || phase.recordingsRunning
                        || phase.waveformsRunning
                if (hasFileActivity) {
                    chipGroupFilter.isVisible      = true
                    recyclerProgressLog.isVisible  = true
                }
                resubmitLogItems()
            }

            is RestorePhase.Error -> {
                // Keep the milestone board and log visible so the user can see how
                // far the restore got before the failure. Just add the error text below.
                progressBar.isIndeterminate = false
                progressBar.progress        = 0
                tvProgressLabel.text        = getString(R.string.restore_progress_failed)
                tvProgressError.isVisible   = true
                tvProgressError.text        = phase.message
                if (phase.isPostSwap) {
                    tvProgressError.append(
                        "\n\n" + getString(R.string.restore_progress_post_swap_note)
                    )
                }

                isCancelable      = true
                btnNext.isVisible = true
                btnNext.text      = getString(android.R.string.ok)
                btnNext.setOnClickListener { dismiss() }
            }
        }
    }

    /**
     * Binds a single milestone row view to a [MilestoneEntry].
     *
     * The row has three mutually exclusive icon states:
     *  - PENDING  → grey dot
     *  - RUNNING  → small indeterminate spinner
     *  - SUCCESS  → green ✓ glyph
     *  - FAILURE  → red ✗ glyph
     */
    private fun bindMilestoneRow(rowView: View, entry: MilestoneEntry) {
        val spinner   = rowView.findViewById<ProgressBar>(R.id.milestoneSpinner)
        val tvIcon    = rowView.findViewById<TextView>(R.id.tvMilestoneIcon)
        val dot       = rowView.findViewById<View>(R.id.milestonePendingDot)
        val tvLabel   = rowView.findViewById<TextView>(R.id.tvMilestoneLabel)
        val tvStamp   = rowView.findViewById<TextView>(R.id.tvMilestoneTimestamp)

        spinner.isVisible = entry.state == MilestoneState.RUNNING
        dot.isVisible     = entry.state == MilestoneState.PENDING
        tvIcon.isVisible  = entry.state == MilestoneState.SUCCESS
                || entry.state == MilestoneState.FAILURE

        when (entry.state) {
            MilestoneState.SUCCESS -> {
                tvIcon.text = "✓"
                tvIcon.setTextColor(ContextCompat.getColor(requireContext(), R.color.restore_log_copied))
                tvLabel.alpha = 1f
            }
            MilestoneState.FAILURE -> {
                tvIcon.text = "✗"
                tvIcon.setTextColor(ContextCompat.getColor(requireContext(), R.color.restore_log_failed))
                tvLabel.alpha = 1f
            }
            MilestoneState.PENDING -> {
                tvLabel.alpha = 0.45f  // dim pending rows
            }
            MilestoneState.RUNNING -> {
                tvLabel.alpha = 1f
            }
        }

        // Timestamp + optional detail
        if (entry.timestamp.isNotEmpty()) {
            tvStamp.isVisible = true
            tvStamp.text      = entry.timestamp
        } else {
            tvStamp.isVisible = false
        }

        // Detail line beneath the label (e.g. "47 recordings exported").
        tvLabel.text = if (entry.detail != null && entry.state != MilestoneState.PENDING) {
            "${entry.label}\n${entry.detail}"
        } else {
            entry.label
        }
    }

    /**
     * Rebuilds the adapter's item list from the last emitted [RestorePhase.Running]
     * state and the adapter's current filter + collapse flags, then submits it.
     *
     * Called on every [onRestorePhaseChanged] update and whenever the filter or
     * a section header is tapped.
     */
    private var lastRunningPhase: RestorePhase.Running? = null

    private fun resubmitLogItems() {
        val phase = lastRunningPhase ?: return
        val items = progressAdapter.buildItems(
            recordingEvents   = phase.recordingEvents,
            recordingCounts   = phase.recordingCounts,
            recordingsRunning = phase.recordingsRunning,
            recordingsComplete = phase.recordingsComplete,
            waveformEvents    = phase.waveformEvents,
            waveformCounts    = phase.waveformCounts,
            waveformsRunning  = phase.waveformsRunning,
            waveformsComplete = phase.waveformsComplete,
        )
        progressAdapter.submitList(items) {
            // After DiffUtil dispatch completes, scroll to bottom unless the user
            // has manually scrolled up to review earlier entries.
            if (!userScrolledUp && items.isNotEmpty()) {
                recyclerProgressLog.scrollToPosition(items.lastIndex)
            }
        }
    }
}