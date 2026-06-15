package app.soundtree.ui.settings

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import app.soundtree.R
import app.soundtree.databinding.FragmentSettingsBinding
import app.soundtree.databinding.ViewBackupProgressCardBinding
import app.soundtree.ui.MainViewModel
import app.soundtree.ui.addBackupTarget
import app.soundtree.ui.refreshStorageVolumes
import app.soundtree.ui.restore.RestoreWizardDialogFragment
import app.soundtree.util.themeColor

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    internal val binding get() = _binding!!
    internal val viewModel: MainViewModel by activityViewModels()

    // ── Tab state ─────────────────────────────────────────────────────
    internal enum class Tab { DISPLAY, BEHAVIOR, STORAGE, TOOLS }
    private var activeTab = Tab.DISPLAY

    /**
     * Stores the UUID of the volume the user tapped "Add" on while the SAF
     * directory picker is open. Cleared when the picker returns.
     */
    internal var pendingBackupVolumeUuid: String? = null

    internal var backupProgressCardBinding: ViewBackupProgressCardBinding? = null

    /**
     * SAF directory picker launcher.
     *
     * Opened when the user taps "Add as backup target" on an available volume.
     * On a successful result:
     *   1. Persist read+write permission so it survives app restart.
     *   2. Hand the volume UUID + URI to the ViewModel to insert the target row.
     *
     * A null result (user cancelled) is silently ignored.
     */
    internal val openDocumentTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val volumeUuid = pendingBackupVolumeUuid ?: return@registerForActivityResult
        pendingBackupVolumeUuid = null
        if (uri == null) return@registerForActivityResult

        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        viewModel.addBackupTarget(volumeUuid, uri.toString())
    }

    internal val openDocumentTreeForRestore = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult

        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )

        RestoreWizardDialogFragment.newInstance(backupRootUri = uri.toString())
            .show(parentFragmentManager, RestoreWizardDialogFragment.TAG)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTabs()
        setupHeader()
        setupTheme()
        setupWaveformStyleSettings()
        setupPlayheadVis()
        setupPlaybackMemory()
        setupLayoutSection()
        setupRecordingWidgetSection()
        setupPlaybackWidgetSection()
        setupPlaybackSettings()
        setupStorageSection()
        setupBackupProgressCard()
        setupRecordingRecoverySection()
        setupBackupSection()
        setupRestoreSection()
        setupProcessingSection()
        setupMigrationSection()
        setupDevOptionsSection()
        loadStats()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        backupProgressCardBinding = null
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStorageVolumes()
    }

    // ── Tab management ────────────────────────────────────────────────

    internal fun selectTab(tab: Tab) {
        activeTab = tab
        val scrolls = mapOf(
            Tab.DISPLAY  to binding.scrollDisplay,
            Tab.BEHAVIOR to binding.scrollBehavior,
            Tab.STORAGE  to binding.scrollStorage,
            Tab.TOOLS    to binding.scrollTools,
        )
        scrolls.forEach { (t, scroll) ->
            scroll.visibility = if (t == tab) View.VISIBLE else View.GONE
        }
        // update tab pill visuals — move the existing styling logic here too
        listOf(
            binding.tabDisplay  to Tab.DISPLAY,
            binding.tabBehavior to Tab.BEHAVIOR,
            binding.tabStorage  to Tab.STORAGE,
            binding.tabTools    to Tab.TOOLS,
        ).forEach { (view, t) ->
            val isActive = t == tab
            view.isSelected = isActive
            view.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
            view.setTextColor(
                if (isActive) requireContext().themeColor(R.attr.colorTextPrimary)
                else          requireContext().themeColor(R.attr.colorTextSecondary)
            )
            view.background = if (isActive) android.graphics.drawable.GradientDrawable().apply {
                shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = resources.getDimension(R.dimen.settings_card_corner_radius) -
                        resources.displayMetrics.density * 3f
                setColor(requireContext().themeColor(R.attr.colorSurfaceElevated))
            } else null
        }
    }

    private fun setupTabs() {
        val tabs = listOf(
            binding.tabDisplay  to Tab.DISPLAY,
            binding.tabBehavior to Tab.BEHAVIOR,
            binding.tabStorage  to Tab.STORAGE,
            binding.tabTools    to Tab.TOOLS
        )

        tabs.forEach { (tv, tab) ->
            tv.setOnClickListener { selectTab(tab) }
        }

        // Apply initial state
        selectTab(Tab.DISPLAY)
    }

    private fun setupHeader() {
        binding.tvAppIdentity.text = getString(R.string.app_name)
    }

    internal var processingOutputExpanded = true
    internal lateinit var recentJobsAdapter: WaveformJobAdapter

}