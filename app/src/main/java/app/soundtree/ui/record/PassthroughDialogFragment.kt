package app.soundtree.ui.record

import android.media.AudioDeviceInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import app.soundtree.R
import app.soundtree.databinding.DialogPassthroughBinding
import app.soundtree.databinding.ItemPassthroughDeviceBinding
import app.soundtree.service.PassthroughManager
import app.soundtree.util.themeColor
import kotlinx.coroutines.launch

/**
 * Bottom sheet for configuring audio passthrough.
 *
 * Inflates [DialogPassthroughBinding] (dialog_passthrough.xml) and wires
 * the enabled toggle and device list RecyclerView to [PassthroughManager]'s
 * state flows.
 *
 * Launched from [RecordFragment] via childFragmentManager so parentFragment
 * is always a [RecordFragment] — used to reach the bound service.
 */
class PassthroughDialogFragment : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.Theme_SoundTree_BottomSheet

    companion object {
        const val TAG = "passthrough_config"

        private val DEVICE_DIFF = object : DiffUtil.ItemCallback<PassthroughManager.OutputDevice>() {
            override fun areItemsTheSame(
                a: PassthroughManager.OutputDevice,
                b: PassthroughManager.OutputDevice,
            ) = a.key == b.key

            override fun areContentsTheSame(
                a: PassthroughManager.OutputDevice,
                b: PassthroughManager.OutputDevice,
            ) = a == b
        }
    }

    // ── Binding ───────────────────────────────────────────────────────────────

    private var _binding: DialogPassthroughBinding? = null
    private val binding get() = _binding!!

    // ── Parent service access ─────────────────────────────────────────────────

    private val recordFragment get() = parentFragment as? RecordFragment
    private val passthroughManager get() = recordFragment?.recordingService?.passthroughManager
    private val preferredInputDevice get() = recordFragment?.recordingService?.getPreferredInputDevice()

    // ── Bottom-sheet behaviour ────────────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state         = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogPassthroughBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val deviceAdapter = DeviceAdapter()

        binding.rvPassthroughDevices.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter       = deviceAdapter
            itemAnimator  = null  // avoids flicker as devices connect/disconnect
        }

        wireEnabledToggle()

        val mgr = passthroughManager ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Keep the enabled toggle in sync with PassthroughManager state.
                launch {
                    mgr.state.collect { state ->
                        val armed = state !is PassthroughManager.State.Idle
                        if (binding.switchPassthroughEnabled.isChecked != armed) {
                            binding.switchPassthroughEnabled.isChecked = armed
                        }
                    }
                }

                // Feed the device list.
                launch {
                    mgr.outputDevices.collect { devices ->
                        deviceAdapter.submitList(devices)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Enabled toggle ────────────────────────────────────────────────────────

    private fun wireEnabledToggle() {
        binding.switchPassthroughEnabled.setOnCheckedChangeListener { _, isChecked ->
            val mgr     = passthroughManager ?: return@setOnCheckedChangeListener
            val isArmed = mgr.state.value !is PassthroughManager.State.Idle
            // Guard: only call toggleArmed when the switch state differs from
            // the current armed state — prevents re-entrancy when the flow
            // observer programmatically updates isChecked.
            if (isChecked != isArmed) {
                recordFragment?.recordingService?.togglePassthrough()
            }
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private inner class DeviceAdapter :
        ListAdapter<PassthroughManager.OutputDevice, DeviceAdapter.VH>(DEVICE_DIFF) {

        inner class VH(val binding: ItemPassthroughDeviceBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemPassthroughDeviceBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            ))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val device = getItem(position)
            val mgr    = passthroughManager ?: return

            with(holder.binding) {

                // ── Name + subtitle ───────────────────────────────────────────
                tvDeviceName.text = device.displayName
                tvDeviceName.setTextColor(
                    root.context.themeColor(
                        if (device.isConnected) R.attr.colorTextPrimary
                        else R.attr.colorTextSecondary
                    )
                )

                val subtitle = buildSubtitle(device)
                tvDeviceSubtitle.text       = subtitle
                tvDeviceSubtitle.visibility = if (subtitle.isNotEmpty()) View.VISIBLE else View.GONE

                // ── Selected checkbox ─────────────────────────────────────────
                checkSelected.isChecked = device.isSelected
                checkSelected.alpha     = if (device.isConnected) 1f else 0.4f

                // Whole-row tap toggles selection (only when connected).
                root.isClickable = device.isConnected
                root.isFocusable = device.isConnected
                root.setOnClickListener {
                    mgr.setDevicePrefs(
                        key         = device.key,
                        selected    = !device.isSelected,
                        autoEnable  = device.autoEnable,
                        inputDevice = preferredInputDevice,
                    )
                }

                // ── Auto-enable toggle ────────────────────────────────────────
                // Clear listener before programmatic isChecked update to avoid
                // re-entrancy during list rebind.
                switchAutoEnable.setOnCheckedChangeListener(null)
                switchAutoEnable.isChecked = device.autoEnable
                switchAutoEnable.setOnCheckedChangeListener { _, checked ->
                    mgr.setDevicePrefs(
                        key         = device.key,
                        selected    = device.isSelected,
                        autoEnable  = checked,
                        inputDevice = preferredInputDevice,
                    )
                }
            }
        }

        private fun buildSubtitle(device: PassthroughManager.OutputDevice): String {
            val ctx = context ?: return ""

            val typeLabel = when (device.info?.type) {
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER  -> null
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> ctx.getString(R.string.passthrough_device_type_wired)
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER      -> ctx.getString(R.string.passthrough_device_type_bluetooth)
                AudioDeviceInfo.TYPE_USB_HEADSET      -> ctx.getString(R.string.passthrough_device_type_usb)
                else                                  -> null
            }

            val connectedLabel = if (!device.isConnected) {
                ctx.getString(R.string.passthrough_device_not_connected)
            } else null

            return listOfNotNull(typeLabel, connectedLabel).joinToString(" · ")
        }
    }
}