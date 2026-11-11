package com.vintagelab.camera.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTimingTest {
    @Test fun videoPtsIsRelativeToSharedEpochAndStrictlyMonotonic() {
        val epoch = 9_000_000_000L
        val p = PresentationTimestamp(epoch)
        val values = listOf(9_040_000_000L, 9_073_000_000L, 9_073_000_000L, 9_060_000_000L, 9_106_000_000L).map(p::next)
        assertEquals(40_000_000L, values.first()) // not rebased to zero: keeps offset from epoch
        values.zipWithNext().forEach { (a, b) -> assertTrue("$a !< $b", a < b) }
        assertEquals(106_000_000L, values.last())
    }

    @Test fun framesBeforeEpochAreDropped() {
        val p = PresentationTimestamp(1_000_000_000L)
        assertEquals(PresentationTimestamp.DROP, p.next(990_000_000L))
        assertEquals(10_000_000L, p.next(1_010_000_000L))
    }

    @Test fun foreignTimebaseReanchorsOnFirstFrame() {
        val p = PresentationTimestamp(1_000_000_000L)
        assertEquals(0L, p.next(900_000_000_000L))
        assertEquals(33_000_000L, p.next(900_033_000_000L))
    }

    @Test fun audioFallbackAnchorsFrameCountToStartTime() {
        val m = AudioPtsMapper(epochNs = 1_000_000_000L, sampleRate = 48_000)
        m.anchorStart(1_020_000_000L)
        assertEquals(20_000L, m.ptsUs(0))
        assertEquals(1_020_000L, m.ptsUs(48_000))
    }

    @Test fun audioUsesDriverTimestampWhenAvailable() {
        val m = AudioPtsMapper(epochNs = 1_000_000_000L, sampleRate = 48_000)
        m.anchorStart(5_000_000_000L) // ignored when a timestamp exists
        // frame 48000 was captured at t=2.05s; frame 96000 is one second later
        assertEquals(1_050_000L + 1_000_000L, m.ptsUs(96_000, 48_000, 2_050_000_000L))
    }

    @Test fun audioBeforeEpochDroppedAndOutputMonotonic() {
        val m = AudioPtsMapper(epochNs = 1_000_000_000L, sampleRate = 48_000)
        m.anchorStart(900_000_000L)
        assertEquals(AudioPtsMapper.DROP, m.ptsUs(0))
        assertEquals(0L, m.ptsUs(4_800)) // exactly at the epoch is kept
        assertEquals(100_000L, m.ptsUs(9_600))
    }

    @Test fun audioRegressingTimestampsStayMonotonic() {
        val m = AudioPtsMapper(epochNs = 0L, sampleRate = 48_000)
        val a = m.ptsUs(0, 0, 100_000_000L)
        val b = m.ptsUs(1_024, 1_024, 100_000_000L) // jitter: timestamp says earlier
        val c = m.ptsUs(2_048, 2_048, 90_000_000L)
        assertTrue(a < b && b < c)
    }

    @Test fun videoAndAudioShareOneClock() {
        val epoch = 50_000_000_000L
        val v = PresentationTimestamp(epoch)
        val a = AudioPtsMapper(epoch, 48_000).apply { anchorStart(epoch + 30_000_000L) }
        // same instant (epoch + 60ms) maps to the same time on both tracks
        assertEquals(60_000L, v.next(epoch + 60_000_000L) / 1_000L)
        assertEquals(60_000L, a.ptsUs(1_440))
    }

    @Test fun cleanupScopeReleasesInReverseOrderUnlessCommitted() {
        val log = ArrayList<String>()
        val scope = CleanupScope()
        scope.add<String>({ log += "rel $it" }) { "video" }
        scope.add<String>({ log += "rel $it" }) { "audio" }
        try {
            scope.add<String>({ log += "rel $it" }) { error("createPending failed") }
        } catch (_: IllegalStateException) {
            scope.releaseAll()
        }
        assertEquals(listOf("rel audio", "rel video"), log)
        scope.releaseAll()
        assertEquals(2, log.size) // idempotent

        val ok = CleanupScope()
        val kept = ArrayList<String>()
        ok.add<String>({ kept += it }) { "x" }
        ok.commit(); ok.releaseAll()
        assertTrue(kept.isEmpty())
    }

    @Test fun teardownDetachesBeforeFinalizeAndRunsOnce() {
        val log = ArrayList<String>()
        var cb: (() -> Unit)? = null
        val t = RecordingTeardown(detachEgl = { log += "detach"; cb = it }, finalize = { log += "finalize" })
        assertTrue(t.request())
        assertFalse(t.request())
        assertEquals(listOf("detach"), log)
        cb!!(); cb!!(); t.onDetached()
        assertEquals(listOf("detach", "finalize"), log)
        assertFalse(t.request())
        assertTrue(t.isFinalized)
    }

    @Test fun teardownFinalizesEvenIfDetachThrowsOrIsForced() {
        var n = 0
        val t = RecordingTeardown(detachEgl = { error("gl gone") }, finalize = { n++ })
        t.request(); t.onDetached()
        assertEquals(1, n)
        var m = 0
        RecordingTeardown(detachEgl = { }, finalize = { m++ }).apply { request(); onDetached(); onDetached() }
        assertEquals(1, m)
    }
}
