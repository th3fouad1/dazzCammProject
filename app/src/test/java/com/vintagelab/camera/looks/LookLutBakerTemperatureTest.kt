package com.vintagelab.camera.looks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookLutBakerTemperatureTest {
    private fun mid(t: Float): FloatArray {
        val f = LookLutBaker.bakeFloat(Preset("x", temperature = t), null)
        val n = LutLayout.SIZE
        val i = n / 2
        val o = LutLayout.index(i, i, i) * 3
        return floatArrayOf(f[o], f[o + 1], f[o + 2])
    }

    @Test
    fun warmShiftIsStrongAndKeepsLuma() {
        val neutral = mid(0f)
        val warm = mid(1f)
        assertTrue(warm[0] / warm[2] > 1.5f) // about (1.25/0.75) before clamping
        val l0 = 0.2126f * neutral[0] + 0.7152f * neutral[1] + 0.0722f * neutral[2]
        val l1 = 0.2126f * warm[0] + 0.7152f * warm[1] + 0.0722f * warm[2]
        assertEquals(l0, l1, 0.01f)
    }
}
