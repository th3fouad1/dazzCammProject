package com.vintagelab.camera.capture

import com.vintagelab.camera.looks.AspectRatio
import com.vintagelab.camera.looks.CropMath
import com.vintagelab.camera.looks.IntRect
import com.vintagelab.camera.looks.FrameId
import com.vintagelab.camera.looks.Vec4
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

    // instant_square
    const val SQUARE_SIDE = 0.07f
    const val SQUARE_TOP = 0.07f
    const val SQUARE_BOTTOM = 0.24f

    // instant_mini (image aspect w/h = 0.76 in portrait)
    const val MINI_ASPECT = 0.76f
    const val MINI_SIDE = 0.06f
    const val MINI_TOP = 0.06f
    const val MINI_BOTTOM = 0.18f

    // film120: black border, square image, frame-number ticks along the bottom border
    const val F120_BORDER = 0.05f
    const val F120_TICK_PITCH = 0.12f
    const val F120_TICK_W = 0.010f
    const val F120_TICK_H = 0.016f
    const val F120_COLOR = 0x0B0B0B

    // slide_mount: card mount with rounded window and inner drop shadow
    const val MOUNT_BORDER = 0.12f
    const val MOUNT_RADIUS = 0.03f
    const val MOUNT_COLOR = 0xEDE9DF
    const val MOUNT_SHADOW_OFFSET_X = 0.006f
    const val MOUNT_SHADOW_OFFSET_Y = 0.010f
    const val MOUNT_SHADOW_BLUR = 0.012f
    const val MOUNT_SHADOW_STRENGTH = 0.30f

    // film_strip
    const val STRIP_BORDER = 0.08f
    const val SPROCKET_PITCH = 0.06f
    const val SPROCKET_W = 0.03f
    const val SPROCKET_H = 0.04f
    const val SPROCKET_RADIUS = 0.006f

    // date stamp
    const val STAMP_INSET = 0.04f
    const val STAMP_CAP_HEIGHT = 0.035f

    /** Reference short side for resolution-independent UI geometry. */
    private const val REF_SHORT = 3000

    /**
     * Image aspect (w/h) forced by [frame], or null if the frame follows the capture ratio.
     * Square frames are 1:1; the mini frame is [MINI_ASPECT] in portrait and its inverse in landscape.
     * Both render paths crop to this via `CropMath.constrainToAspect` (SPEC 7.3).
     */
    fun forcedAspect(frame: FrameId, imagePortrait: Boolean): Float? = when (frame) {
        FrameId.INSTANT_SQUARE, FrameId.FILM_120 -> 1f
        FrameId.INSTANT_MINI -> if (imagePortrait) MINI_ASPECT else 1f / MINI_ASPECT
        else -> null
    }

    private fun refLayout(ratio: AspectRatio, frame: FrameId, imagePortrait: Boolean): FrameLayout {
        val forced = forcedAspect(frame, imagePortrait)
        val (w, h) = if (forced != null) {
            if (forced >= 1f) (REF_SHORT * forced).roundToInt() to REF_SHORT
            else REF_SHORT to (REF_SHORT / forced).roundToInt()
        } else {
            val long = REF_SHORT * ratio.long / ratio.short
            if (imagePortrait) REF_SHORT to long else long to REF_SHORT
        }
        return layout(frame, w, h)
    }

    /** Width / height of the output canvas **including the frame margin** (DESIGN §4.1, approved contract). */
    fun canvasAspect(ratio: AspectRatio, frame: FrameId, imagePortrait: Boolean): Float {
        val l = refLayout(ratio, frame, imagePortrait)
        return l.canvasW.toFloat() / l.canvasH
    }

    /** K13: the image area inside the canvas, normalized (x, y-down, w, h). */
    fun imageRectInCanvas(ratio: AspectRatio, frame: FrameId, imagePortrait: Boolean): Vec4 {
        val r = refLayout(ratio, frame, imagePortrait).imageRectUv()
        return Vec4(r[0], r[1], r[2], r[3])
    }

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

    /** Applies the frame's forced aspect (if any) to a source crop, for preview and capture alike. */
    fun constrainCrop(crop: IntRect, rotationDegrees: Int, frame: FrameId): IntRect {
        val swaps = rotationDegrees == 90 || rotationDegrees == 270
        val uw = if (swaps) crop.height else crop.width
        val uh = if (swaps) crop.width else crop.height
        val aspect = forcedAspect(frame, uh >= uw) ?: return crop
        return CropMath.constrainToAspect(crop, rotationDegrees, aspect)
    }

    private fun bordered(frame: FrameId, imageW: Int, imageH: Int, side: Int, top: Int, bottom: Int) =
        FrameLayout(frame, imageW + 2 * side, imageH + top + bottom, side, top, imageW, imageH)

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
            FrameId.INSTANT_SQUARE -> bordered(frame, imageW, imageH, px(SQUARE_SIDE), px(SQUARE_TOP), px(SQUARE_BOTTOM))
            FrameId.INSTANT_MINI -> bordered(frame, imageW, imageH, px(MINI_SIDE), px(MINI_TOP), px(MINI_BOTTOM))
            FrameId.FILM_120 -> px(F120_BORDER).let { bordered(frame, imageW, imageH, it, it, it) }
            FrameId.SLIDE_MOUNT -> px(MOUNT_BORDER).let { bordered(frame, imageW, imageH, it, it, it) }
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
