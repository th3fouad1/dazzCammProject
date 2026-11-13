package com.vintagelab.camera.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class BoostTest {
    private fun scene(w: Int, h: Int, ox: Float = 0f, oy: Float = 0f): Luma {
        val v = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val X = x + ox
            val Y = y + oy
            v[y * w + x] = (0.5f + 0.25f * sin(X * 0.11f) * sin(Y * 0.07f) + 0.2f * sin((X + 2 * Y) * 0.031f)).toFloat()
        }
        return Luma(w, h, v)
    }

    @Test fun alignFindsIntegerShift() {
        val ref = scene(320, 240)
        val fr = scene(320, 240, ox = -13f, oy = 7f) // frame(x+13, y-7) == ref(x, y)
        val s = Align.estimate(ref, fr)
        assertEquals(13f, s.dx, 0.35f)
        assertEquals(-7f, s.dy, 0.35f)
    }

    @Test fun alignFindsSubPixelShift() {
        val ref = scene(320, 240)
        val fr = scene(320, 240, ox = -4.5f, oy = 2.25f)
        val s = Align.estimate(ref, fr)
        assertEquals(4.5f, s.dx, 0.3f)
        assertEquals(-2.25f, s.dy, 0.3f)
    }

    @Test fun alignIdentity() {
        val ref = scene(200, 150)
        val s = Align.estimate(ref, ref)
        assertEquals(0f, s.dx, 0.05f)
        assertEquals(0f, s.dy, 0.05f)
    }

    @Test fun plansAndRules() {
        assertNull(BoostRules.plan(BoostMode.OFF))
        assertEquals(6, BoostRules.plan(BoostMode.NIGHT)!!.frames)
        assertTrue(BoostRules.plan(BoostMode.HDR)!!.ev < 0f)
        assertEquals(BoostMode.NIGHT, BoostRules.next(BoostMode.OFF))
        assertEquals(BoostMode.HDR, BoostRules.next(BoostMode.NIGHT))
        assertEquals(BoostMode.OFF, BoostRules.next(BoostMode.HDR))
        assertTrue(BoostRules.applies(BoostMode.NIGHT, flashOff = true, multi = MultiMode.None))
        assertFalse(BoostRules.applies(BoostMode.NIGHT, flashOff = false, multi = MultiMode.None))
        assertFalse(BoostRules.applies(BoostMode.HDR, flashOff = true, multi = MultiMode.Double))
        assertFalse(BoostRules.applies(BoostMode.OFF, flashOff = true, multi = MultiMode.None))
    }

    @Test fun evIndexAndFpsRange() {
        assertEquals(-8, BoostRules.evIndex(-1.3f, 1f / 6f, -24, 24))
        assertEquals(-2, BoostRules.evIndex(-1.3f, 1f / 6f, -2, 2))
        assertEquals(0, BoostRules.evIndex(0f, 1f / 6f, -24, 24))
        assertEquals(10 to 30, BoostRules.nightFpsRange(listOf(30 to 30, 7 to 30, 10 to 30, 10 to 24, 15 to 30)))
        assertNull(BoostRules.nightFpsRange(listOf(30 to 30, 24 to 30)))
    }
}
