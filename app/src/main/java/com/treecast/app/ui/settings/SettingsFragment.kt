package com.treecast.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.treecast.app.databinding.FragmentSettingsBinding
import com.treecast.app.ui.MainViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
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
        setupPlaybackSettings()
        loadStats()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupPlaybackSettings() {
        // Initialise toggle from ViewModel state
        binding.switchAutoNavigate.isChecked = viewModel.autoNavigateToListen.value

        // User changes the toggle → push to ViewModel
        binding.switchAutoNavigate.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoNavigateToListen(isChecked)
        }
    }

    private fun loadStats() {
        viewLifecycleOwner.lifecycleScope.launch {
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
