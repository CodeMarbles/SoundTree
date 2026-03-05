package com.treecast.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.treecast.app.R
import com.treecast.app.databinding.FragmentSettingsBinding
import com.treecast.app.ui.MainViewModel
import com.treecast.app.util.themeColor
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTheme()
        setupPlaybackSettings()
        loadStats()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupTheme() {
        fun updateToggleVisuals(selected: String) {
            val activeText   = requireContext().themeColor(R.attr.colorTextPrimary)
            val inactiveText = requireContext().themeColor(R.attr.colorTextSecondary)
            val activeBg     = requireContext().themeColor(R.attr.colorSurfaceElevated)

            listOf(
                binding.btnThemeSystem to "system",
                binding.btnThemeLight  to "light",
                binding.btnThemeDark   to "dark"
            ).forEach { (btn, mode) ->
                val isActive = mode == selected
                btn.setTextColor(if (isActive) activeText else inactiveText)
                btn.setTypeface(null, if (isActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                btn.setBackgroundColor(
                    if (isActive) activeBg else android.graphics.Color.TRANSPARENT
                )
            }
        }

        updateToggleVisuals(viewModel.themeMode.value)

        fun select(mode: String) {
            viewModel.setThemeMode(mode)
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark"  -> AppCompatDelegate.MODE_NIGHT_YES
                    else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
            // Activity recreates automatically — no manual call needed.
        }

        binding.btnThemeSystem.setOnClickListener { select("system") }
        binding.btnThemeLight.setOnClickListener  { select("light")  }
        binding.btnThemeDark.setOnClickListener   { select("dark")   }
    }

    private fun setupPlaybackSettings() {
        // ── Jump to Library on save toggle ───────────────────────────────
        binding.switchJumpToLibrary.isChecked = viewModel.jumpToLibraryOnSave.value
        binding.switchJumpToLibrary.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setJumpToLibraryOnSave(isChecked)
        }

        // ── Auto-navigate toggle ───────────────────────────────────────
        binding.switchAutoNavigate.isChecked = viewModel.autoNavigateToListen.value
        binding.switchAutoNavigate.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoNavigateToListen(isChecked)
        }

        // ── Scrub Back ────────────────────────────────────────────────
        // Initialise display from ViewModel
        binding.tvScrubBackSecs.text = viewModel.scrubBackSecs.value.toString()

        binding.btnScrubBackMinus.setOnClickListener {
            val newVal = (viewModel.scrubBackSecs.value - 5).coerceAtLeast(5)
            viewModel.setScrubBackSecs(newVal)
            binding.tvScrubBackSecs.text = newVal.toString()
        }
        binding.btnScrubBackPlus.setOnClickListener {
            val newVal = viewModel.scrubBackSecs.value + 5
            viewModel.setScrubBackSecs(newVal)
            binding.tvScrubBackSecs.text = newVal.toString()
        }

        // ── Scrub Forward ─────────────────────────────────────────────
        binding.tvScrubForwardSecs.text = viewModel.scrubForwardSecs.value.toString()

        binding.btnScrubForwardMinus.setOnClickListener {
            val newVal = (viewModel.scrubForwardSecs.value - 5).coerceAtLeast(5)
            viewModel.setScrubForwardSecs(newVal)
            binding.tvScrubForwardSecs.text = newVal.toString()
        }
        binding.btnScrubForwardPlus.setOnClickListener {
            val newVal = viewModel.scrubForwardSecs.value + 5
            viewModel.setScrubForwardSecs(newVal)
            binding.tvScrubForwardSecs.text = newVal.toString()
        }
    }

    private fun loadStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    val lastSession = viewModel.getLastSession()
                    binding.tvLastOpened.text = if (lastSession?.closedAt != null) {
                        "Last session: ${formatGap(System.currentTimeMillis() - lastSession.closedAt)} ago"
                    } else {
                        "Last session: this session"
                    }
                    binding.tvTotalRecordings.text =
                        "Total recordings: ${viewModel.allRecordings.value.size}"
                }
            }
        }
    }

    private fun formatGap(ms: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return when {
            hours >= 24 -> "${hours / 24}d ${hours % 24}h"
            hours > 0   -> "${hours}h ${minutes}m"
            else        -> "${minutes}m"
        }
    }
}