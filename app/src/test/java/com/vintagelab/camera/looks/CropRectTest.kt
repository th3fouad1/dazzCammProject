package com.vintagelab.camera.looks

import org.junit.Assert.assertEquals
import org.junit.Test

class CropRectTest {
    @Test
    fun landscapeBuffer() {
        assertEquals(IntRect(0, 0, 4032, 3024), CropMath.centerCrop(4032, 3024, AspectRatio.R4_3))
        assertEquals(IntRect(504, 0, 3528, 3024), CropMath.centerCrop(4032, 3024, AspectRatio.R1_1))
        assertEquals(IntRect(0, 378, 4032, 2646), CropMath.centerCrop(4032, 3024, AspectRatio.R16_9))
    }

    @Test
    fun portraitBuffer() {
        assertEquals(IntRect(0, 0, 3024, 4032), CropMath.centerCrop(3024, 4032, AspectRatio.R4_3))
        assertEquals(IntRect(0, 504, 3024, 3528), CropMath.centerCrop(3024, 4032, AspectRatio.R1_1))
        assertEquals(IntRect(378, 0, 2646, 4032), CropMath.centerCrop(3024, 4032, AspectRatio.R16_9))
    }

    @Test
    fun cropIsCenteredAndHasRatio() {
        for (ratio in AspectRatio.entries) for ((w, h) in listOf(4032 to 3024, 3024 to 4032)) {
            val r = CropMath.centerCrop(w, h, ratio)
            val long = maxOf(r.width, r.height).toFloat()
            val short = minOf(r.width, r.height).toFloat()
            assertEquals(ratio.long.toFloat() / ratio.short, long / short, 0.01f)
            assertEquals(w - r.right, r.left)
            assertEquals(h - r.bottom, r.top)
        }
    }

    @Test
    fun capScale() {
        assertEquals(1f, CropMath.capScale(4000, 3000, 4096))
        assertEquals(0.5f, CropMath.capScale(8192, 6144, 4096))
        assertEquals(2048 to 1536, CropMath.scaled(8192, 6144, CropMath.capScale(8192, 6144, 2048)))
    }
}
