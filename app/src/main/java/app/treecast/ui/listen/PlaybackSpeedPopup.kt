package app.treecast.ui.listen

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import app.treecast.R
import app.treecast.ui.MainViewModel
import kotlin.math.roundToInt

/**
 * Manages the floating playback speed picker popup.
 *
 * The popup appears above [anchor] and dismisses on outside touch or when
 * the caller invokes [dismiss] / [toggle].
 *
 * Usage in ListenFragment:
 *
 *   private lateinit var speedPopup: PlaybackSpeedPopup
 *
 *   // In setupTransportControls():
 *   speedPopup = PlaybackSpeedPopup(requireContext(), binding.btnPlaybackSpeed) { speed ->
 *       viewModel.setPlaybackSpeed(speed)
 *   }
 *   binding.btnPlaybackSpeed.setOnClickListener {
 *       speedPopup.toggle(viewModel.playbackSpeed.value)
 *   }
 *
 *   // In the playbackSpeed StateFlow collector:
 *   viewModel.playbackSpeed.collect { speed ->
 *       binding.btnPlaybackSpeed.text = formatSpeed(speed)
 *       speedPopup.syncSpeed(speed)       // keep popup in sync if open
 *   }
 *
 * @param context       Fragment/Activity context — needed for inflation and
 *                      theme colour resolution.
 * @param anchor        The view the popup is anchored above (btnPlaybackSpeed).
 * @param onSpeedChanged Callback fired immediately on every speed change
 *                      (preset tap, slider drag, or ± step). Routes to
 *                      [MainViewModel.setPlaybackSpeed].
 */
class PlaybackSpeedPopup(
    private val context: Context,
    private val anchor: View,
    private val onSpeedChanged: (Float) -> Unit
) {

    private var window: PopupWindow? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /** Show if hidden, dismiss if visible. */
    fun toggle(currentSpeed: Float) {
        if (window?.isShowing == true) dismiss() else show(currentSpeed)
    }

    /** Dismiss the popup. Safe to call when already hidden. */
    fun dismiss() {
        window?.dismiss()
        window = null
    }

    val isShowing: Boolean get() = window?.isShowing == true

    /**
     * Synchronise the popup's preset highlights and slider position to [speed].
     * Call this from the fragment's [MainViewModel.playbackSpeed] collector
     * so the popup stays consistent even while it is open.
     * No-op if the popup is not currently visible.
     */
    fun syncSpeed(speed: Float) {
        val content = window?.contentView ?: return
        updatePresetHighlights(content, speed)
        content.findViewById<Slider>(R.id.sliderSpeed).value =
            speed.coerceIn(MainViewModel.SPEED_MIN, MainViewModel.SPEED_MAX)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun show(currentSpeed: Float) {
        val content = LayoutInflater.from(context)
            .inflate(R.layout.popup_playback_speed, anchor.parent as? ViewGroup, false)

        setupPresets(content, currentSpeed)
        setupSlider(content, currentSpeed)

        val pw = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            /* focusable = */ true  // enables outside-touch dismiss automatically
        ).apply {
            // Transparent wrapper — the card background comes from the layout drawable
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            elevation = 12f * context.resources.displayMetrics.density
            isOutsideTouchable = true
        }

        // Measure the content so we can calculate the upward offset before showing.
        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupH  = content.measuredHeight
        val anchorH = anchor.height
        val gapDp   = (6 * context.resources.displayMetrics.density).toInt()
        val yOff    = -(popupH + anchorH + gapDp)

        // xOff = 0: left edge of popup aligns with left edge of anchor.
        pw.showAsDropDown(anchor, 0, yOff)
        window = pw
    }

    // ── Preset row ────────────────────────────────────────────────────────────

    private val presetIdSpeedPairs = listOf(
        R.id.btnSpeed075 to 0.75f,
        R.id.btnSpeed100 to 1.00f,
        R.id.btnSpeed125 to 1.25f,
        R.id.btnSpeed150 to 1.50f,
        R.id.btnSpeed200 to 2.00f
    )

    private fun setupPresets(root: View, currentSpeed: Float) {
        presetIdSpeedPairs.forEach { (id, speed) ->
            root.findViewById<MaterialButton>(id).setOnClickListener {
                onSpeedChanged(speed)
                dismiss()
            }
        }
        updatePresetHighlights(root, currentSpeed)
    }

    private fun updatePresetHighlights(root: View, currentSpeed: Float) {
        val accentColor    = resolveColor(R.attr.colorAccent)
        val secondaryColor = resolveColor(R.attr.colorTextSecondary)

        presetIdSpeedPairs.forEach { (id, speed) ->
            val btn      = root.findViewById<MaterialButton>(id)
            val isActive = approxEqual(currentSpeed, speed)
            btn.setTextColor(if (isActive) accentColor else secondaryColor)
            // Active preset gets slightly larger text to act as the visual anchor.
            btn.textSize = if (isActive) 14f else 12f
        }
    }

    // ── Slider row ────────────────────────────────────────────────────────────

    private fun setupSlider(root: View, currentSpeed: Float) {
        val slider = root.findViewById<Slider>(R.id.sliderSpeed).apply {
            valueFrom = MainViewModel.SPEED_MIN
            valueTo   = MainViewModel.SPEED_MAX
            stepSize  = MainViewModel.SPEED_STEP
            value     = currentSpeed.coerceIn(MainViewModel.SPEED_MIN, MainViewModel.SPEED_MAX)
            // Accessibility description
            contentDescription = context.getString(R.string.listen_cd_speed_slider)
        }

        root.findViewById<MaterialButton>(R.id.btnSpeedMinus).setOnClickListener {
            val next = snap((slider.value - MainViewModel.SPEED_STEP)
                .coerceAtLeast(MainViewModel.SPEED_MIN))
            slider.value = next
            onSpeedChanged(next)
        }

        root.findViewById<MaterialButton>(R.id.btnSpeedPlus).setOnClickListener {
            val next = snap((slider.value + MainViewModel.SPEED_STEP)
                .coerceAtMost(MainViewModel.SPEED_MAX))
            slider.value = next
            onSpeedChanged(next)
        }

        // fromUser guard: ignore programmatic value changes (e.g. from syncSpeed())
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) onSpeedChanged(snap(value))
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /** Round [value] to the nearest SPEED_STEP to eliminate floating-point drift. */
    private fun snap(value: Float): Float =
        ((value / MainViewModel.SPEED_STEP).roundToInt() * MainViewModel.SPEED_STEP)
            .coerceIn(MainViewModel.SPEED_MIN, MainViewModel.SPEED_MAX)

    /** True when two speeds are within half a step of each other. */
    private fun approxEqual(a: Float, b: Float): Boolean =
        kotlin.math.abs(a - b) < MainViewModel.SPEED_STEP / 2f

    /** Resolve a theme colour attribute to its ARGB int. */
    private fun resolveColor(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}