package app.treecast.ui.restore

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
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.treecast.R
import app.treecast.ui.LibrarySummary
import app.treecast.ui.MainViewModel
import app.treecast.ui.RestorePhase
import app.treecast.ui.getLibrarySummary
import app.treecast.ui.listDbSnapshots
import app.treecast.ui.resetRestorePhase
import app.treecast.ui.restoreFromBackup
import app.treecast.ui.restorePhase
import app.treecast.util.DatabaseRestoreManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * A four-step "wizard" dialog that guides the user through a database restore.
 *
 * ## Step 0 — Snapshot selection
 * Scans the chosen backup root for available `.db` snapshots and presents
 * them as a radio list. The user picks one and taps Next.
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

    override fun getTheme(): Int = R.style.Theme_TreeCast_FullscreenDialog

    // ── Step identifiers ──────────────────────────────────────────────────────

    private enum class Step(val index: Int) {
        SNAPSHOT_SELECT(0),
        SUMMARY(1),
        CONFIRM(2),
        PROGRESS(3),
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var backupRootUri: String

    private var snapshots: List<DatabaseRestoreManager.DbSnapshot> = emptyList()
    private var selectedSnapshotIndex: Int = 0
    private var librarySummary: LibrarySummary? = null

    // ── Views ─────────────────────────────────────────────────────────────────

    private lateinit var flipper: ViewFlipper

    // Step 0 — snapshot
    private lateinit var rgSnapshots: RadioGroup
    private lateinit var tvNoSnapshots: TextView
    private lateinit var tvSnapshotLoading: TextView

    // Step 1 — summary
    private lateinit var tvSummaryRecordings: TextView
    private lateinit var tvSummaryMarks: TextView
    private lateinit var tvSummaryTopics: TextView
    private lateinit var tvSummaryLoading: TextView

    // Step 2 — confirm
    // (no dynamic views beyond the static layout)

    // Step 3 — progress
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressLabel: TextView
    private lateinit var tvProgressSub: TextView
    private lateinit var tvProgressError: TextView

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

        // Bind views
        flipper          = view.findViewById(R.id.wizardFlipper)

        rgSnapshots      = view.findViewById(R.id.rgSnapshots)
        tvNoSnapshots    = view.findViewById(R.id.tvNoSnapshots)
        tvSnapshotLoading = view.findViewById(R.id.tvSnapshotLoading)

        tvSummaryRecordings = view.findViewById(R.id.tvSummaryRecordings)
        tvSummaryMarks      = view.findViewById(R.id.tvSummaryMarks)
        tvSummaryTopics     = view.findViewById(R.id.tvSummaryTopics)
        tvSummaryLoading    = view.findViewById(R.id.tvSummaryLoading)

        progressBar       = view.findViewById(R.id.restoreProgressBar)
        tvProgressLabel   = view.findViewById(R.id.tvProgressLabel)
        tvProgressSub     = view.findViewById(R.id.tvProgressSub)
        tvProgressError   = view.findViewById(R.id.tvProgressError)

        btnBack = view.findViewById(R.id.btnWizardBack)
        btnNext = view.findViewById(R.id.btnWizardNext)

        btnBack.setOnClickListener { onBackClicked() }
        btnNext.setOnClickListener { onNextClicked() }

        // Show step 0 and kick off snapshot scan
        showStep(Step.SNAPSHOT_SELECT)
        loadSnapshots()

        // Observe restore progress for step 3
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
                // Move to summary; load counts if not yet loaded
                showStep(Step.SUMMARY)
                if (librarySummary == null) loadLibrarySummary()
            }
            Step.SUMMARY -> {
                showStep(Step.CONFIRM)
            }
            Step.CONFIRM -> {
                // Commit — start the restore
                showStep(Step.PROGRESS)
                val chosen = snapshots[selectedSnapshotIndex]
                viewModel.restoreFromBackup(
                    backupRootDirUri = backupRootUri,
                    backupFile       = chosen.file,
                )
            }
            Step.PROGRESS -> { /* unreachable — buttons hidden */ }
        }
    }

    private fun onBackClicked() {
        when (currentStep()) {
            Step.SUMMARY  -> showStep(Step.SNAPSHOT_SELECT)
            Step.CONFIRM  -> showStep(Step.SUMMARY)
            else          -> { /* no-op */ }
        }
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadSnapshots() {
        tvSnapshotLoading.isVisible = true
        rgSnapshots.isVisible       = false
        tvNoSnapshots.isVisible     = false

        viewLifecycleOwner.lifecycleScope.launch {
            val result = viewModel.listDbSnapshots(backupRootUri)
            snapshots = result

            tvSnapshotLoading.isVisible = false

            if (result.isEmpty()) {
                tvNoSnapshots.isVisible = true
                btnNext.isEnabled       = false
                return@launch
            }

            rgSnapshots.isVisible   = true
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

    // ── Progress observation ──────────────────────────────────────────────────

    private fun onRestorePhaseChanged(phase: RestorePhase) {
        // Only act on progress updates while we're on the progress step.
        if (currentStep() != Step.PROGRESS && phase !is RestorePhase.Error) return

        when (phase) {
            is RestorePhase.Idle -> { /* nothing */ }

            is RestorePhase.Running -> {
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
            }

            is RestorePhase.Error -> {
                // Surface the error and allow dismissal.
                progressBar.isIndeterminate   = false
                progressBar.progress          = 0
                tvProgressLabel.text          = getString(R.string.restore_progress_failed)
                tvProgressError.isVisible     = true
                tvProgressError.text          = phase.message

                if (phase.isPostSwap) {
                    tvProgressError.append(
                        "\n\n" + getString(R.string.restore_progress_post_swap_note)
                    )
                }

                // Re-enable dismissal and show a close button.
                isCancelable    = true
                btnNext.isVisible = true
                btnNext.text    = getString(android.R.string.ok)
                btnNext.setOnClickListener { dismiss() }
            }
        }
    }
}