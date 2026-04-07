package app.treecast.ui.library

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * A [Drawable] that fills its bounds with two solid colours split at a
 * horizontal fraction, producing a clean left/right "progress bar" effect
 * suitable for use as a RecyclerView item background.
 *
 * - The *played* colour occupies [0, fraction * width].
 * - The *unplayed* colour occupies [fraction * width, width].
 * - When [fraction] is 0f the entire background is [unplayedColor].
 * - When [fraction] is 1f the entire background is [playedColor].
 *
 * Call [setFraction] to update the split position in real time; the drawable
 * calls [invalidateSelf] automatically so attached views repaint immediately.
 *
 * Thread-safety: must be called from the main thread (same requirement as all
 * Drawable mutation).
 */
class SplitBackgroundDrawable(
    playedColor:   Int,
    unplayedColor: Int,
    fraction:      Float = 0f,
) : Drawable() {

    private val playedPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = playedColor }
    private val unplayedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = unplayedColor }

    private var _fraction: Float = fraction.coerceIn(0f, 1f)

    var playedColor: Int
        get() = playedPaint.color
        set(value) { playedPaint.color = value; invalidateSelf() }

    var unplayedColor: Int
        get() = unplayedPaint.color
        set(value) { unplayedPaint.color = value; invalidateSelf() }

    /** Current split position in [0f, 1f]. */
    val fraction: Float get() = _fraction

    /**
     * Update the split position. Clamps to [0f, 1f].
     * Calls [invalidateSelf] only when the value actually changes, so it is
     * safe to call on every progress tick without causing unnecessary redraws.
     */
    fun setFraction(newFraction: Float) {
        val clamped = newFraction.coerceIn(0f, 1f)
        if (clamped == _fraction) return
        _fraction = clamped
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val split = w * _fraction

        if (split > 0f) {
            canvas.drawRect(0f, 0f, split, h, playedPaint)
        }
        if (split < w) {
            canvas.drawRect(split, 0f, w, h, unplayedPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        playedPaint.alpha   = alpha
        unplayedPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        playedPaint.colorFilter   = colorFilter
        unplayedPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}