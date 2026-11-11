package com.vintagelab.camera.looks

import kotlin.math.roundToInt

/** Pure-Kotlin integer rectangle (left/top inclusive, right/bottom exclusive), buffer pixels. */
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

enum class AspectRatio(val long: Int, val short: Int) {
    R4_3(4, 3), R1_1(1, 1), R16_9(16, 9);

    /** Width/height of the upright output for a portrait (true) or landscape (false) device. */
    fun upright(portrait: Boolean): Pair<Int, Int> = if (portrait) short to long else long to short
}

object CropMath {
    /**
     * Largest centred rect of [ratio] whose long side follows the buffer's long side.
     * This matches what a shared `ViewPort` (fill-centre) yields for the same ratio, independent
     * of rotation (a 90° rotation swaps both the buffer and the target orientation). 1:1 is a
     * centre crop of the full frame.
     */
    fun centerCrop(bufW: Int, bufH: Int, ratio: AspectRatio): IntRect {
        val landscape = bufW >= bufH
        val aw = if (landscape) ratio.long else ratio.short
        val ah = if (landscape) ratio.short else ratio.long
        var w = bufW
        var h = (bufW.toLong() * ah / aw).toInt()
        if (h > bufH) {
            h = bufH
            w = (bufH.toLong() * aw / ah).toInt()
        }
        val l = (bufW - w) / 2
        val t = (bufH - h) / 2
        return IntRect(l, t, l + w, t + h)
    }

    /**
     * Shrinks [crop] (buffer pixels, centred) so that its **upright** aspect (width/height after
     * [rotationDegrees]) equals [aspect]. Used by frames that force a ratio (SPEC 7.3); applied
     * identically to the preview crop and the capture crop so both paths frame the same pixels.
     */
    fun constrainToAspect(crop: IntRect, rotationDegrees: Int, aspect: Float): IntRect {
        val swaps = rotationDegrees == 90 || rotationDegrees == 270
        val uw = if (swaps) crop.height else crop.width
        val uh = if (swaps) crop.width else crop.height
        var nw = uw
        var nh = (uw / aspect).roundToInt()
        if (nh > uh) {
            nh = uh
            nw = (uh * aspect).roundToInt()
        }
        nw = nw.coerceIn(1, uw)
        nh = nh.coerceIn(1, uh)
        val bw = if (swaps) nh else nw
        val bh = if (swaps) nw else nh
        val l = crop.left + (crop.width - bw) / 2
        val t = crop.top + (crop.height - bh) / 2
        return IntRect(l, t, l + bw, t + bh)
    }

    /** Scale factor so that the long side of (w,h) is ≤ [maxLong] (never upscales). */
    fun capScale(w: Int, h: Int, maxLong: Int): Float {
        val long = maxOf(w, h)
        return if (long <= maxLong) 1f else maxLong.toFloat() / long
    }

    fun scaled(w: Int, h: Int, scale: Float): Pair<Int, Int> =
        maxOf(1, (w * scale).roundToInt()) to maxOf(1, (h * scale).roundToInt())
}

/**
 * Maps the upright, cropped output image to source-buffer coordinates.
 *
 * Coordinates are normalized with origin top-left, y down ("image uv"). [rotationDegrees] is
 * the clockwise rotation that makes the buffer upright (CameraX convention). [mirror] flips
 * the upright output horizontally (front-camera preview only; saved photos are never mirrored).
 */
data class SourceTransform(
    val bufWidth: Int,
    val bufHeight: Int,
    val crop: IntRect,
    val rotationDegrees: Int,
    val mirror: Boolean,
) {
    init {
        require(rotationDegrees in setOf(0, 90, 180, 270)) { "rotation $rotationDegrees" }
    }

    private val swaps get() = rotationDegrees == 90 || rotationDegrees == 270

    /** Upright output size in pixels (before any resolution cap). */
    val outWidth: Int get() = if (swaps) crop.height else crop.width
    val outHeight: Int get() = if (swaps) crop.width else crop.height

    /** Upright uv → crop-local uv (rotation and mirroring undone). */
    private fun unrotate(u0: Float, v: Float): Pair<Float, Float> {
        val u = if (mirror) 1f - u0 else u0
        return when (rotationDegrees) {
            0 -> u to v
            90 -> v to 1f - u
            180 -> 1f - u to 1f - v
            else -> 1f - v to u
        }
    }

    private fun rerotate(x: Float, y: Float): Pair<Float, Float> {
        val (u, v) = when (rotationDegrees) {
            0 -> x to y
            90 -> 1f - y to x
            180 -> 1f - x to 1f - y
            else -> y to 1f - x
        }
        return (if (mirror) 1f - u else u) to v
    }

    /** Upright output uv → normalized buffer uv (y down). */
    fun uprightToBuffer(u: Float, v: Float): Pair<Float, Float> {
        val (x, y) = unrotate(u, v)
        return (crop.left + x * crop.width) / bufWidth to (crop.top + y * crop.height) / bufHeight
    }

    /** Normalized buffer uv (y down) → upright output uv. */
    fun bufferToUpright(bx: Float, by: Float): Pair<Float, Float> {
        val x = (bx * bufWidth - crop.left) / crop.width
        val y = (by * bufHeight - crop.top) / crop.height
        return rerotate(x, y)
    }

    /**
     * Column-major 3×3 affine matrix M with `M · (u, v, 1) = (bx, by, 1)`, for the Pass 0 shader.
     */
    fun matrix(): FloatArray {
        val (ox, oy) = uprightToBuffer(0f, 0f)
        val (ux, uy) = uprightToBuffer(1f, 0f)
        val (vx, vy) = uprightToBuffer(0f, 1f)
        return floatArrayOf(
            ux - ox, uy - oy, 0f,
            vx - ox, vy - oy, 0f,
            ox, oy, 1f,
        )
    }
}
