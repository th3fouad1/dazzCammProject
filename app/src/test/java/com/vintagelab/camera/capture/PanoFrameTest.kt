package com.vintagelab.camera.capture

import com.vintagelab.camera.looks.AspectRatio
import com.vintagelab.camera.looks.FrameId
import com.vintagelab.camera.looks.IntRect
import com.vintagelab.camera.looks.TestAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** SPEC 7.3 pano_sprocket: forced 2.7:1, film edge drawn over the image (no canvas growth). */
class PanoFrameTest {
    @Test
    fun forcedAspectIs2_7InBothOrientations() {
        assertEquals(2.7f, FrameGeometry.forcedAspect(FrameId.PANO_SPROCKET, true)!!, 1e-6f)
        assertEquals(2.7f, FrameGeometry.forcedAspect(FrameId.PANO_SPROCKET, false)!!, 1e-6f)
        assertEquals(7, FrameId.PANO_SPROCKET.code)
        assertEquals(FrameId.PANO_SPROCKET, FrameId.fromId("pano_sprocket"))
    }

    @Test
    fun canvasIsTheImage_andIgnoresRatioButton() {
        for (r in AspectRatio.entries) for (portrait in listOf(true, false)) {
            assertEquals(2.7f, FrameGeometry.canvasAspect(r, FrameId.PANO_SPROCKET, portrait), 0.003f)
            val rect = FrameGeometry.imageRectInCanvas(r, FrameId.PANO_SPROCKET, portrait)
            assertEquals(0f, rect.x); assertEquals(0f, rect.y); assertEquals(1f, rect.z); assertEquals(1f, rect.w)
        }
        val l = FrameGeometry.layout(FrameId.PANO_SPROCKET, 2700, 1000)
        assertEquals(2700, l.canvasW); assertEquals(1000, l.canvasH)
    }

    @Test
    fun constrainCropGivesWideCropEvenFromPortraitCapture() {
        // Portrait 3:4 upright image (buffer rotated by 90): crop becomes 2.7 wide, centred.
        val buf = IntRect(0, 0, 4000, 3000)
        val c = FrameGeometry.constrainCrop(buf, 90, FrameId.PANO_SPROCKET)
        val uprightW = c.height
        val uprightH = c.width
        assertEquals(2.7f, uprightW.toFloat() / uprightH, 0.01f)
        assertEquals(3000, uprightW)
        assertEquals((4000 - c.width) / 2, c.left)
    }

    @Test
    fun sprocketRowMatchesSpec() {
        val w = 2700
        val h = 1000
        val g = FrameGeometry.panoHoles(w, h)
        assertEquals(0.09f * h, g.rebate, 1e-3f)
        assertEquals(0.045f * h, g.rowCentreFromEdge, 1e-3f)
        assertEquals(0.035f * h, g.holeW, 1e-3f)
        assertEquals(0.05f * h, g.holeH, 1e-3f)
        // Pitch 0.075 S: 36 holes fit 2700 px exactly.
        assertEquals(36, g.xs.size)
        for (i in 1 until g.xs.size) assertEquals(0.075f * h, g.xs[i] - g.xs[i - 1], 1e-3f)
        // Row is centred in the image width and holes stay inside it.
        assertEquals(w / 2f, (g.xs.first() + g.xs.last()) / 2f, 1e-2f)
        assertTrue(g.xs.all { it - g.holeW / 2 >= 0f && it + g.holeW / 2 <= w })
        // Hole sits inside the rebate strip.
        assertTrue(g.holeH < g.rebate)
    }

    @Test
    fun holeCountScalesWithResolution() {
        // Same shape at preview and capture size: hole count is resolution independent.
        assertEquals(FrameGeometry.panoHoles(1080, 400).xs.size, FrameGeometry.panoHoles(4320, 1600).xs.size)
    }

    @Test
    fun panoLookUsesSprocketFrameAndSpecifiedGrade() {
        val p = TestAssets.presets().first { it.id == "pano" }
        assertEquals(FrameId.PANO_SPROCKET, p.defaultFrame)
        assertEquals("look_pano", p.displayNameRes)
        assertEquals("kodak_ektar_100", p.lut)
        assertEquals(0.3f, p.grain.amount, 1e-6f)
        assertTrue(p.vignette.amount in 0.05f..0.4f)
        assertTrue(abs(p.vignette.amount) > 0f)
    }
}
