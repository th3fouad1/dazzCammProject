package com.vintagelab.camera.negatives

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NegativeLruTest {
    private fun e(id: Long, bytes: Long = 10, used: Long = id) = NegativeEntry(id, bytes, used)

    @Test fun underBothCapsKeepsEverything() {
        assertTrue(NegativeLru.evictions((1L..5L).map { e(it) }, maxCount = 5, maxBytes = 50).isEmpty())
    }

    @Test fun countCapDropsLeastRecentlyUsed() {
        val list = (1L..7L).map { e(it) }
        assertEquals(listOf(1L, 2L), NegativeLru.evictions(list, maxCount = 5, maxBytes = Long.MAX_VALUE))
    }

    @Test fun byteCapDropsOldestUntilItFits() {
        val list = listOf(e(1, 400), e(2, 400), e(3, 400))
        assertEquals(listOf(1L), NegativeLru.evictions(list, maxCount = 200, maxBytes = 800))
        assertEquals(listOf(1L, 2L), NegativeLru.evictions(list, maxCount = 200, maxBytes = 400))
    }

    @Test fun recentUseProtectsAnOldEntry() {
        val list = listOf(e(1, used = 100), e(2, used = 2), e(3, used = 3))
        assertEquals(listOf(2L), NegativeLru.evictions(list, maxCount = 2, maxBytes = Long.MAX_VALUE))
    }

    @Test fun tiesBreakByIdAndInputOrderDoesNotMatter() {
        val list = listOf(e(3, used = 5), e(1, used = 5), e(2, used = 5))
        assertEquals(listOf(1L), NegativeLru.evictions(list, maxCount = 2, maxBytes = Long.MAX_VALUE))
    }

    @Test fun defaultsAreTheSpecCaps() {
        assertEquals(200, NegativeLru.MAX_COUNT)
        assertEquals(1_500_000_000L, NegativeLru.MAX_BYTES)
        val list = (1L..201L).map { e(it) }
        assertEquals(listOf(1L), NegativeLru.evictions(list))
    }
}
