package com.vintagelab.camera.capture

import com.vintagelab.camera.looks.AspectRatio
import com.vintagelab.camera.looks.CropMath
import com.vintagelab.camera.looks.FrameId
import com.vintagelab.camera.looks.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameGeometryTest {

    @Test
    fun instantSquare_borders() {
        val l = FrameGeometry.layout(FrameId.INSTANT_SQUARE, 3000, 3000)
        assertEquals(210, l.imageLeft)
        assertEquals(210, l.imageTop)
        assertEquals(3000 + 420, l.canvasW)
        assertEquals(3000 + 210 + 720, l.canvasH)
    }

    @Test
    fun instantMini_borders() {
        val l = FrameGeometry.layout(FrameId.INSTANT_MINI, 2280, 3000)
        assertEquals(137, l.imageLeft) // 0.06 * 2280
        assertEquals(137, l.imageTop)
        assertEquals(2280 + 2 * 137, l.canvasW)
        assertEquals(3000 + 137 + 410, l.canvasH) // bottom 0.18 * 2280
    }

    @Test
    fun film120_and_slideMount_borders() {
        val f = FrameGeometry.layout(FrameId.FILM_120, 2000, 2000)
        assertEquals(100, f.imageLeft)
        assertEquals(2200, f.canvasW)
        assertEquals(2200, f.canvasH)
        val m = FrameGeometry.layout(FrameId.SLIDE_MOUNT, 4000, 3000)
        assertEquals(360, m.imageLeft)
        assertEquals(360, m.imageTop)
        assertEquals(4000 + 720, m.canvasW)
        assertEquals(3000 + 720, m.canvasH)
    }

    @Test
    fun forcedAspect_overridesRatioInCanvasAspect() {
        assertEquals(1f, FrameGeometry.forcedAspect(FrameId.INSTANT_SQUARE, true))
        assertEquals(1f, FrameGeometry.forcedAspect(FrameId.FILM_120, false))
        assertEquals(0.76f, FrameGeometry.forcedAspect(FrameId.INSTANT_MINI, true)!!, 1e-6f)
        assertEquals(1f / 0.76f, FrameGeometry.forcedAspect(FrameId.INSTANT_MINI, false)!!, 1e-6f)
        assertNull(FrameGeometry.forcedAspect(FrameId.SLIDE_MOUNT, true))
        // the ratio button is ignored for forced frames
        val a = FrameGeometry.canvasAspect(AspectRatio.R16_9, FrameId.INSTANT_SQUARE, true)
        assertEquals(FrameGeometry.canvasAspect(AspectRatio.R4_3, FrameId.INSTANT_SQUARE, true), a, 1e-4f)
        assertEquals(1.14f / 1.31f, a, 0.002f)
        assertEquals(1.14f / 1.31f, FrameGeometry.canvasAspect(AspectRatio.R1_1, FrameId.INSTANT_SQUARE, false), 0.002f)
        // slide mount follows the ratio
        assertEquals((1f + 0.24f) / (4f / 3f + 0.24f), FrameGeometry.canvasAspect(AspectRatio.R4_3, FrameId.SLIDE_MOUNT, true), 0.002f)
    }

    @Test
    fun imageRect_matchesLayout() {
        val r = FrameGeometry.imageRectInCanvas(AspectRatio.R4_3, FrameId.INSTANT_MINI, true)
        assertEquals(0.06f / 1.12f, r.x, 0.002f)
    }

    @Test
    fun constrainCrop_isConsistentForPreviewAndCapture() {
        // Upright portrait (rotation 90 => landscape buffer 4000x3000) -> square, centred.
        val buf = IntRect(0, 0, 4000, 3000)
        val sq = FrameGeometry.constrainCrop(buf, 90, FrameId.INSTANT_SQUARE)
        assertEquals(IntRect(500, 0, 3500, 3000), sq)
        // A subsampled capture bitmap (half size) yields the same normalized crop.
        val half = FrameGeometry.constrainCrop(IntRect(0, 0, 2000, 1500), 90, FrameId.INSTANT_SQUARE)
        assertEquals(sq.left / 2, half.left)
        assertEquals(sq.width / 2, half.width)
        // mini: upright portrait 0.76 w/h, so with a rotated buffer the buffer h/w is 0.76
        val mini = FrameGeometry.constrainCrop(buf, 90, FrameId.INSTANT_MINI)
        assertEquals(0.76f, mini.height.toFloat() / mini.width, 0.002f)
        assertEquals(3000, mini.height)
        // frames without a forced ratio leave the crop alone
        assertEquals(buf, FrameGeometry.constrainCrop(buf, 0, FrameId.SLIDE_MOUNT))
    }

    @Test
    fun constrainToAspect_upright() {
        val c = CropMath.constrainToAspect(IntRect(0, 0, 4000, 3000), 0, 1f)
        assertEquals(IntRect(500, 0, 3500, 3000), c)
        val c2 = CropMath.constrainToAspect(IntRect(0, 0, 3000, 4000), 0, 0.76f)
        assertEquals(3000, c2.width)
        assertEquals(0.76f, c2.width.toFloat() / c2.height, 0.002f)
    }
}
