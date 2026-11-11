package com.vintagelab.camera.looks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EditAdjustTest {
    private val base = Preset(id = "x", exposure = 0.3f, temperature = 0.4f)

    @Test fun zeroKeepsThePresetInstance() = assertSame(base, EditAdjust.apply(base, 0f, 0f))

    @Test fun addsToTheLook() {
        val p = EditAdjust.apply(base, 0.5f, -0.2f)
        assertEquals(0.8f, p.exposure, 1e-6f)
        assertEquals(0.2f, p.temperature, 1e-6f)
    }

    @Test fun clampsSlidersAndResult() {
        val p = EditAdjust.apply(base, 9f, 9f)
        assertEquals(0.3f + EditAdjust.EV_RANGE, p.exposure, 1e-6f)
        assertEquals(0.9f, p.temperature, 1e-6f)
        assertEquals(1f, EditAdjust.apply(base.copy(temperature = 0.9f), 0f, 0.5f).temperature, 1e-6f)
    }
}
