package app.treecast.ui.waveform

/**
 * Draw-time display parameters for [MultiLineWaveformView]'s background decoration.
 *
 * These settings control *how* the background bitmap is composited onto each
 * waveform line at draw time.  Changing any of them does NOT require rebuilding
 * the background bitmap — only [MultiLineWaveformView.invalidateItemDecorations]
 * is needed, which happens automatically when the view's property setter is called.
 *
 * Contrast this with [WaveformStyle], which describes *what* to draw and whose
 * changes do trigger a full bitmap rebuild.
 *
 * ── Properties ────────────────────────────────────────────────────────────────
 *
 * [backgroundAlpha]
 *   Opacity of the background scene, in the range 0f (invisible) to 1f (opaque).
 *   Stored as a float in SharedPreferences; the Settings slider maps 0–100 → 0f–1f.
 *   Default (≈ 0.51f) matches the previous hard-coded value of 130/255.
 *
 * [extendsUnderRuler]
 *   When true, the background is drawn behind the ruler strip at the top of each
 *   line as well as behind the waveform bars.  When false, the background starts
 *   below the ruler, leaving it visually clean against the surface colour.
 *
 * [unplayedOnly]
 *   When true, the background is clipped to the unplayed region of each line —
 *   the area to the right of the playhead.  The scene retreats as the playhead
 *   advances, giving a "dreams dissolving into reality" transition effect.
 *   When false, the background fills the whole line regardless of playback position.
 */
data class WaveformDisplayConfig(
    val backgroundAlpha: Float   = DEFAULT_BACKGROUND_ALPHA,
    val extendsUnderRuler: Boolean = DEFAULT_EXTENDS_UNDER_RULER,
    val unplayedOnly: Boolean      = DEFAULT_UNPLAYED_ONLY,
) {
    companion object {
        /**
         * Matches the previous hard-coded constant [MultiLineWaveformView.BACKGROUND_ALPHA]
         * of 130/255 ≈ 0.51f, so the default experience is unchanged after migration.
         */
        const val DEFAULT_BACKGROUND_ALPHA: Float    = 130f / 255f   // ≈ 0.51f
        const val DEFAULT_EXTENDS_UNDER_RULER: Boolean = true
        const val DEFAULT_UNPLAYED_ONLY: Boolean       = true
    }
}