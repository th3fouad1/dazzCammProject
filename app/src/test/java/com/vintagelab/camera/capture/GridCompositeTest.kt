package com.vintagelab.camera.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** SPEC 7.4 multi-lens grid: composite geometry and burst state machine. */
class GridCompositeTest {
    @Test
    fun gapIsOnePercentOfCanvasShortSide() {
        for (a in listOf(0.75f, 4f / 3f, 1f, 9f / 16f)) for (cap in listOf(2048, 3072, 4096)) {
            val l = GridComposite.layout(a, cap)
            val short = minOf(l.canvasW, l.canvasH)
            assertTrue("gap ${l.gap} vs ${0.01f * short}", abs(l.gap - 0.01f * short) <= 1f)
            assertTrue(maxOf(l.canvasW, l.canvasH) <= cap)
            // Not needlessly small: within 2 % of the cap.
            assertTrue(maxOf(l.canvasW, l.canvasH) >= cap * 0.97f)
        }
    }

    @Test
    fun cellsTileTheCanvasWithoutOverlap() {
        val l = GridComposite.layout(0.75f, 3072)
        assertEquals(0, l.cellX(0)); assertEquals(0, l.cellY(0))
        assertEquals(l.cellW + l.gap, l.cellX(1)); assertEquals(0, l.cellY(1))
        assertEquals(0, l.cellX(2)); assertEquals(l.cellH + l.gap, l.cellY(2))
        assertEquals(l.canvasW, l.cellX(3) + l.cellW)
        assertEquals(l.canvasH, l.cellY(3) + l.cellH)
        // Canvas keeps the cell aspect (within rounding + gap).
        assertEquals(0.75f, l.canvasW.toFloat() / l.canvasH, 0.01f)
    }

    @Test
    fun landscapeAndPortraitCellsKeepAspect() {
        val p = GridComposite.layout(0.75f, 3072)
        assertEquals(0.75f, p.cellW.toFloat() / p.cellH, 0.002f)
        assertTrue(p.canvasH in 3060..3072)
        val ls = GridComposite.layout(4f / 3f, 3072)
        assertTrue(ls.canvasW in 3060..3072)
    }

    private fun armed() = MultiShotRules.toggle(MultiShotState(), MultiMode.Grid4).state

    @Test
    fun burstStateMachine() {
        var s = armed()
        assertEquals(MultiShotState(MultiMode.Grid4, MultiExposure.Waiting), s)
        assertEquals(ShotKind.Grid, MultiShotRules.nextShot(s))
        assertEquals(1, MultiShotRules.maxOutstanding(s))
        s = MultiShotRules.beginBurst(s)
        assertEquals(MultiExposure.Burst, s.phase)
        assertEquals(ShotKind.Grid, MultiShotRules.nextShot(s))
        repeat(4) {
            assertTrue(MultiShotRules.burstWantsMore(s))
            s = MultiShotRules.burstShotTaken(s)
        }
        assertEquals(4, s.gridShots)
        assertFalse(MultiShotRules.burstWantsMore(s))
        // Never counts past 4.
        assertEquals(4, MultiShotRules.burstShotTaken(s).gridShots)
        s = MultiShotRules.endBurst(s)
        assertEquals(MultiShotState(MultiMode.Grid4, MultiExposure.Waiting, 0), s)
    }

    @Test
    fun noCancelOrToggleOnceBurstStarted() {
        val s = MultiShotRules.beginBurst(armed())
        assertFalse(MultiShotRules.canChangeMode(s))
        assertEquals(s, MultiShotRules.toggle(s, MultiMode.None).state)
        assertEquals(s, MultiShotRules.toggle(s, MultiMode.Half).state)
        assertEquals(s, MultiShotRules.cancel(s).state)
        assertFalse(MultiShotRules.cancel(s).discardedFirst)
        assertTrue(MultiShotRules.ratioLocked(s))
        // Before the burst the mode can be switched off again.
        assertEquals(MultiShotState(), MultiShotRules.toggle(armed(), MultiMode.Grid4).state)
    }

    @Test
    fun beginBurstOnlyFromArmedGrid() {
        assertEquals(MultiShotState(), MultiShotRules.beginBurst(MultiShotState()))
        val dbl = MultiShotRules.toggle(MultiShotState(), MultiMode.Double).state
        assertEquals(dbl, MultiShotRules.beginBurst(dbl))
        assertNotEquals(MultiExposure.Burst, MultiShotRules.endBurst(dbl).phase)
    }

    @Test
    fun gridSkipsRatioForcingFramesLikeHalfFrame() {
        val s = armed()
        for (f in listOf(com.vintagelab.camera.looks.FrameId.PANO_SPROCKET, com.vintagelab.camera.looks.FrameId.FILM_120, com.vintagelab.camera.looks.FrameId.INSTANT_SQUARE)) {
            assertEquals(com.vintagelab.camera.looks.FrameId.NONE, MultiShotRules.enforceFrame(s, com.vintagelab.camera.looks.FrameId.NONE, f))
        }
        assertEquals(com.vintagelab.camera.looks.FrameId.INSTANT_WHITE, MultiShotRules.enforceFrame(s, com.vintagelab.camera.looks.FrameId.NONE, com.vintagelab.camera.looks.FrameId.INSTANT_WHITE))
        assertEquals(4, MultiShotRules.GRID_SHOTS)
        assertEquals(150L, MultiShotRules.GRID_INTERVAL_MS)
    }
}
