package com.vintagelab.camera.capture

import androidx.exifinterface.media.ExifInterface
import com.vintagelab.camera.looks.FrameId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class CaptureMathTest {
    @Test
    fun exifPlan_copiesWhitelist_setsOrientationAndSoftware_neverGps() {
        val src = mapOf(
            ExifInterface.TAG_MAKE to "Maker",
            ExifInterface.TAG_F_NUMBER to "1.8",
            ExifInterface.TAG_GPS_LATITUDE to "1/1,2/1,3/1",
            ExifInterface.TAG_ORIENTATION to "6",
        )
        val plan = ExifWriter.plan(src, "App", LocalDateTime.of(2026, 11, 11, 9, 8, 7))
        assertEquals("Maker", plan[ExifInterface.TAG_MAKE])
        assertEquals("1.8", plan[ExifInterface.TAG_F_NUMBER])
        assertEquals("1", plan[ExifInterface.TAG_ORIENTATION])
        assertEquals("App", plan[ExifInterface.TAG_SOFTWARE])
        assertEquals("2026:11:11 09:08:07", plan[ExifInterface.TAG_DATETIME_ORIGINAL])
        assertFalse(plan.keys.any { it.startsWith("GPS") })
    }

    @Test
    fun fitLayout_respectsCapIncludingFrame() {
        for (frame in FrameId.entries) {
            val l = FrameGeometry.fitLayout(frame, 3024, 4032, 4096, 4096)
            assertTrue("$frame ${l.canvasW}x${l.canvasH}", l.canvasW <= 4096 && l.canvasH <= 4096)
            assertTrue(l.imageW <= 3024 && l.imageH <= 4032)
            assertEquals(3024f / 4032f, l.imageW.toFloat() / l.imageH, 0.002f)
        }
        // never upscales
        val small = FrameGeometry.fitLayout(FrameId.NONE, 640, 480, 4000, 4000)
        assertEquals(640, small.imageW)
    }

    @Test
    fun filmStrip_borders() {
        val l = FrameGeometry.layout(FrameId.FILM_STRIP, 4000, 3000)
        assertEquals(240, l.imageTop) // 0.08 S
        assertEquals(3000 + 480, l.canvasH)
        assertEquals(4000, l.canvasW)
    }

    @Test
    fun sampleSize() {
        assertEquals(1, CapturePipeline.sampleSize(4000, 3000, 4096, 4096))
        assertEquals(2, CapturePipeline.sampleSize(4000, 3000, 2048, 2048)) // OOM retry
        assertEquals(2, CapturePipeline.sampleSize(8000, 6000, 4096, 4096))
    }

    @Test
    fun dateStampCodes() {
        val c = DateStamp.codes(LocalDateTime.of(2007, 3, 9, 0, 0))
        assertEquals("'07 03 09", DateStamp.text(c))
        assertEquals(DateStamp.SLOTS, c.size)
    }
}
