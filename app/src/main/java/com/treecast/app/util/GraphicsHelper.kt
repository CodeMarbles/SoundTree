package com.treecast.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.palette.graphics.Palette
import java.io.ByteArrayOutputStream

private const val FALLBACK_COLOR = "#6C63FF"

/** Parses [hex] as a color, falling back to [FALLBACK_COLOR] if the string is invalid. */
private fun parseColorSafe(hex: String): Int =
    try {
        Color.parseColor(hex)
    } catch (_: IllegalArgumentException) {
        Color.parseColor(FALLBACK_COLOR)
    }

fun buildTopicArtwork(color: String, icon: String, sizePx: Int = 512): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Fill with topic color, falling back gracefully if the stored value is malformed
    canvas.drawColor(parseColorSafe(color))

    // Draw the emoji centered
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizePx * 0.45f
        textAlign = Paint.Align.CENTER
    }
    val yPos = (canvas.height / 2f) - ((paint.descent() + paint.ascent()) / 2f)
    canvas.drawText(icon, sizePx / 2f, yPos, paint)

    return bitmap
}

fun bitmapToPngByteArray(bmp: Bitmap): ByteArray {
    return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}

/**
 * Derives a mid-range accent color from an emoji by rasterizing it onto a
 * small bitmap and running the Palette API over the result.
 *
 * Vibrant swatch is preferred so the color looks intentional rather than
 * washed out. HSV value is clamped to [0.35, 0.62] so the result is never
 * dark enough to bury white notification text or light enough to read as blank.
 */
fun emojiToColor(emoji: String, sizePx: Int = 128): String {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize  = sizePx * 0.82f
        textAlign = Paint.Align.CENTER
    }
    val yPos = (sizePx / 2f) - ((paint.descent() + paint.ascent()) / 2f)
    canvas.drawText(emoji, sizePx / 2f, yPos, paint)

    val palette = Palette.from(bitmap).generate()
    val swatch  = palette.vibrantSwatch
        ?: palette.mutedSwatch
        ?: palette.dominantSwatch
        ?: return "#6C63FF"

    val hsv = FloatArray(3)
    Color.colorToHSV(swatch.rgb, hsv)
    hsv[1] = (hsv[1] * 1.15f).coerceAtMost(1f)  // nudge saturation up slightly
    hsv[2] = hsv[2].coerceIn(0.35f, 0.62f)        // clamp brightness for notification bg

    return "#%06X".format(0xFFFFFF and Color.HSVToColor(hsv))
}