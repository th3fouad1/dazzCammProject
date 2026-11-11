package com.vintagelab.camera.looks

import org.junit.Assert.assertEquals
import org.junit.Test

/** SPEC 7.4 prism lens: the uniform is a pure function of the user setting (off for parity tests). */
class PrismTest {
    private fun state(m: PrismMode? = null) =
        if (m == null) LookState(seed = 1L, frame = FrameId.NONE, dateStampOn = false)
        else LookState(seed = 1L, frame = FrameId.NONE, dateStampOn = false, prism = m)

    private val preset = TestAssets.presets().first()

    @Test
    fun offByDefault() {
        assertEquals(PrismMode.OFF, state().prism)
        assertEquals(Vec2(0f, 1f), UniformMapper.map(preset, state()).prism)
        assertEquals(Vec2(0f, 1f), UniformSet().prism)
    }

    @Test
    fun modesMapToShaderCodes() {
        assertEquals(Vec2(0f, 1f), UniformMapper.map(preset, state(PrismMode.OFF)).prism)
        assertEquals(Vec2(1f, 1f), UniformMapper.map(preset, state(PrismMode.SPLIT)).prism)
        assertEquals(Vec2(2f, 1f), UniformMapper.map(preset, state(PrismMode.TRI)).prism)
        assertEquals(Vec2(3f, 1f), UniformMapper.map(preset, state(PrismMode.HALO)).prism)
    }

    @Test
    fun cycleOrderIsOffSplitTriHaloOff() {
        var m = PrismMode.OFF
        val seen = ArrayList<PrismMode>()
        repeat(5) { seen += m; m = m.next() }
        assertEquals(listOf(PrismMode.OFF, PrismMode.SPLIT, PrismMode.TRI, PrismMode.HALO, PrismMode.OFF), seen)
    }

    @Test
    fun prismDoesNotChangeOtherUniforms() {
        val a = UniformMapper.map(preset, state())
        val b = UniformMapper.map(preset, state(PrismMode.TRI))
        assertEquals(a, b.copy(prism = a.prism))
    }
}
