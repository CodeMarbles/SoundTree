package app.soundtree.ui.settings

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.soundtree.storage.AppVolume
import app.soundtree.ui.recovery.OrphanRecoveryDialogFragment
import app.soundtree.util.OrphanRecording
import kotlinx.coroutines.launch

// ── Restore ───────────────────────────────────────────────────────────────────

internal fun SettingsFragment.setupRestoreSection() {
    binding.groupRestore.btnRestoreFromBackup.setOnClickListener {
        openDocumentTreeForRestore.launch(null)
    }
}

// ── Recording recovery ────────────────────────────────────────────────────────

internal fun SettingsFragment.setupRecordingRecoverySection() {
    binding.groupRecordingRecovery.btnReviewOrphans.setOnClickListener {
        OrphanRecoveryDialogFragment
            .newInstance(viewModel.orphanRecordings.value)
            .show(parentFragmentManager, OrphanRecoveryDialogFragment.TAG)
    }

    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.orphanRecordings.collect { orphans ->
                renderOrphanSummary(orphans)
            }
        }
    }
}

internal fun SettingsFragment.renderOrphanSummary(orphans: List<OrphanRecording>) {
    val recoverable   = orphans.filter { it.isPlayable }
    val unrecoverable = orphans.filter { !it.isPlayable }
    binding.groupRecordingRecovery.tvOrphanRecoverableSummary.text = formatOrphanSummary(recoverable)
    binding.groupRecordingRecovery.tvOrphanCorruptSummary.text     = formatOrphanSummary(unrecoverable)
}

internal fun SettingsFragment.formatOrphanSummary(orphans: List<OrphanRecording>): String {
    if (orphans.isEmpty()) return "None"
    val count      = orphans.size
    val totalBytes = orphans.sumOf { it.file.length() }
    val label      = if (count == 1) "1 recording" else "$count recordings"
    return "$label · ${AppVolume.formatBytes(totalBytes)}"
}