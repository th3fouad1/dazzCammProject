package com.vintagelab.camera.capture

import com.vintagelab.camera.looks.FrameId
import com.vintagelab.camera.looks.LookState
import com.vintagelab.camera.looks.TestAssets
import com.vintagelab.camera.looks.UniformMapper
import com.vintagelab.camera.looks.Vec2
import com.vintagelab.camera.looks.Vec4
import com.vintagelab.camera.ui.CameraRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureModesTest {
    private val off = MultiShotState()

    private fun armed(mode: MultiMode) = MultiShotRules.toggle(off, mode).state

    @Test
    fun doubleExposureFlow() {
        var s = armed(MultiMode.Double)
        assertEquals(MultiShotState(MultiMode.Double, MultiExposure.Waiting), s)
        assertEquals(ShotKind.First, MultiShotRules.nextShot(s))
        s = MultiShotRules.onFirstStored(s, MultiMode.Double).state
        assertEquals(MultiExposure.HasFirst, s.phase)
        assertEquals(ShotKind.Second, MultiShotRules.nextShot(s))
        s = MultiShotRules.onSecondSaved(s).state
        // Stays armed for the next pair.
        assertEquals(MultiShotState(MultiMode.Double, MultiExposure.Waiting), s)
    }

    @Test
    fun halfFrameFlow() {
        var s = armed(MultiMode.Half)
        s = MultiShotRules.onFirstStored(s, MultiMode.Half).state
        assertEquals(MultiShotState(MultiMode.Half, MultiExposure.HasFirst), s)
        s = MultiShotRules.onSecondSaved(s).state
        assertEquals(MultiExposure.Waiting, s.phase)
    }

    @Test
    fun normalShotsWhenOff() {
        assertEquals(ShotKind.Normal, MultiShotRules.nextShot(off))
        assertEquals(2, MultiShotRules.maxOutstanding(off))
        assertEquals(1, MultiShotRules.maxOutstanding(armed(MultiMode.Double)))
    }

    @Test
    fun cancelDiscardsFirstAndStaysArmed() {
        val has = MultiShotRules.onFirstStored(armed(MultiMode.Double), MultiMode.Double).state
        val step = MultiShotRules.cancel(has)
        assertTrue(step.discardedFirst)
        assertEquals(MultiShotState(MultiMode.Double, MultiExposure.Waiting), step.state)
        assertFalse(MultiShotRules.cancel(step.state).discardedFirst)
    }

    @Test
    fun toggleSwitchesOrDisarmsAndDiscards() {
        val has = MultiShotRules.onFirstStored(armed(MultiMode.Double), MultiMode.Double).state
        val toHalf = MultiShotRules.toggle(has, MultiMode.Half)
        assertTrue(toHalf.discardedFirst)
        assertEquals(MultiShotState(MultiMode.Half, MultiExposure.Waiting), toHalf.state)
        assertEquals(off, MultiShotRules.toggle(toHalf.state, MultiMode.Half).state)
    }

    @Test
    fun oomOrBackgroundDropRestartsThePair() {
        val has = MultiShotRules.onFirstStored(armed(MultiMode.Half), MultiMode.Half).state
        val dropped = MultiShotRules.onDropped(has)
        assertTrue(dropped.discardedFirst)
        assertEquals(MultiExposure.Waiting, dropped.state.phase)
        // Second shot failed because the first was lost (OOM): back to Waiting.
        val failed = MultiShotRules.onSecondFailed(has, firstDropped = true)
        assertEquals(MultiExposure.Waiting, failed.state.phase)
        assertTrue(failed.discardedFirst)
        // A plain save failure keeps the first so the user can retry shot 2.
        val kept = MultiShotRules.onSecondFailed(has, firstDropped = false)
        assertEquals(MultiExposure.HasFirst, kept.state.phase)
        assertFalse(kept.discardedFirst)
    }

    @Test
    fun staleFirstIsDiscardedWhenModeChangedMeanwhile() {
        val step = MultiShotRules.onFirstStored(off, MultiMode.Double)
        assertEquals(off, step.state)
        assertTrue(step.discardedFirst)
        val other = MultiShotRules.onFirstStored(armed(MultiMode.Half), MultiMode.Double)
        assertEquals(MultiExposure.Waiting, other.state.phase)
        assertTrue(other.discardedFirst)
    }

    @Test
    fun failedFirstStaysWaiting() {
        val s = armed(MultiMode.Double)
        assertEquals(s, MultiShotRules.onFirstFailed(s).state)
    }

    @Test
    fun locks() {
        assertTrue(MultiShotRules.ratioLocked(armed(MultiMode.Half)))
        assertFalse(MultiShotRules.ratioLocked(armed(MultiMode.Double)))
        val has = MultiShotRules.onFirstStored(armed(MultiMode.Double), MultiMode.Double).state
        assertTrue(MultiShotRules.ratioLocked(has))
        assertTrue(MultiShotRules.frameLocked(has))
        val halfHas = MultiShotRules.onFirstStored(armed(MultiMode.Half), MultiMode.Half).state
        assertFalse(MultiShotRules.frameLocked(halfHas))
    }

    @Test
    fun halfFrameFrameCycleSkipsForcedRatioFrames() {
        var f = FrameId.NONE
        val seen = HashSet<FrameId>()
        repeat(FrameId.entries.size) { f = CameraRules.nextFrame(f, half = true); seen += f }
        assertTrue(seen.all { FrameGeometry.forcedAspect(it, true) == null })
        assertEquals(FrameId.entries[(FrameId.NONE.ordinal + 1) % FrameId.entries.size], CameraRules.nextFrame(FrameId.NONE, half = false))
    }

    // ---- diptych geometry ----

    @Test
    fun diptychFitsCapAndKeepsHalfAspectAndGap() {
        for (cap in listOf(2048, 3072, 4096)) {
            val l = Diptych.layout(cap)
            assertTrue("canvas within cap", l.canvasW <= cap)
            assertTrue("fills the cap", l.canvasW >= cap - 3)
            assertEquals(Diptych.HALF_ASPECT, l.halfW.toFloat() / l.halfH, 0.001f)
            assertEquals(Diptych.GAP, l.gap.toFloat() / l.halfH, 0.001f)
            assertEquals(2 * l.halfW + l.gap, l.canvasW)
            assertEquals(l.halfH, l.canvasH)
            assertEquals(l.halfW + l.gap, l.rightX)
        }
    }

    @Test
    fun diptychExactNumbers() {
        val l = Diptych.layoutForHeight(1000)
        assertEquals(720, l.halfW)
        assertEquals(15, l.gap)
        assertEquals(1455, l.canvasW)
        assertEquals(1000, l.canvasH)
    }

    // ---- gel ----

    @Test
    fun gelAppliesOnlyWhenFlashFired() {
        assertEquals(FlashGel.RED, FlashGel.effective(FlashGel.RED, exifFlashFired = true, screenFlashOn = false))
        assertEquals(FlashGel.BLUE, FlashGel.effective(FlashGel.BLUE, exifFlashFired = false, screenFlashOn = true))
        assertEquals(FlashGel.NONE, FlashGel.effective(FlashGel.RED, exifFlashFired = false, screenFlashOn = false))
        assertEquals(FlashGel.NONE, FlashGel.entries.last().next())
    }

    @Test
    fun gelTintsCentreGlowAndAddsCast() {
        val p = TestAssets.presets().first { it.id == "classic35" }
        val plain = UniformMapper.map(p, LookState(1L, FrameId.NONE, false))
        assertEquals(0f, plain.gel.w)
        val gel = UniformMapper.map(p, LookState(1L, FrameId.NONE, false, gel = FlashGel.RED))
        assertEquals(Vec4(FlashGel.RED.r, FlashGel.RED.g, FlashGel.RED.b, 1f), gel.gel)
        assertTrue(gel.centerGlow.x >= FlashGel.MIN_GLOW)
        assertTrue(gel.centerGlow.y >= FlashGel.MIN_GLOW_RADIUS)
        assertNotEquals(plain.centerGlow, gel.centerGlow)
        assertEquals(plain.vignette, gel.vignette)
    }

    @Test
    fun firstExposureUniformsDropPerImageEffects() {
        val p = TestAssets.presets().first { it.id == "classic35" }
        val u = UniformMapper.firstExposure(UniformMapper.map(p, LookState(7L, FrameId.NONE, true, gel = FlashGel.WARM)))
        assertEquals(0f, u.grain.x)
        assertEquals(0f, u.vignette.x)
        assertEquals(Vec2(0f, u.centerGlow.y), u.centerGlow)
        assertEquals(0f, u.leak.x)
        assertEquals(0f, u.dust)
        assertEquals(0f, u.stampOn)
        assertEquals(0f, u.gel.w)
    }
}
