package com.vintagelab.camera.looks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneCurveTest {
    @Test
    fun identityPoints_giveIdentity() {
        for (pts in listOf(
            listOf(0f to 0f, 1f to 1f),
            listOf(0f to 0f, 0.3f to 0.3f, 0.6f to 0.6f, 1f to 1f),
        )) {
            val c = ToneCurve(pts)
            for (i in 0..255) {
                val x = i / 255f
                assertEquals(x, c.eval(x), 1f / 255f)
            }
        }
    }

    @Test
    fun monotoneOutput() {
        val curves = listOf(
            listOf(0f to 0.04f, 0.25f to 0.24f, 0.75f to 0.78f, 1f to 0.97f), // classic35
            listOf(0f to 0f, 0.1f to 0.5f, 0.2f to 0.52f, 1f to 1f), // steep then flat
            listOf(0f to 0.2f, 0.5f to 0.2f, 0.6f to 0.9f, 1f to 0.9f), // plateaus
        )
        for (pts in curves) {
            val c = ToneCurve(pts)
            var prev = c.eval(0f)
            for (i in 1..1000) {
                val y = c.eval(i / 1000f)
                assertTrue("non-monotone at ${i / 1000f}: $y < $prev", y >= prev - 1e-6f)
                prev = y
            }
            for ((x, y) in pts) assertEquals(y, c.eval(x), 1e-5f)
        }
    }

    @Test
    fun holdsEndValuesOutsideRange() {
        val c = ToneCurve(listOf(0.2f to 0.1f, 0.8f to 0.9f))
        assertEquals(0.1f, c.eval(0f), 0f)
        assertEquals(0.9f, c.eval(1f), 0f)
    }
}
