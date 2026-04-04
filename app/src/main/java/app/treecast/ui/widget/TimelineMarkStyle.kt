package app.treecast.ui.widget

/**
 * Shared mark-line sizing constants for the Mini Player and Mini Recorder timelines.
 *
 * Both [MiniPlayerTimelineView] and [MiniRecorderTimelineView] draw identical
 * mark lines. All sizing lives here so changes to thickness or height only
 * need to be made in one place.
 *
 * Usage:
 *   val mark = TimelineMarkStyle.compute(density, barH)
 *   markRect.set(cx - mark.halfW, barCy - mark.halfH, cx + mark.halfW, barCy + mark.halfH)
 *   canvas.drawRoundRect(markRect, mark.corner, mark.corner, paint)
 */
object TimelineMarkStyle {

    /** Thickness of a mark line in dp. */
    const val WIDTH_DP = 3f

    /**
     * Mark line height expressed as a multiple of the timeline bar height.
     * A value of 2.5 means the line extends 2.5× barH above and below centre,
     * making the total mark height 5× the bar height.
     */
    const val HALF_HEIGHT_MULTIPLIER = 2.5f

    /** Pre-computed dimensions ready to use in onDraw. */
    data class Dims(
        /** Half the line width — use as ± offset from centre X. */
        val halfW: Float,
        /** Half the line height — use as ± offset from bar centre Y. */
        val halfH: Float,
        /** Corner radius for drawRoundRect — produces fully rounded caps. */
        val corner: Float,
    )

    /**
     * Compute mark dimensions for a given display density and bar height.
     * Call once per onDraw, after [barH] is known.
     *
     * @param density  [DisplayMetrics.density] from the view's resources.
     * @param barH     Height of the timeline bar in pixels.
     */
    fun compute(density: Float, barH: Float): Dims {
        val halfW = density * WIDTH_DP / 2f
        val halfH = barH * HALF_HEIGHT_MULTIPLIER
        return Dims(halfW = halfW, halfH = halfH, corner = halfW)
    }
}