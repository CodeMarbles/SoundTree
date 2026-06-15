package app.soundtree.ui.settings

import android.view.View
import app.soundtree.R
import app.soundtree.ui.getNearEndDurationThresholdSecs
import app.soundtree.ui.getNearEndEnabled
import app.soundtree.ui.getNearEndLongPct
import app.soundtree.ui.getNearEndShortSecs
import app.soundtree.ui.getRememberLongThresholdSecs
import app.soundtree.ui.setAutoNavigateToListen
import app.soundtree.ui.setJumpToLibraryOnSave
import app.soundtree.ui.setMarkRewindThresholdSecs
import app.soundtree.ui.setNearEndDurationThresholdSecs
import app.soundtree.ui.setNearEndEnabled
import app.soundtree.ui.setNearEndLongPct
import app.soundtree.ui.setNearEndShortSecs
import app.soundtree.ui.setRememberLongThresholdSecs
import app.soundtree.ui.setRememberPositionMode
import app.soundtree.ui.setScrubBackSecs
import app.soundtree.ui.setScrubForwardSecs
import app.soundtree.util.PlaybackPositionHelper
import app.soundtree.util.themeColor

// ── Playback memory ───────────────────────────────────────────────────────────

internal fun SettingsFragment.setupPlaybackMemory() {
    // ── Mode picker ───────────────────────────────────────────────────────────
    val btnAlways    = binding.groupPlaybackMemory.btnRememberAlways
    val btnLongOnly  = binding.groupPlaybackMemory.btnRememberLongOnly
    val btnNever     = binding.groupPlaybackMemory.btnRememberNever
    val rowThreshold = binding.groupPlaybackMemory.rowRememberLongThreshold
    val tvThreshold  = binding.groupPlaybackMemory.tvRememberThresholdValue

    fun highlightMode(mode: String) {
        listOf(
            btnAlways   to PlaybackPositionHelper.MODE_ALWAYS,
            btnLongOnly to PlaybackPositionHelper.MODE_LONG_ONLY,
            btnNever    to PlaybackPositionHelper.MODE_NEVER,
        ).forEach { (btn, m) ->
            val isActive = m == mode
            btn.background = if (isActive) {
                android.graphics.drawable.GradientDrawable().apply {
                    shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = resources.getDimension(R.dimen.settings_card_corner_radius) -
                            resources.displayMetrics.density * 3f
                    setColor(requireContext().themeColor(R.attr.colorSurfaceElevated))
                }
            } else null
            btn.setTextColor(
                requireContext().themeColor(
                    if (isActive) R.attr.colorTextPrimary else R.attr.colorTextSecondary
                )
            )
        }
        rowThreshold.visibility = if (mode == PlaybackPositionHelper.MODE_LONG_ONLY) View.VISIBLE else View.GONE
    }

    val initialMode = viewModel.rememberPositionMode.value
    highlightMode(initialMode)

    btnAlways.setOnClickListener   { viewModel.setRememberPositionMode(PlaybackPositionHelper.MODE_ALWAYS);    highlightMode(PlaybackPositionHelper.MODE_ALWAYS) }
    btnLongOnly.setOnClickListener { viewModel.setRememberPositionMode(PlaybackPositionHelper.MODE_LONG_ONLY); highlightMode(PlaybackPositionHelper.MODE_LONG_ONLY) }
    btnNever.setOnClickListener    { viewModel.setRememberPositionMode(PlaybackPositionHelper.MODE_NEVER);     highlightMode(PlaybackPositionHelper.MODE_NEVER) }

    // ── Long threshold stepper ────────────────────────────────────────────────
    fun renderThreshold() {
        val secs = viewModel.getRememberLongThresholdSecs()
        tvThreshold.text = if (secs % 60 == 0) "${secs / 60} min" else "${secs}s"
    }
    renderThreshold()
    binding.groupPlaybackMemory.btnRememberThresholdDown.setOnClickListener {
        val current = viewModel.getRememberLongThresholdSecs()
        viewModel.setRememberLongThresholdSecs((current - 60).coerceAtLeast(60))
        renderThreshold()
    }
    binding.groupPlaybackMemory.btnRememberThresholdUp.setOnClickListener {
        val current = viewModel.getRememberLongThresholdSecs()
        viewModel.setRememberLongThresholdSecs(current + 60)
        renderThreshold()
    }

    // ── Near-End Reset ────────────────────────────────────────────────────────
    val switchNearEnd       = binding.groupPlaybackMemory.switchNearEndEnabled
    val rowShort            = binding.groupPlaybackMemory.rowNearEndShort
    val rowLong             = binding.groupPlaybackMemory.rowNearEndLong
    val rowNearEndThreshold = binding.groupPlaybackMemory.rowNearEndThreshold
    val tvShort             = binding.groupPlaybackMemory.tvNearEndShortValue
    val tvShortDesc         = binding.groupPlaybackMemory.tvNearEndShortDesc
    val tvLong              = binding.groupPlaybackMemory.tvNearEndLongValue

    /** Alpha applied to the stepper rows when the master toggle is off. */
    val DISABLED_ALPHA = 0.38f

    fun renderNearEndSection() {
        val enabled = viewModel.getNearEndEnabled()

        // Sync the switch without triggering its listener.
        switchNearEnd.setOnCheckedChangeListener(null)
        switchNearEnd.isChecked = enabled
        switchNearEnd.setOnCheckedChangeListener { _, checked ->
            viewModel.setNearEndEnabled(checked)
            renderNearEndSection()
        }

        // Dim and block interaction on the three stepper rows when disabled.
        val alpha = if (enabled) 1f else DISABLED_ALPHA
        rowShort.alpha            = alpha; rowShort.isEnabled            = enabled
        rowLong.alpha             = alpha; rowLong.isEnabled             = enabled
        rowNearEndThreshold.alpha = alpha; rowNearEndThreshold.isEnabled = enabled
        // Propagate enabled state to children so the +/− buttons don't fire.
        setChildrenEnabled(rowShort,            enabled)
        setChildrenEnabled(rowLong,             enabled)
        setChildrenEnabled(rowNearEndThreshold, enabled)
    }

    fun renderNearEndShort() {
        val secs = viewModel.getNearEndShortSecs()
        tvShort.text = "${secs}s"
        val thresholdMins = viewModel.getNearEndDurationThresholdSecs() / 60
        tvShortDesc.text = getString(R.string.settings_playback_near_end_short_desc, thresholdMins)
    }
    fun renderNearEndLong() { tvLong.text = "${viewModel.getNearEndLongPct()}%" }

    renderNearEndSection()
    renderNearEndShort()
    renderNearEndLong()

    binding.groupPlaybackMemory.btnNearEndShortDown.setOnClickListener {
        viewModel.setNearEndShortSecs(viewModel.getNearEndShortSecs() - 5)
        renderNearEndShort()
    }
    binding.groupPlaybackMemory.btnNearEndShortUp.setOnClickListener {
        viewModel.setNearEndShortSecs(viewModel.getNearEndShortSecs() + 5)
        renderNearEndShort()
    }
    binding.groupPlaybackMemory.btnNearEndLongDown.setOnClickListener {
        viewModel.setNearEndLongPct(viewModel.getNearEndLongPct() - 1)
        renderNearEndLong()
    }
    binding.groupPlaybackMemory.btnNearEndLongUp.setOnClickListener {
        viewModel.setNearEndLongPct(viewModel.getNearEndLongPct() + 1)
        renderNearEndLong()
    }

    // ── Duration threshold stepper ────────────────────────────────────────────
    val tvDurThresh = binding.groupPlaybackMemory.tvNearEndThresholdValue
    fun renderDurThreshold() {
        val mins = viewModel.getNearEndDurationThresholdSecs() / 60
        tvDurThresh.text = "${mins} min"
    }
    renderDurThreshold()
    binding.groupPlaybackMemory.btnNearEndThresholdDown.setOnClickListener {
        val current = viewModel.getNearEndDurationThresholdSecs()
        viewModel.setNearEndDurationThresholdSecs((current - 60).coerceAtLeast(60))
        renderDurThreshold()
        renderNearEndShort()  // description references this value
    }
    binding.groupPlaybackMemory.btnNearEndThresholdUp.setOnClickListener {
        val current = viewModel.getNearEndDurationThresholdSecs()
        viewModel.setNearEndDurationThresholdSecs(current + 60)
        renderDurThreshold()
        renderNearEndShort()
    }
}

// ── Navigation controls & playback settings ───────────────────────────────────

internal fun SettingsFragment.setupPlaybackSettings() {
    // ── Switch to Listen on play ──────────────────────────────────────────────
    binding.groupNavigationControls.switchAutoNavigate.isChecked = viewModel.autoNavigateToListen.value
    binding.groupNavigationControls.switchAutoNavigate.setOnCheckedChangeListener { _, isChecked ->
        viewModel.setAutoNavigateToListen(isChecked)
    }

    // ── Jump to Library on save ───────────────────────────────────────────────
    binding.groupNavigationControls.switchJumpToLibrary.isChecked = viewModel.jumpToLibraryOnSave.value
    binding.groupNavigationControls.switchJumpToLibrary.setOnCheckedChangeListener { _, isChecked ->
        viewModel.setJumpToLibraryOnSave(isChecked)
    }

    // ── Scrub Back ────────────────────────────────────────────────────────────
    binding.groupNavigationControls.tvScrubBackSecs.text = viewModel.scrubBackSecs.value.toString()
    binding.groupNavigationControls.btnScrubBackMinus.setOnClickListener {
        val newVal = (viewModel.scrubBackSecs.value - 5).coerceAtLeast(5)
        viewModel.setScrubBackSecs(newVal)
        binding.groupNavigationControls.tvScrubBackSecs.text = newVal.toString()
    }
    binding.groupNavigationControls.btnScrubBackPlus.setOnClickListener {
        val newVal = viewModel.scrubBackSecs.value + 5
        viewModel.setScrubBackSecs(newVal)
        binding.groupNavigationControls.tvScrubBackSecs.text = newVal.toString()
    }

    // ── Scrub Forward ─────────────────────────────────────────────────────────
    binding.groupNavigationControls.tvScrubForwardSecs.text = viewModel.scrubForwardSecs.value.toString()
    binding.groupNavigationControls.btnScrubForwardMinus.setOnClickListener {
        val newVal = (viewModel.scrubForwardSecs.value - 5).coerceAtLeast(5)
        viewModel.setScrubForwardSecs(newVal)
        binding.groupNavigationControls.tvScrubForwardSecs.text = newVal.toString()
    }
    binding.groupNavigationControls.btnScrubForwardPlus.setOnClickListener {
        val newVal = viewModel.scrubForwardSecs.value + 5
        viewModel.setScrubForwardSecs(newVal)
        binding.groupNavigationControls.tvScrubForwardSecs.text = newVal.toString()
    }

    // ── Mark Rewind Threshold ─────────────────────────────────────────────────
    fun Float.toDisplayString() =
        if (this == this.toLong().toFloat()) "${this.toInt()}s" else "${this}s"

    binding.groupNavigationControls.tvMarkRewindSecs.text = viewModel.markRewindThresholdSecs.value.toDisplayString()
    binding.groupNavigationControls.btnMarkRewindMinus.setOnClickListener {
        val newVal = (viewModel.markRewindThresholdSecs.value - 0.5f).coerceAtLeast(0.5f)
        viewModel.setMarkRewindThresholdSecs(newVal)
        binding.groupNavigationControls.tvMarkRewindSecs.text = newVal.toDisplayString()
    }
    binding.groupNavigationControls.btnMarkRewindPlus.setOnClickListener {
        val newVal = (viewModel.markRewindThresholdSecs.value + 0.5f).coerceAtMost(5.0f)
        viewModel.setMarkRewindThresholdSecs(newVal)
        binding.groupNavigationControls.tvMarkRewindSecs.text = newVal.toDisplayString()
    }
}