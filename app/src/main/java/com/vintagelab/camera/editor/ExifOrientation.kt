package com.vintagelab.camera.editor

/**
 * EXIF Orientation (values 1..8) as "mirror horizontally first, then rotate clockwise" (SPEC 6.7).
 * Pure Kotlin so the eight cases are unit tested. The primary decoder (ImageDecoder) applies the orientation itself;
 * this mapping is used by the BitmapFactory fallback.
 */
object ExifOrientation {
    data class Spec(val rotationDegrees: Int, val mirror: Boolean)

    fun spec(orientation: Int): Spec = when (orientation) {
        2 -> Spec(0, true)
        3 -> Spec(180, false)
        4 -> Spec(180, true)
        5 -> Spec(270, true)
        6 -> Spec(90, false)
        7 -> Spec(90, true)
        8 -> Spec(270, false)
        else -> Spec(0, false)
    }

    fun swapsAxes(orientation: Int): Boolean = spec(orientation).rotationDegrees.let { it == 90 || it == 270 }

    /** Width and height of the upright image for a [w]x[h] stored image. */
    fun outSize(orientation: Int, w: Int, h: Int): Pair<Int, Int> = if (swapsAxes(orientation)) h to w else w to h

    /** Where the stored pixel ([x], [y]) of a [w]x[h] image lands in the upright image. */
    fun mapPoint(orientation: Int, x: Int, y: Int, w: Int, h: Int): Pair<Int, Int> {
        val s = spec(orientation)
        val mx = if (s.mirror) w - 1 - x else x
        return when (s.rotationDegrees) {
            90 -> (h - 1 - y) to mx
            180 -> (w - 1 - mx) to (h - 1 - y)
            270 -> y to (w - 1 - mx)
            else -> mx to y
        }
    }
}
