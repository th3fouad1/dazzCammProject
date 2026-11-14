package com.vintagelab.camera.gallery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomMathTest {
    private val view = Size(1000f, 2000f)
    private val aspect = 0.75f // 3:4 portrait photo, fitted to 1000 x 1333

    @Test fun scaleStaysBetweenFitAndMax() {
        assertEquals(1f, ZoomMath.step(1f, Offset.Zero, 0.5f, Offset.Zero, Offset.Zero, view, aspect).first)
        assertEquals(ZoomMath.MAX, ZoomMath.step(4f, Offset.Zero, 3f, Offset.Zero, Offset.Zero, view, aspect).first)
    }

    @Test fun pointUnderFingersStaysPut() {
        val c = Offset(200f, 100f)
        val (s, o) = ZoomMath.step(1f, Offset.Zero, 2f, Offset.Zero, c, view, aspect)
        // content point under c before: c; after: c * s + o must equal c
        assertEquals(c.x, c.x * s + o.x, 0.01f)
        assertEquals(c.y, c.y * s + o.y, 0.01f)
    }

    @Test fun panIsClampedToPhotoEdges() {
        val o = ZoomMath.clamp(Offset(5000f, 5000f), 2f, view, aspect)
        assertEquals(500f, o.x, 0.01f) // (1000 * 2 - 1000) / 2
        assertEquals(333.33f, o.y, 0.1f) // (1333.3 * 2 - 2000) / 2
        assertEquals(Offset.Zero, ZoomMath.clamp(Offset(300f, 300f), 1f, view, aspect))
    }
}
