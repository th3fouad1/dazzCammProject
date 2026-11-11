package com.vintagelab.camera.looks

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 4 rotations × 2 lens facings × 3 ratios, for both the preview and the capture transform.
 * Capture never mirrors; the front preview mirrors. Landscape sensor buffer 4032×3024.
 */
class OrientationTest {
    private val bufW = 4032
    private val bufH = 3024
    private val eps = 1e-4f

    private fun close(exp: Pair<Float, Float>, act: Pair<Float, Float>, msg: String) {
        assertEquals("$msg x", exp.first, act.first, eps)
        assertEquals("$msg y", exp.second, act.second, eps)
    }

    @Test
    fun rotationsFacingsRatios() {
        for (rotation in listOf(0, 90, 180, 270)) for (front in listOf(false, true)) for (ratio in AspectRatio.entries) {
            for (preview in listOf(false, true)) {
                val mirror = front && preview
                val crop = CropMath.centerCrop(bufW, bufH, ratio)
                val t = SourceTransform(bufW, bufH, crop, rotation, mirror)
                val tag = "rot=$rotation front=$front preview=$preview $ratio"

                if (rotation % 180 == 0) {
                    assertEquals(tag, crop.width, t.outWidth); assertEquals(tag, crop.height, t.outHeight)
                } else {
                    assertEquals(tag, crop.height, t.outWidth); assertEquals(tag, crop.width, t.outHeight)
                }

                // The buffer corner that a clockwise rotation brings to the upright top-left / top-right.
                val l = crop.left.toFloat() / bufW
                val r = crop.right.toFloat() / bufW
                val top = crop.top.toFloat() / bufH
                val bot = crop.bottom.toFloat() / bufH
                val topLeft = when (rotation) {
                    0 -> l to top
                    90 -> l to bot
                    180 -> r to bot
                    else -> r to top
                }
                val topRight = when (rotation) {
                    0 -> r to top
                    90 -> l to top
                    180 -> l to bot
                    else -> r to bot
                }
                if (mirror) {
                    close(topRight, t.uprightToBuffer(0f, 0f), "$tag TL mirrored")
                    close(topLeft, t.uprightToBuffer(1f, 0f), "$tag TR mirrored")
                } else {
                    close(topLeft, t.uprightToBuffer(0f, 0f), "$tag TL")
                    close(topRight, t.uprightToBuffer(1f, 0f), "$tag TR")
                }

                val m = t.matrix()
                for ((u, v) in listOf(0.1f to 0.2f, 0.5f to 0.5f, 0.9f to 0.7f)) {
                    val b = t.uprightToBuffer(u, v)
                    close(u to v, t.bufferToUpright(b.first, b.second), "$tag roundtrip")
                    close(b, (m[0] * u + m[3] * v + m[6]) to (m[1] * u + m[4] * v + m[7]), "$tag matrix")
                }
            }
        }
    }
}
