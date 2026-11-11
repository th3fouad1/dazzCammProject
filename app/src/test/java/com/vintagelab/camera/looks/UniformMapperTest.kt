package com.vintagelab.camera.looks

import com.vintagelab.camera.capture.DateStamp
import com.vintagelab.camera.capture.FrameGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class UniformMapperTest {

    private val classic35 by lazy { TestAssets.presets().first { it.id == "classic35" } }

    @Test
    fun goldenClassic35() {
        val codes = DateStamp.codes(LocalDateTime.of(2026, 11, 11, 10, 0)).toList()
        val state = LookState(seed = 42L, frame = FrameId.NONE, dateStampOn = true, stampCodes = codes, grainTime = 1.5f)
        val u = UniformMapper.map(classic35, state)
        assertEquals(1f, u.lutStrength)
        assertEquals(Vec3(0.4f, 0.0014f, 0.2f), u.grain)
        assertEquals(1.5f, u.grainTime)
        assertEquals(Vec4(0.35f, 0.9f, 0.6f, 0f), u.vignette)
        assertEquals(Vec2(0f, 0.5f), u.centerGlow)
        assertEquals(0f, u.softFocus)
        assertEquals(Vec2(0f, 0.8f), u.bloom)
        assertEquals(0f, u.sharpen)
        assertEquals(Vec2(0f, 0f), u.distortion)
        assertEquals(0.35f, u.leak.z)
        assertTrue(u.leak.x == 0f || u.leak.x == 1f)
        assertTrue(u.leak.y in listOf(0f, 1f, 2f, 3f))
        assertTrue(u.leak.w in 0f..(2 * Math.PI.toFloat()))
        assertEquals(0.1f, u.dust)
        assertTrue(u.seed in 0f..1f)
        assertEquals(0, u.frame)
        assertEquals(Vec4(1f, 0x8A / 255f, 0x1F / 255f, 1f), u.stampColor)
        assertEquals(1f, u.stampOn)
        assertEquals(listOf(11f, 2f, 6f, 1f, 1f, 1f, 1f, 10f), u.stampDigits)
        assertEquals("'26 11 11", DateStamp.text(codes.toIntArray()))
        // deterministic: same seed → identical uniforms (both GL contexts rely on this)
        assertEquals(u, UniformMapper.map(classic35, state))
    }

    @Test
    fun leakProbabilityRoughlyHonoured_andSeedMatters() {
        val n = 4000
        val on = (0 until n).count {
            UniformMapper.map(classic35, LookState(it.toLong() * 7919, FrameId.NONE, false)).leak.x == 1f
        }
        assertEquals(0.3, on.toDouble() / n, 0.03)
        assertNotEquals(
            UniformMapper.map(classic35, LookState(1, FrameId.NONE, false)).seed,
            UniformMapper.map(classic35, LookState(2, FrameId.NONE, false)).seed,
        )
    }

    @Test
    fun noLeakWhenProbabilityZero() {
        val p = Preset("x")
        for (s in 0L until 100L) assertEquals(0f, UniformMapper.map(p, LookState(s, FrameId.NONE, false)).leak.x)
    }

    @Test
    fun geometry_isNormalizedToShortSide() {
        val u = UniformMapper.map(classic35, LookState(1, FrameId.INSTANT_WHITE, false))
        val layout = FrameGeometry.layout(FrameId.INSTANT_WHITE, 3024, 4032)
        val g = UniformMapper.withGeometry(u, Vec4(0f, 0f, 1f, 1f), layout)
        assertEquals(3024f, g.shortSide)
        assertEquals(Vec2(3024f, 4032f), g.outSize)
        assertEquals(1, g.frame)
        assertEquals(layout.canvasW.toFloat(), g.canvasSize.x)
        assertEquals(665, layout.canvasH - layout.imageTop - layout.imageH) // 0.22 S
        assertEquals(181, layout.imageLeft) // 0.06 S
    }
}
