package app.treecast.ui.waveform

import android.graphics.Bitmap

/**
 * Return type for all background renderer build functions
 * (e.g. [SkyBackgroundRenderer.buildNight], [SkyLightsBackgroundRenderer.buildAurora]).
 *
 * Bundling the bitmap and its tile height together means [MultiLineWaveformView]
 * can never accidentally use a stale or mismatched height constant — the correct
 * value always travels with the bitmap it describes.
 *
 * @param bitmap      The rendered background scene.  The view takes ownership;
 *                    call [bitmap.recycle] when replacing or discarding it.
 * @param heightLines The bitmap's height expressed as a multiple of one line
 *                    height in pixels.  Used by [BackgroundDecoration] to compute
 *                    the Y offset for each line:
 *                    `yOffset = (lineIndex * lineHeightPx) % bitmap.height`
 */
data class BackgroundBitmap(
    val bitmap: Bitmap,
    val heightLines: Float,
)