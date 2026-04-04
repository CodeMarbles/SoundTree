package app.treecast.ui.waveform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Renders the background bitmaps for [WaveformStyle.Sky].
 *
 * All drawing logic that previously lived in [MultiLineWaveformView] as
 * `buildNightBitmap` / `buildDayBitmap` / `drawCloudStrip` now lives here.
 * The view simply calls [buildNight] or [buildDay] and stores the result —
 * it has no knowledge of how the scene is constructed.
 *
 * ── Tiling ────────────────────────────────────────────────────────────────────
 *
 * Both bitmaps are intentionally taller than a single line.  The view reads a
 * vertical slice of the bitmap whose Y offset is:
 *
 *   yOffset = (lineIndex * lineHeightPx) % bitmap.height
 *
 * so adjacent lines show different parts of the scene, and the image only
 * repeats after [NIGHT_HEIGHT_LINES] / [DAY_HEIGHT_LINES] lines.  Stars are
 * seam-wrapped so the repeat boundary is invisible on scroll.
 *
 * ── Tuning ────────────────────────────────────────────────────────────────────
 *
 * To redesign a single cloud strip, change the corresponding entry in
 * [CLOUD_SEEDS].  To adjust star density or size, adjust [STAR_COUNT],
 * [STAR_MIN_RADIUS_DP], or [STAR_MAX_RADIUS_DP].
 */
object SkyBackgroundRenderer {

    // ── Geometry ──────────────────────────────────────────────────────────────

    /** Bitmap height as a multiple of one line height. Non-integer → repeat cycle = 9 lines. */
    const val NIGHT_HEIGHT_LINES: Float = 4.5f

    /** Bitmap height as a multiple of one line height. */
    const val DAY_HEIGHT_LINES: Float = 4f

    // ── Night sky constants ───────────────────────────────────────────────────

    private const val STAR_SEED: Long       = 31415L
    private const val STAR_COUNT: Int       = 130
    private const val STAR_MIN_RADIUS_DP    = 0.7f
    private const val STAR_MAX_RADIUS_DP    = 2.2f

    // ── Day sky constants ─────────────────────────────────────────────────────

    /** One seed per cloud strip. Change any entry to redesign that strip. */
    private val CLOUD_SEEDS = longArrayOf(1001L, 2002L, 3003L, 4004L)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Builds the night-sky background bitmap.
     *
     * @param w       Bitmap width in pixels (should match the view width).
     * @param lineH   Height of one waveform line in pixels.
     * @param density Screen density ([DisplayMetrics.density]) for DP→PX conversion.
     * @return A [BackgroundBitmap] wrapping the rendered [Bitmap] and its tile height.
     */
    fun buildNight(w: Int, lineH: Int, density: Float): BackgroundBitmap {
        val bmpH = (lineH * NIGHT_HEIGHT_LINES).toInt()
        val bmp  = Bitmap.createBitmap(w, bmpH, Bitmap.Config.ARGB_8888)
        val c    = Canvas(bmp)

        // Sky gradient — near-black at top fading to deep navy at bottom
        c.drawRect(
            0f, 0f, w.toFloat(), bmpH.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, bmpH.toFloat(),
                    intArrayOf(0xFF060618.toInt(), 0xFF0F0F38.toInt(), 0xFF1A1A50.toInt()),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        )

        // Stars — seeded for determinism, wrapped at vertical seams
        val rng       = Random(STAR_SEED)
        val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        repeat(STAR_COUNT) {
            val x      = rng.nextFloat() * w
            val y      = rng.nextFloat() * bmpH
            val radius = (STAR_MIN_RADIUS_DP + rng.nextFloat() *
                    (STAR_MAX_RADIUS_DP - STAR_MIN_RADIUS_DP)) * density
            val alpha  = 155 + rng.nextInt(100)   // 155–254: varied brightness
            val rComp  = 235 + rng.nextInt(21)     // 235–255
            val gComp  = 235 + rng.nextInt(21)
            val bComp  = 220 + rng.nextInt(36)     // slightly cooler blue floor
            starPaint.color = Color.argb(alpha, rComp, gComp, bComp)

            c.drawCircle(x, y, radius, starPaint)
            // Seam-wrap: mirror at the bottom and top edges so the tiling
            // boundary is invisible as lines scroll into view
            if (y + radius > bmpH) c.drawCircle(x, y - bmpH, radius, starPaint)
            if (y - radius < 0f)   c.drawCircle(x, y + bmpH, radius, starPaint)
        }

        return BackgroundBitmap(bmp, NIGHT_HEIGHT_LINES)
    }

    /**
     * Builds the day-sky background bitmap.
     *
     * Four independent cloud strips are stacked vertically, one per line height.
     * Each strip is a complete composition so no cloud is ever sliced at a seam.
     *
     * @param w       Bitmap width in pixels (should match the view width).
     * @param lineH   Height of one waveform line in pixels.
     * @param density Screen density ([DisplayMetrics.density]) for DP→PX conversion.
     *                Unused for cloud geometry (all sizes are relative to lineH)
     *                but accepted for API symmetry with [buildNight].
     * @return A [BackgroundBitmap] wrapping the rendered [Bitmap] and its tile height.
     */
    @Suppress("UNUSED_PARAMETER")
    fun buildDay(w: Int, lineH: Int, density: Float): BackgroundBitmap {
        val bmpH = (lineH * DAY_HEIGHT_LINES).toInt()
        val bmp  = Bitmap.createBitmap(w, bmpH, Bitmap.Config.ARGB_8888)
        val c    = Canvas(bmp)

        CLOUD_SEEDS.forEachIndexed { i, seed ->
            c.save()
            c.translate(0f, (i * lineH).toFloat())
            drawCloudStrip(c, w, lineH, seed)
            c.restore()
        }

        return BackgroundBitmap(bmp, DAY_HEIGHT_LINES)
    }

    // ── Private drawing helpers ───────────────────────────────────────────────

    private fun drawCloudStrip(c: Canvas, w: Int, h: Int, seed: Long) {
        // Bright cerulean sky — peeks through gaps between cloud puffs
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(),
            Paint().apply { color = 0xFF4FC3F7.toInt() })

        val rng       = Random(seed)
        val puffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val oval      = RectF()

        val numClouds = 4 + rng.nextInt(2)   // 4–5 clouds per strip
        repeat(numClouds) { idx ->
            // Space centres evenly with slight jitter
            val fraction = (idx + 0.5f + (rng.nextFloat() - 0.5f) * 0.35f) / numClouds
            val cx       = fraction * w
            val cy       = h * (0.35f + rng.nextFloat() * 0.30f)
            val baseR    = h * (0.35f + rng.nextFloat() * 0.20f)
            val puffs    = 4 + rng.nextInt(3)   // 4–6 puffs per cloud

            repeat(puffs) { p ->
                val angle  = (p.toFloat() / puffs) * 2f * Math.PI.toFloat()
                val spread = baseR * (0.5f + rng.nextFloat() * 0.5f)
                val px     = cx + cos(angle.toDouble()).toFloat() * spread * 0.9f
                val py     = cy + sin(angle.toDouble()).toFloat() * spread * 0.45f
                val pr     = baseR * (0.55f + rng.nextFloat() * 0.45f)
                val shade  = 245 + rng.nextInt(11)   // 245–255: white to very pale grey
                val alpha  = 220 + rng.nextInt(35)   // 220–254: slightly translucent edges
                puffPaint.color = Color.argb(alpha, shade, shade, shade)
                oval.set(px - pr, py - pr * 0.72f, px + pr, py + pr * 0.72f)
                c.drawOval(oval, puffPaint)
            }

            // Bright white centre to unify each cluster
            puffPaint.color = Color.argb(255, 255, 255, 255)
            oval.set(cx - baseR * 0.55f, cy - baseR * 0.45f,
                cx + baseR * 0.55f, cy + baseR * 0.45f)
            c.drawOval(oval, puffPaint)
        }
    }
}