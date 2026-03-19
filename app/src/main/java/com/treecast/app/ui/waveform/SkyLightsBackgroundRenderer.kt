package com.treecast.app.ui.waveform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.random.Random

/**
 * Renders the background bitmaps for [WaveformStyle.SkyLights].
 *
 * ── Aurora (night) ────────────────────────────────────────────────────────────
 *
 * A deep dark sky with softly glowing aurora bands drifting across it.
 * Rendered in layers: wide diffuse underlayers first for atmospheric depth,
 * then narrower bright bands on top for the characteristic ribbon quality.
 * Colors draw from the natural aurora palette — spring greens, teals, mints,
 * and purples — each band a single hue that fades to transparent at its edges.
 *
 * ── Rainbow crops (day) ───────────────────────────────────────────────────────
 *
 * A pale luminous sky crossed by angled ROYGBIV bands — not full arches, but
 * close-cropped impressions of prismatic light at varying angles. Each band
 * sweeps the full visible spectrum across its thickness and fades to transparent
 * at both edges, so adjacent bands bleed softly into one another.
 *
 * ── Tiling ────────────────────────────────────────────────────────────────────
 *
 * Both bitmaps are taller than a single line; the view tiles them using:
 *
 *   yOffset = (lineIndex * lineHeightPx) % bitmap.height
 *
 * All bands fade to transparent at their vertical edges, so the tile boundary
 * is invisible in practice — no explicit seam-wrapping is needed.
 *
 * ── Tuning ────────────────────────────────────────────────────────────────────
 *
 * [AURORA_SEED] / [RAINBOW_SEED]        — change to regenerate the scene layout.
 * [AURORA_GLOW_BANDS] / [AURORA_BRIGHT_BANDS] — layer counts for aurora.
 * [RAINBOW_BAND_COUNT]                  — crossing band count for the day scene.
 */
object SkyLightsBackgroundRenderer {

    // ── Geometry ──────────────────────────────────────────────────────────────

    /** Non-integer → repeat tile cycle = 11 lines, minimising visible repetition. */
    const val AURORA_HEIGHT_LINES: Float  = 5.5f

    /** 5 lines gives enough vertical space for varied band placement. */
    const val RAINBOW_HEIGHT_LINES: Float = 5f

    // ── Aurora constants ──────────────────────────────────────────────────────

    private const val AURORA_SEED: Long   = 27182L
    private const val AURORA_GLOW_BANDS  = 3   // wide diffuse underlayers
    private const val AURORA_BRIGHT_BANDS = 4  // narrow vivid ribbons on top

    /** Aurora color palette as (R, G, B) triples. */
    private val AURORA_PALETTE = arrayOf(
        intArrayOf(0x00, 0xFF, 0x88),   // spring green
        intArrayOf(0x00, 0xDD, 0xAA),   // teal
        intArrayOf(0x44, 0xFF, 0xCC),   // cyan-mint
        intArrayOf(0x00, 0xFF, 0xCC),   // pure mint
        intArrayOf(0x88, 0x44, 0xFF),   // violet
        intArrayOf(0xCC, 0x44, 0xFF),   // purple
    )

    // ── Rainbow constants ─────────────────────────────────────────────────────

    private const val RAINBOW_SEED: Long  = 16180L
    private const val RAINBOW_BAND_COUNT = 5

    /**
     * ROYGBIV color stops as (R, G, B) triples.
     * Slightly de-saturated from pure primaries so the bands read as natural
     * light rather than neon.
     */
    private val ROYGBIV = arrayOf(
        intArrayOf(255,  20,   0),   // Red
        intArrayOf(255, 130,   0),   // Orange
        intArrayOf(255, 230,   0),   // Yellow
        intArrayOf(  0, 210,  60),   // Green
        intArrayOf( 10,  80, 240),   // Blue
        intArrayOf( 60,   0, 200),   // Indigo
        intArrayOf(180,   0, 255),   // Violet
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Builds the aurora night background bitmap.
     *
     * @param w       Bitmap width in pixels (should match the view width).
     * @param lineH   Height of one waveform line in pixels.
     * @param density Screen density for DP→PX conversion (unused here; accepted
     *                for API symmetry with other renderers and future use).
     * @return A [BackgroundBitmap] wrapping the rendered scene and its tile height.
     */
    @Suppress("UNUSED_PARAMETER")
    fun buildAurora(w: Int, lineH: Int, density: Float): BackgroundBitmap {
        val bmpH = (lineH * AURORA_HEIGHT_LINES).toInt()
        val bmp  = Bitmap.createBitmap(w, bmpH, Bitmap.Config.ARGB_8888)
        val c    = Canvas(bmp)

        // Deep dark sky: near-black with a faint blue-teal tint
        c.drawRect(0f, 0f, w.toFloat(), bmpH.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, bmpH.toFloat(),
                    intArrayOf(0xFF01080C.toInt(), 0xFF010D12.toInt(), 0xFF021018.toInt()),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        )

        val rng = Random(AURORA_SEED)

        // ── Pass 1: wide diffuse glow bands (atmospheric depth) ───────────────
        // Broad and faint — creates the sense of the sky being lit even between
        // the brighter ribbons.
        repeat(AURORA_GLOW_BANDS) { i ->
            val (r, g, b) = AURORA_PALETTE[rng.nextInt(AURORA_PALETTE.size)]
            val centerY   = ((i + 0.5f + (rng.nextFloat() - 0.5f) * 0.4f) / AURORA_GLOW_BANDS) * bmpH
            val halfThick = lineH * (0.45f + rng.nextFloat() * 0.30f)   // 45–75% of lineH
            val angleDeg  = (rng.nextFloat() - 0.5f) * 8f               // gentle ±4°
            val coreAlpha = 55 + rng.nextInt(35)                         // 55–89: quite faint
            drawAuroraBand(c, w, centerY, halfThick, angleDeg, r, g, b, coreAlpha)
        }

        // ── Pass 2: narrower vivid ribbons ────────────────────────────────────
        // Sit atop the glow layers; give the aurora its characteristic ribbon look.
        repeat(AURORA_BRIGHT_BANDS) { i ->
            val (r, g, b) = AURORA_PALETTE[rng.nextInt(AURORA_PALETTE.size)]
            val centerY   = ((i + 0.3f + rng.nextFloat() * 0.4f) / AURORA_BRIGHT_BANDS) * bmpH
            val halfThick = lineH * (0.12f + rng.nextFloat() * 0.13f)   // 12–25% of lineH
            val angleDeg  = (rng.nextFloat() - 0.5f) * 12f              // ±6°
            val coreAlpha = 140 + rng.nextInt(70)                        // 140–209: vivid
            drawAuroraBand(c, w, centerY, halfThick, angleDeg, r, g, b, coreAlpha)
        }

        return BackgroundBitmap(bmp, AURORA_HEIGHT_LINES)
    }

    /**
     * Builds the rainbow-crop day background bitmap.
     *
     * @param w       Bitmap width in pixels (should match the view width).
     * @param lineH   Height of one waveform line in pixels.
     * @param density Screen density (unused; accepted for API symmetry).
     * @return A [BackgroundBitmap] wrapping the rendered scene and its tile height.
     */
    @Suppress("UNUSED_PARAMETER")
    fun buildRainbow(w: Int, lineH: Int, density: Float): BackgroundBitmap {
        val bmpH = (lineH * RAINBOW_HEIGHT_LINES).toInt()
        val bmp  = Bitmap.createBitmap(w, bmpH, Bitmap.Config.ARGB_8888)
        val c    = Canvas(bmp)

        // Pale luminous sky: warm white softening to a cool lavender haze
        c.drawRect(0f, 0f, w.toFloat(), bmpH.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, bmpH.toFloat(),
                    intArrayOf(0xFFFBF8FF.toInt(), 0xFFF5F0FF.toInt(), 0xFFF0F4FF.toInt()),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        )

        val rng = Random(RAINBOW_SEED)

        repeat(RAINBOW_BAND_COUNT) { i ->
            // Distribute band centres with jitter so no two adjacent bands are
            // equidistant — keeps the pattern from looking mechanical.
            val centerY   = ((i + 0.3f + rng.nextFloat() * 0.4f) / RAINBOW_BAND_COUNT) * bmpH
            val halfThick = lineH * (0.18f + rng.nextFloat() * 0.22f)   // 18–40% of lineH
            val angleDeg  = (rng.nextFloat() - 0.5f) * 30f              // ±15°
            val coreAlpha = 110 + rng.nextInt(70)                        // 110–179: vivid but not harsh
            drawRainbowBand(c, w, centerY, halfThick, angleDeg, coreAlpha)
        }

        return BackgroundBitmap(bmp, RAINBOW_HEIGHT_LINES)
    }

    // ── Private drawing helpers ───────────────────────────────────────────────

    /**
     * Draws one aurora band — a single-hue ribbon fading to transparent at both
     * vertical edges.
     *
     * The canvas is rotated so the band sits at [angleDeg] from horizontal.
     * The rect extends to ±[w] horizontally to cover all rotated corners without
     * clipping.
     */
    private fun drawAuroraBand(
        c: Canvas,
        w: Int,
        centerY: Float,
        halfThick: Float,
        angleDeg: Float,
        r: Int, g: Int, b: Int,
        coreAlpha: Int,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, centerY - halfThick,
                0f, centerY + halfThick,
                intArrayOf(
                    Color.argb(0,          r, g, b),
                    Color.argb(coreAlpha,  r, g, b),
                    Color.argb(coreAlpha,  r, g, b),
                    Color.argb(0,          r, g, b),
                ),
                floatArrayOf(0f, 0.25f, 0.75f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.save()
        c.rotate(angleDeg, w / 2f, centerY)
        c.drawRect(-w.toFloat(), centerY - halfThick, w * 2f, centerY + halfThick, paint)
        c.restore()
    }

    /**
     * Draws one rainbow band — a full ROYGBIV sweep across the band's thickness,
     * fading to transparent at both vertical edges.
     *
     * The gradient runs perpendicular to the band direction: after the canvas is
     * rotated by [angleDeg], the LinearGradient's vertical axis becomes the
     * cross-band axis automatically, so color placement is correct at any angle
     * without trigonometric adjustment.
     *
     * The transparent bookend stops share their RGB with the nearest color stop
     * so the alpha fade dissolves into the background rather than shifting hue
     * as it approaches zero.
     */
    private fun drawRainbowBand(
        c: Canvas,
        w: Int,
        centerY: Float,
        halfThick: Float,
        angleDeg: Float,
        coreAlpha: Int,
    ) {
        val stopCount     = ROYGBIV.size + 2
        val stopColors    = IntArray(stopCount)
        val stopPositions = FloatArray(stopCount)

        // Transparent bookends
        stopColors[0]            = Color.argb(0, ROYGBIV.first()[0], ROYGBIV.first()[1], ROYGBIV.first()[2])
        stopPositions[0]         = 0f
        stopColors[stopCount - 1]    = Color.argb(0, ROYGBIV.last()[0], ROYGBIV.last()[1], ROYGBIV.last()[2])
        stopPositions[stopCount - 1] = 1f

        // ROYGBIV stops mapped across the inner 76% of the band, leaving 12% at
        // each edge for the fade zone.
        ROYGBIV.forEachIndexed { idx, (r, g, b) ->
            stopColors[idx + 1]    = Color.argb(coreAlpha, r, g, b)
            stopPositions[idx + 1] = 0.12f + (idx.toFloat() / (ROYGBIV.size - 1)) * 0.76f
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, centerY - halfThick,
                0f, centerY + halfThick,
                stopColors,
                stopPositions,
                Shader.TileMode.CLAMP
            )
        }
        c.save()
        c.rotate(angleDeg, w / 2f, centerY)
        c.drawRect(-w.toFloat(), centerY - halfThick, w * 2f, centerY + halfThick, paint)
        c.restore()
    }
}

// ── IntArray destructuring for (r, g, b) palette entries ─────────────────────

private operator fun IntArray.component1() = this[0]
private operator fun IntArray.component2() = this[1]
private operator fun IntArray.component3() = this[2]