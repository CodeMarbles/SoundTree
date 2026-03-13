package com.treecast.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream

fun buildTopicArtwork(color: String, icon: String, sizePx: Int = 512): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Fill with topic color
    canvas.drawColor(Color.parseColor(color))

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