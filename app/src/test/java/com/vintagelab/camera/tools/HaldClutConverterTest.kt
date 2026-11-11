package com.vintagelab.camera.tools

import com.vintagelab.camera.looks.LutLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HaldClutConverterTest {

    private fun assertIdentity(cube: FloatArray, tol: Float) {
        val id = LutLayout.identity()
        var worst = 0f
        for (i in id.indices) worst = maxOf(worst, abs(id[i] - cube[i]))
        assertTrue("max error ${worst * 255}/255", worst <= tol)
    }

    @Test
    fun identityHaldLevel12_givesIdentity33_within1() {
        val hald = HaldClutConverter.identityHald(12)
        assertEquals(1728, hald.width)
        val cube = HaldClutConverter.convert(hald)
        assertIdentity(cube, 1f / 255f + 1e-6f)
        // and after the 8-bit PNG round trip
        val back = HaldClutConverter.fromImage(HaldClutConverter.toImage(cube))
        assertIdentity(back, 1f / 255f + 1e-6f)
    }

    @Test
    fun imageLayout_isRPlus33G_byB() {
        val img = HaldClutConverter.toImage(LutLayout.identity())
        assertEquals(1089, img.width)
        assertEquals(33, img.height)
        // entry (r=32, g=0, b=0) -> x=32, y=0 is pure red
        assertEquals(0xFF0000, img.getRGB(32, 0) and 0xFFFFFF)
        // entry (r=0, g=32, b=0) -> x=33*32, y=0 is pure green
        assertEquals(0x00FF00, img.getRGB(33 * 32, 0) and 0xFFFFFF)
        // entry (r=0, g=0, b=32) -> x=0, y=32 is pure blue
        assertEquals(0x0000FF, img.getRGB(0, 32) and 0xFFFFFF)
    }

    @Test
    fun pngRoundTrip() {
        val img = HaldClutConverter.toImage(LutLayout.identity())
        val back = MiniPng.decode(MiniPng.encodeRgb(img))
        assertEquals(img.width, back.width)
        assertTrue(img.pixels.contentEquals(back.pixels))
    }

    @Test
    fun snakeIds() {
        assertEquals("kodak_gold_200", HaldClutConverter.snakeId("Color Negative - Kodak Gold 200.png"))
        assertEquals("ilford_hp5_plus_400", HaldClutConverter.snakeId("Black and White - Ilford HP5 Plus 400.png"))
        assertEquals("fuji_pro_400h", HaldClutConverter.snakeId("Color Negative - Fuji Pro 400H.png"))
    }
}
