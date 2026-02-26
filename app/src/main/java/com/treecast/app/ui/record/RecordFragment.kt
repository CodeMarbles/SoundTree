package com.treecast.app.ui.record

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.treecast.app.databinding.FragmentRecordBinding
import com.treecast.app.service.RecordingService
import com.treecast.app.ui.MainActivity
import com.treecast.app.ui.MainViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordFragment : Fragment() {

    private var _binding: FragmentRecordBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    private var selectedTopicId: Long? = null

    // ── Recording service ─────────────────────────────────────────────
    private var recordingService: RecordingService? = null
    private var isBound = false
    private var pendingQuickRecord = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            recordingService = (binder as RecordingService.RecordingBinder).getService()
            isBound = true
            observeServiceState()
            if (pendingQuickRecord) {
                pendingQuickRecord = false
                startRecording()
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            recordingService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else Toast.makeText(requireContext(), "Microphone permission required", Toast.LENGTH_LONG).show()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
        setupTopicPicker()
        observeLock()
        bindRecordingService()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (isBound) {
            requireContext().unbindService(serviceConnection)
            isBound = false
        }
        _binding = null
    }

    fun triggerQuickRecord() {
        if (isBound && recordingService != null) startRecording()
        else pendingQuickRecord = true
    }

    // ── Setup ─────────────────────────────────────────────────────────
    private fun setupButtons() {
        binding.btnRecord.setOnClickListener {
            when (recordingService?.state?.value) {
                RecordingService.State.IDLE      -> checkPermissionAndRecord()
                RecordingService.State.RECORDING -> pauseRecording()
                RecordingService.State.PAUSED    -> resumeRecording()
                null -> checkPermissionAndRecord()
            }
        }
        binding.btnStop.setOnClickListener { stopAndSave() }
        binding.fabLock.setOnClickListener { viewModel.setLocked(true) }
    }

    private fun setupTopicPicker() {
        // fragment_record.xml: id topicPicker (renamed from categoryPicker in step-3 XML edit)
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.allTopics.collect { topics ->
                binding.topicPicker.setTopics(topics)
            }
        }
        binding.topicPicker.onTopicSelected = { topicId ->
            selectedTopicId = topicId
        }
    }

    private fun observeServiceState() {
        val svc = recordingService ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            svc.state.collect { updateUiForState(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            svc.elapsedMs.collect { ms -> binding.tvTimer.text = formatDuration(ms) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            svc.amplitude.collect { amp -> binding.waveformView.pushAmplitude(amp) }
        }
    }

    private fun observeLock() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLocked.collect { locked ->
                binding.fabLock.alpha = if (locked) 0.3f else 1f
            }
        }
    }

    // ── Recording state UI ────────────────────────────────────────────
    private fun updateUiForState(state: RecordingService.State) {
        when (state) {
            RecordingService.State.IDLE -> {
                binding.btnRecord.text = "● REC"
                binding.btnRecord.backgroundTintList =
                    requireContext().getColorStateList(com.treecast.app.R.color.rec_red)
                binding.btnStop.visibility = View.GONE
                binding.tvTimer.text = "0:00"
                binding.waveformView.clear()
                binding.topicPicker.collapse()
            }
            RecordingService.State.RECORDING -> {
                binding.btnRecord.text = "⏸  Pause"
                binding.btnRecord.backgroundTintList =
                    requireContext().getColorStateList(android.R.color.holo_orange_light)
                binding.btnStop.visibility = View.VISIBLE
            }
            RecordingService.State.PAUSED -> {
                binding.btnRecord.text = "▶  Resume"
                binding.btnRecord.backgroundTintList =
                    requireContext().getColorStateList(com.treecast.app.R.color.accent)
                binding.btnStop.visibility = View.VISIBLE
            }
        }
    }

    // ── Recording actions ─────────────────────────────────────────────
    private fun checkPermissionAndRecord() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun bindRecordingService() {
        val intent = Intent(requireContext(), RecordingService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun startRecording() {
        val svc = recordingService ?: run {
            ContextCompat.startForegroundService(
                requireContext(),
                Intent(requireContext(), RecordingService::class.java)
            )
            bindRecordingService()
            pendingQuickRecord = true
            return
        }
        svc.startRecording()
    }

    private fun pauseRecording()  { recordingService?.pauseRecording() }
    private fun resumeRecording() { recordingService?.resumeRecording() }

    private fun stopAndSave() {
        val svc = recordingService ?: return
        val (filePath, durationMs) = svc.stopRecording()
        if (filePath != null && File(filePath).exists()) {
            val stamp = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                .format(Date(System.currentTimeMillis()))
            val savedDeferred = viewModel.saveRecording(
                filePath      = filePath,
                durationMs    = durationMs,
                fileSizeBytes = File(filePath).length(),
                title         = "Recording – $stamp",
                topicId       = selectedTopicId
            )
            Toast.makeText(requireContext(), "Saved!", Toast.LENGTH_SHORT).show()
            binding.topicPicker.collapse()

            val topicIdForNav = selectedTopicId
            selectedTopicId = null

            if (viewModel.jumpToLibraryOnSave.value) {
                lifecycleScope.launch {
                    val newId = savedDeferred.await()
                    viewModel.selectRecording(newId)
                    (requireActivity() as? MainActivity)
                        ?.navigateToLibraryForRecording(topicIdForNav)
                }
            }
        } else {
            Toast.makeText(requireContext(), "Nothing recorded", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}