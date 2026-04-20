package app.soundtree.ui

import android.graphics.drawable.GradientDrawable

// ─────────────────────────────────────────────────────────────────────────────
// MainActivity_Helpers.kt
//
// Shared utility extension functions on MainActivity
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Builds a pill-shaped [GradientDrawable] with the given fill and stroke.
 * A cornerRadius of 999dp produces true semicircular ends at any height.
 */
internal fun MainActivity.pillBackground(fillColor: Int, strokeColor: Int): GradientDrawable {
    val dp = resources.displayMetrics.density
    return GradientDrawable().apply {
        shape        = GradientDrawable.RECTANGLE
        cornerRadius = 999f * dp
        setColor(fillColor)
        setStroke((1.5f * dp).toInt(), strokeColor)
    }
}

/**
 * Builds a solid pill-shaped [GradientDrawable] with no stroke.
 * Used for the minimize buttons on each mini widget.
 */
internal fun MainActivity.solidPillBackground(fillColor: Int): GradientDrawable {
    val dp = resources.displayMetrics.density
    return GradientDrawable().apply {
        shape        = GradientDrawable.RECTANGLE
        cornerRadius = 999f * dp
        setColor(fillColor)
    }
}

/**
 * Formats a millisecond duration as "m:ss" (e.g. "3:07").
 * Used by the mini player and mini recorder elapsed/position displays.
 */
internal fun MainActivity.formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}