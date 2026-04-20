package app.soundtree.ui.workspace

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Draws a classic seven-colour rainbow arc positioned in the upper portion of
 * the view — roughly where a face sits in a portrait photograph.
 *
 * The arc is a semicircle centred at (width/2, arcCentreY) with its opening
 * facing downward, so it rises into the frame like a real rainbow.  Each band
 * is drawn as a filled arc sweep, outermost (red) first, inning inward to
 * violet.  A soft sky-gradient background and subtle ground fill complete the
 * scene.
 */
class RainbowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        // Rainbow bands from outermost to innermost
        private val BAND_COLORS = intArrayOf(
            Color.parseColor("#FF0000"), // Red
            Color.parseColor("#FF7700"), // Orange
            Color.parseColor("#FFEE00"), // Yellow
            Color.parseColor("#00CC44"), // Green
            Color.parseColor("#1166FF"), // Blue
            Color.parseColor("#4B0082"), // Indigo
            Color.parseColor("#8F00FF")  // Violet
        )
        private const val BAND_COUNT   = 7
        private const val SWEEP_ANGLE  = 180f   // full semicircle
        private const val START_ANGLE  = 180f   // start from left (9 o'clock), sweep clockwise
    }

    private val arcPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val skyPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ovalRect  = RectF()
    private val isNightMode: Boolean
        get() = (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // ── Sky background ────────────────────────────────────────────────
        skyPaint.color = Color.parseColor(if (isNightMode) "#0D1B2A" else "#D6EEFF")
        canvas.drawRect(0f, 0f, w, h * 0.65f, skyPaint)

        // ── Ground ────────────────────────────────────────────────────────
        skyPaint.color = Color.parseColor(if (isNightMode) "#0D1A10" else "#E8F5E9")
        canvas.drawRect(0f, h * 0.65f, w, h, skyPaint)

        // ── Rainbow arc ───────────────────────────────────────────────
        // Arc centre is at 55 % down from the top (face-position baseline).
        // Outer radius = 46 % of width; each band is bandWidth dp thick.
        val centreX    = w * 0.5f
        val centreY    = h * 0.55f          // slightly below vertical midpoint
        val outerR     = w * 0.46f
        val bandWidth  = outerR / (BAND_COUNT + 1.5f)   // leave a white core gap

        for (i in 0 until BAND_COUNT) {
            val r = outerR - i * bandWidth
            ovalRect.set(centreX - r, centreY - r, centreX + r, centreY + r)
            arcPaint.color = BAND_COLORS[i]
            canvas.drawArc(ovalRect, START_ANGLE, SWEEP_ANGLE, true, arcPaint)
        }

        // ── White "inner sky" fill to punch out the core of the arcs ──
        // Draws a filled semicircle in the sky colour to erase the arc centres,
        // leaving only the outer rainbow bands visible as a proper arch.
        val innerR = outerR - BAND_COUNT * bandWidth
        ovalRect.set(centreX - innerR, centreY - innerR, centreX + innerR, centreY + innerR)
        skyPaint.color = Color.parseColor(if (isNightMode) "#0D1B2A" else "#D6EEFF")
        canvas.drawArc(ovalRect, START_ANGLE, SWEEP_ANGLE, true, skyPaint)

        // ── Ground rect over the lower half of the arc ────────────────
        // Clips away the bottom half of each arc/filled-pie so only the
        // arch above the horizon shows.
        skyPaint.color = Color.parseColor(if (isNightMode) "#0D1A10" else "#E8F5E9")
        canvas.drawRect(0f, centreY, w, h, skyPaint)
    }
}