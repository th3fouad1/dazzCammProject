package com.vintagelab.camera.ui

import com.vintagelab.camera.camera.FlashMode
import com.vintagelab.camera.camera.Lens
import com.vintagelab.camera.capture.CaptureErrorKind
import com.vintagelab.camera.capture.CapturePipeline
import com.vintagelab.camera.capture.FrameGeometry
import com.vintagelab.camera.looks.AspectRatio
import com.vintagelab.camera.looks.FrameId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ChooseModeTest {
    private data class Row(val w: Float, val h: Float, val portrait: Boolean, val mode: LayoutMode, val vf43: Pair<Float, Float>)

    /** DESIGN §4.3 (4:3 column). */
    private val table = listOf(
        Row(412f, 859f, true, LayoutMode.Regular, 412f to 549f),
        Row(360f, 728f, true, LayoutMode.Regular, 342f to 456f),
        Row(360f, 560f, true, LayoutMode.Compact, 252f to 336f),
        Row(859f, 412f, false, LayoutMode.Regular, 549f to 412f),
        Row(412f, 390f, true, LayoutMode.Mini, 292f to 390f),
        Row(673f, 841f, true, LayoutMode.Regular, 427f to 569f),
        Row(1232f, 800f, false, LayoutMode.Regular, 952f to 714f),
        Row(480f, 360f, false, LayoutMode.Mini, 392f to 294f),
    )

    @Test
    fun designTable() {
        for (r in table) {
            val mode = LayoutMath.chooseMode(r.w, r.h, r.portrait)
            assertEquals("$r", r.mode, mode)
            val aspect = FrameGeometry.canvasAspect(AspectRatio.R4_3, FrameId.NONE, r.portrait)
            val vf = LayoutMath.contain(LayoutMath.slot(r.w, r.h, mode), aspect)
            assertEquals("$r w", r.vf43.first, vf.w, 1.5f)
            assertEquals("$r h", r.vf43.second, vf.h, 1.5f)
        }
    }

    @Test
    fun canvasAspect_includesFrameMargin() {
        // 4:3 portrait + instant: (S + 0.12 S) / (4/3 S + 0.28 S)
        assertEquals(1.12f / (4f / 3f + 0.28f), FrameGeometry.canvasAspect(AspectRatio.R4_3, FrameId.INSTANT_WHITE, true), 0.002f)
        assertEquals(1f / (16f / 9f + 0.16f), FrameGeometry.canvasAspect(AspectRatio.R16_9, FrameId.FILM_STRIP, true), 0.002f)
        val r = FrameGeometry.imageRectInCanvas(AspectRatio.R1_1, FrameId.INSTANT_WHITE, true)
        assertEquals(0.06f / 1.12f, r.x, 0.002f)
        assertEquals(1f / 1.28f, r.w, 0.002f)
    }
}

class FlashCycleTest {
    @Test
    fun backCycle() {
        var f = FlashMode.OFF
        val seen = (0 until 3).map { f = CameraRules.nextFlash(f, Lens.BACK); f }
        assertEquals(listOf(FlashMode.AUTO, FlashMode.ON, FlashMode.OFF), seen)
    }

    @Test
    fun frontSkipsAuto() {
        assertEquals(FlashMode.ON, CameraRules.nextFlash(FlashMode.OFF, Lens.FRONT))
        assertEquals(FlashMode.OFF, CameraRules.nextFlash(FlashMode.ON, Lens.FRONT))
        assertEquals(FlashMode.OFF, CameraRules.nextFlash(FlashMode.AUTO, Lens.FRONT))
    }
}

class EnablementTest {
    private val ready = CameraUiState(camera = CameraStatus.Ready)

    @Test
    fun matrix() {
        assertTrue(CameraRules.lensRatioEnabled(ready))
        assertTrue(CameraRules.shutterEnabled(ready))
        val cd = ready.copy(captureState = CaptureState.Countdown(3))
        assertFalse(CameraRules.lensRatioEnabled(cd))
        assertFalse(CameraRules.timerEnabled(cd))
        assertTrue(CameraRules.shutterEnabled(cd)) // = cancel
        val busy = ready.copy(captureState = CaptureState.Processing)
        assertFalse(CameraRules.lensRatioEnabled(busy))
        assertFalse(CameraRules.shutterEnabled(busy))
        assertTrue(CameraRules.timerEnabled(busy))
        val opening = CameraUiState(camera = CameraStatus.Opening)
        assertFalse(CameraRules.shutterEnabled(opening))
        assertFalse(CameraRules.lensRatioEnabled(opening))
    }

    @Test
    fun permissionRules() {
        assertEquals(PermissionState.Granted, CameraRules.onPermissionResult(true, false))
        assertEquals(PermissionState.Denied, CameraRules.onPermissionResult(false, true))
        assertEquals(PermissionState.PermanentlyDenied, CameraRules.onPermissionResult(false, false))
        // refresh never flips Denied <-> PermanentlyDenied
        assertEquals(PermissionState.PermanentlyDenied, CameraRules.onRefreshPermission(PermissionState.PermanentlyDenied, false))
        assertEquals(PermissionState.Denied, CameraRules.onRefreshPermission(PermissionState.Granted, false))
        assertEquals(PermissionState.Granted, CameraRules.onRefreshPermission(PermissionState.Denied, true))
    }
}

class SwipeDirectionTest {
    @Test
    fun rules() {
        assertEquals(3, SwipeRules.target(-70f, -700f, false, 2, 6)) // toward start = next
        assertEquals(1, SwipeRules.target(130f, 10f, false, 2, 6)) // far travel at any speed
        assertNull(SwipeRules.target(-70f, -100f, false, 2, 6)) // too slow and short
        assertNull(SwipeRules.target(-200f, -900f, true, 2, 6)) // system gesture start
        assertEquals(5, SwipeRules.target(-200f, -900f, false, 5, 6)) // no wrap
        assertEquals(0, SwipeRules.target(200f, 900f, false, 0, 6))
    }
}

class ZoomMathTest {
    @Test
    fun roundTripAndEnds() {
        assertEquals(0f, ZoomMath.linearFromRatio(1f, 1f, 8f), 1e-5f)
        assertEquals(1f, ZoomMath.linearFromRatio(8f, 1f, 8f), 1e-5f)
        for (r in listOf(0.6f, 1f, 2f, 5f)) {
            val f = ZoomMath.linearFromRatio(r, 0.6f, 10f)
            assertEquals(r, ZoomMath.ratioFromLinear(f, 0.6f, 10f), 1e-3f)
        }
        assertEquals("1.0", ZoomMath.format(1f))
    }
}

class CaptureBudgetTest {
    @Test
    fun capFromMemoryClass() {
        assertEquals(4096, CapturePipeline.capForMemory(512))
        assertEquals(4096, CapturePipeline.capForMemory(256))
        assertTrue(CapturePipeline.capForMemory(128) in 2048..4095)
        assertEquals(2048, CapturePipeline.capForMemory(32))
    }

    @Test
    fun errorClassification() {
        assertEquals(CaptureErrorKind.StorageFull, CapturePipeline.classify(IOException("write failed: ENOSPC (No space left on device)")).kind)
        assertEquals(CaptureErrorKind.SaveFailed, CapturePipeline.classify(IOException("clearing IS_PENDING updated 0 rows")).kind)
        assertEquals(CaptureErrorKind.DevelopFailed, CapturePipeline.classify(IllegalStateException("GL")).kind)
    }
}
