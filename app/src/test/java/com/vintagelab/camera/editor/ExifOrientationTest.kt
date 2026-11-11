package com.vintagelab.camera.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class ExifOrientationTest {
    private val w = 4
    private val h = 2

    /** Destination of the stored top-left, top-right and bottom-left pixels, per EXIF value 1..8. */
    private fun corners(o: Int) = Triple(
        ExifOrientation.mapPoint(o, 0, 0, w, h),
        ExifOrientation.mapPoint(o, w - 1, 0, w, h),
        ExifOrientation.mapPoint(o, 0, h - 1, w, h),
    )

    @Test fun o1_normal() = assertEquals(Triple(0 to 0, 3 to 0, 0 to 1), corners(1))
    @Test fun o2_mirrorHorizontal() = assertEquals(Triple(3 to 0, 0 to 0, 3 to 1), corners(2))
    @Test fun o3_rotate180() = assertEquals(Triple(3 to 1, 0 to 1, 3 to 0), corners(3))
    @Test fun o4_mirrorVertical() = assertEquals(Triple(0 to 1, 3 to 1, 0 to 0), corners(4))

    // 5..8 swap axes: the upright image is h wide and w tall (2 x 4).
    @Test fun o5_transpose() = assertEquals(Triple(0 to 0, 0 to 3, 1 to 0), corners(5))
    @Test fun o6_rotate90() = assertEquals(Triple(1 to 0, 1 to 3, 0 to 0), corners(6))
    @Test fun o7_transverse() = assertEquals(Triple(1 to 3, 1 to 0, 0 to 3), corners(7))
    @Test fun o8_rotate270() = assertEquals(Triple(0 to 3, 0 to 0, 1 to 3), corners(8))

    @Test fun sizesSwapOnlyForFiveToEight() {
        for (o in 1..4) assertEquals(4 to 2, ExifOrientation.outSize(o, 4, 2))
        for (o in 5..8) assertEquals(2 to 4, ExifOrientation.outSize(o, 4, 2))
    }

    @Test fun everyOrientationIsABijection() {
        for (o in 1..8) {
            val (ow, oh) = ExifOrientation.outSize(o, w, h)
            val seen = HashSet<Pair<Int, Int>>()
            for (y in 0 until h) for (x in 0 until w) {
                val p = ExifOrientation.mapPoint(o, x, y, w, h)
                assertEquals("in range for $o", true, p.first in 0 until ow && p.second in 0 until oh)
                seen += p
            }
            assertEquals("bijection for $o", w * h, seen.size)
        }
    }

    @Test fun unknownValuesAreIdentity() {
        assertEquals(ExifOrientation.Spec(0, false), ExifOrientation.spec(0))
        assertEquals(ExifOrientation.Spec(0, false), ExifOrientation.spec(99))
    }
}
