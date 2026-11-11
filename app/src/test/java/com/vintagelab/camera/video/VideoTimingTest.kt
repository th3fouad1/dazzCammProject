package com.vintagelab.camera.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTimingTest {
    @Test fun presentationTimestampsAreZeroBasedAndStrictlyMonotonic() {
        val p = PresentationTimestamp()
        val values = listOf(9_000_000_000L, 9_033_000_000L, 9_033_000_000L, 9_020_000_000L, 9_066_000_000L).map(p::next)
        assertEquals(0L, values.first())
        values.zipWithNext().forEach { (a, b) -> assertTrue("$a !< $b", a < b) }
        assertEquals(66_000_000L, values.last())
    }

    @Test fun audioClockUsesExactSampleCountAndCapsLead() {
        val c = AvClockAlignment(48_000, maxLeadUs = 100_000)
        assertEquals(0L, c.audioPtsUs(0))
        assertEquals(1_000_000L, c.audioPtsUs(48_000))
        assertEquals(350_000L, c.alignAudioToVideo(900_000L, 250_000L))
        assertEquals(90_000L, c.alignAudioToVideo(90_000L, 250_000L))
    }
}
