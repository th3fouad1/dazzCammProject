package com.vintagelab.camera.capture

import com.vintagelab.camera.looks.FrameId
import kotlin.math.roundToInt

/**
 * Frame / stamp geometry (SPEC §7.3), in units of S = short side of the cropped image.
 * Used by both render paths so preview and capture place everything identically.
 */
object FrameGeometry {
    // instant_white
    const val INSTANT_SIDE = 0.06f
    const val INSTANT_TOP = 0.06f
    const val INSTANT_BOTTOM = 0.22f
    const val INSTANT_COLOR = 0xF4F1EA

    // film_strip
    const val STRIP_BORDER = 0.08f
    const val SPROCKET_PITCH = 0.06f
    const val SPROCKET_W = 0.03f
    const val SPROCKET_H = 0.04f
    const val SPROCKET_RADIUS = 0.006f

    // date stamp
    const val STAMP_INSET = 0.04f
    const val STAMP_CAP_HEIGHT = 0.035f

    /**
     * Largest layout (never upscaling the [srcW]×[srcH] image) whose canvas fits [maxW]×[maxH].
     * Preview: bounds = view size. Capture: bounds = long-side cap in both dimensions.
     */
    fun fitLayout(frame: FrameId, srcW: Int, srcH: Int, maxW: Int, maxH: Int): FrameLayout {
        val full = layout(frame, srcW, srcH)
        var s = minOf(1f, maxW.toFloat() / full.canvasW, maxH.toFloat() / full.canvasH)
        while (true) {
            val w = maxOf(1, (srcW * s).toInt())
            val h = maxOf(1, (srcH * s).toInt())
            val l = layout(frame, w, h)
            if ((l.canvasW <= maxW && l.canvasH <= maxH) || w == 1 || h == 1) return l
            s *= 0.995f
        }
    }

    /**
     * Canvas layout for an image of [imageW]×[imageH] pixels.
     * Borders are rounded to whole pixels; the image area is never resampled.
     */
    fun layout(frame: FrameId, imageW: Int, imageH: Int): FrameLayout {
        val s = minOf(imageW, imageH).toFloat()
        fun px(f: Float) = (f * s).roundToInt()
        return when (frame) {
            FrameId.NONE -> FrameLayout(frame, imageW, imageH, 0, 0, imageW, imageH)
            FrameId.INSTANT_WHITE -> {
                val side = px(INSTANT_SIDE)
                val top = px(INSTANT_TOP)
                val bottom = px(INSTANT_BOTTOM)
                FrameLayout(frame, imageW + 2 * side, imageH + top + bottom, side, top, imageW, imageH)
            }
            FrameId.FILM_STRIP -> {
                val b = px(STRIP_BORDER)
                FrameLayout(frame, imageW, imageH + 2 * b, 0, b, imageW, imageH)
            }
        }
    }
}

/** Canvas of [canvasW]×[canvasH] px containing the image at ([imageLeft],[imageTop]) (y down). */
data class FrameLayout(
    val frame: FrameId,
    val canvasW: Int,
    val canvasH: Int,
    val imageLeft: Int,
    val imageTop: Int,
    val imageW: Int,
    val imageH: Int,
) {
    val shortSide: Int get() = minOf(imageW, imageH)

    /** Image rect in canvas uv (x, y-down, w, h). */
    fun imageRectUv(): FloatArray = floatArrayOf(
        imageLeft.toFloat() / canvasW, imageTop.toFloat() / canvasH,
        imageW.toFloat() / canvasW, imageH.toFloat() / canvasH,
    )
}
