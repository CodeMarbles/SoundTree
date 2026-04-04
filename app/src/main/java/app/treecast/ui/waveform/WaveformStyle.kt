package app.treecast.ui.waveform

/**
 * Defines the visual style applied to [MultiLineWaveformView].
 *
 * The sealed hierarchy is intentionally open for new variants — future styles
 * (Fog, Paper, etc.) are added here as new subclasses. The rendering pipeline
 * checks `is WaveformStyle.Sky`, `is WaveformStyle.Standard`, etc. rather than
 * an exhaustive when-expression, so adding a new style does not require touching
 * the draw code until that style is actually implemented.
 *
 * ── Render stack (bottom to top) ─────────────────────────────────────────────
 *
 *   1. Background surface  — optional; owned by [MultiLineWaveformView].
 *                            Only rendered for non-[Standard] styles.
 *                            Taller than one line for visual variety on scroll.
 *
 *   2. Played bitmap       — waveform bars to the left of the playhead,
 *                            drawn in the accent gradient.
 *
 *   3. Unplayed bitmap     — waveform bars to the right of the playhead,
 *                            drawn in muted grey.
 *
 *   4. Ruler bitmap        — tick marks and time labels (transparent ARGB_8888).
 *
 *   5. Dynamic overlays    — mark lines/triangles, playhead line + label.
 *
 * ── Style variants ────────────────────────────────────────────────────────────
 *
 * [Standard]   — No background surface. Bars only: played = accent gradient,
 *                unplayed = muted grey. The original look.
 *
 * [Sky]        — Background surface rendered as a sky scene behind the bars.
 *                Day variant: a cloudy blue sky (light theme).
 *                Night variant: a starry dark sky (dark theme).
 *                By default the variant follows the app theme; set [invertTheme]
 *                to swap them — useful for seeing what dark skies look like in
 *                light mode and vice-versa.
 *
 * [SkyLights]  — Background surface rendered as atmospheric light phenomena.
 *                Day variant: soft, angled rainbow band crops that gently
 *                fade into one another.
 *                Night variant: flowing aurora bands in greens and purples.
 *                Like [Sky], the variant follows the app theme by default;
 *                set [invertTheme] to swap.
 *
 * ── Adding a new style ────────────────────────────────────────────────────────
 *
 *   1. Add a new subclass here (object or data class as appropriate).
 *   2. Add PREF handling and a Settings toggle in [MainViewModel] /
 *      [SettingsFragment] / [fragment_settings.xml].
 *   3. Add a renderer in a new <StyleName>BackgroundRenderer.kt file and
 *      wire it into [MultiLineWaveformView.rebuildBackgroundBitmap].
 *   4. Optionally adjust bar colours in [WaveformLineView.rebuildUnplayedBitmap]
 *      if the new style looks better with a different unplayed bar treatment.
 */
sealed class WaveformStyle {

    // ── Standard ──────────────────────────────────────────────────────────────

    /** Plain rendering — no stylized background.  The default. */
    data object Standard : WaveformStyle()

    // ── Sky ───────────────────────────────────────────────────────────────────

    /**
     * Sky-themed background rendered behind the waveform bars.
     *
     * Day variant (light theme or [invertTheme] = true + dark theme):
     *   A cloudy blue sky — the "waking" side of the playhead.
     *
     * Night variant (dark theme or [invertTheme] = true + light theme):
     *   A starry dark sky — the "dreaming" side of the playhead.
     *
     * Rendered by [SkyBackgroundRenderer].
     *
     * @param invertTheme
     *   When false (default): light app theme → day sky, dark → night sky.
     *   When true: the association is swapped.
     */
    data class Sky(val invertTheme: Boolean = false) : WaveformStyle()

    // ── SkyLights ─────────────────────────────────────────────────────────────

    /**
     * Atmospheric light phenomena rendered behind the waveform bars.
     *
     * Day variant (light theme or [invertTheme] = true + dark theme):
     *   Close-cropped rainbow bands at varying angles, soft-edged and
     *   irregularly spaced, fading gently into one another — less a
     *   classic arch and more a sensory impression of prismatic light.
     *
     * Night variant (dark theme or [invertTheme] = true + light theme):
     *   Flowing aurora bands in greens and purples drifting across a
     *   deep dark sky.
     *
     * Rendered by [SkyLightsBackgroundRenderer].
     *
     * @param invertTheme
     *   When false (default): light app theme → rainbow, dark → aurora.
     *   When true: the association is swapped.
     */
    data class SkyLights(val invertTheme: Boolean = false) : WaveformStyle()

    // ── Future variants (reserved) ────────────────────────────────────────────
    //
    // object Fog     : WaveformStyle()   // soft diffuse gradient
    // object Paper   : WaveformStyle()   // warm parchment texture
}