package com.vintagelab.camera.capture

import com.vintagelab.camera.looks.PrismMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** SPEC 7.4 grid burst: generations, abort tombstone, idempotent final retry, prism rule. */
class GridBurstsTest {
    private class Cell { var recycled = false }

    private fun bursts() = GridBursts<Cell> { it.recycled = true }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("expected GridAborted") } catch (_: GridAborted) { }
    }

    @Test
    fun generationsAreUniqueAndOnlyNewestIsAccepted() {
        val b = bursts()
        val old = b.begin()
        val c0 = Cell()
        b.add(old, 0, 0.75f, c0)
        val new = b.begin()
        assertTrue(new > old)
        assertFalse(b.isLive(old))
        assertTrue(b.isLive(new))
        // Superseding freed the older burst's cell and its late cells are refused (and recycled).
        assertTrue(c0.recycled)
        val late = Cell()
        rejected { b.add(old, 1, 0.75f, late) }
        assertTrue(late.recycled)
        assertNull(b.aspectOf(old))
        rejected { b.snapshot(old, 0) }
        b.add(new, 0, 0.5f, Cell())
        assertEquals(0.5f, b.aspectOf(new))
    }

    @Test
    fun abortTombstoneBlocksLateIndexZero() {
        val b = bursts()
        val g = b.begin()
        b.abort(g)
        assertFalse(b.isLive(g))
        val late = Cell()
        rejected { b.add(g, 0, 0.75f, late) }
        assertTrue(late.recycled)
        assertFalse(b.hasCells)
    }

    @Test
    fun abortCurrentDropsEveryBitmap() {
        val b = bursts()
        val g = b.begin()
        val cells = List(3) { Cell() }
        cells.forEachIndexed { i, c -> b.add(g, i, 1f, c) }
        b.abortCurrent()
        assertTrue(cells.all { it.recycled })
        assertFalse(b.hasCells)
        assertFalse(b.isLive(g))
        rejected { b.add(g, 3, 1f, Cell()) }
        // A new burst after the abort works again.
        assertTrue(b.isLive(b.begin()))
    }

    @Test
    fun outOfOrderCellIsRefused() {
        val b = bursts()
        val g = b.begin()
        rejected { b.add(g, 1, 1f, Cell()) }
        b.add(g, 0, 1f, Cell())
        rejected { b.add(g, 2, 1f, Cell()) }
        rejected { b.add(g, 0, 1f, Cell()) }
    }

    @Test
    fun finalComposeRetryIsIdempotent() {
        val b = bursts()
        val g = b.begin()
        val cells = List(3) { Cell() }
        cells.forEachIndexed { i, c -> b.add(g, i, 1f, c) }
        val caps = ArrayList<Int>()
        val composedFrom = ArrayList<List<Cell>>()
        var trimmed = false
        val out = retryOnOom(4096, CapturePipeline.RETRY_LONG_SIDE, onOom = { trimmed = true }) { cap ->
            caps += cap
            val sources = b.snapshot(g, 3)
            composedFrom += sources
            if (cap == 4096) throw OutOfMemoryError("injected")
            "saved@$cap"
        }
        assertEquals("saved@2048", out)
        assertTrue(trimmed)
        assertEquals(listOf(4096, 2048), caps)
        // Same three sources both times, none recycled by the failed attempt.
        assertEquals(composedFrom[0], composedFrom[1])
        assertTrue(cells.none { it.recycled })
        // Only the terminal step frees them.
        b.finish(g)
        assertTrue(cells.all { it.recycled })
    }

    @Test
    fun terminalOomKeepsNothingAfterFinish() {
        val b = bursts()
        val g = b.begin()
        val cells = List(3) { Cell() }
        cells.forEachIndexed { i, c -> b.add(g, i, 1f, c) }
        try {
            retryOnOom(4096, 2048, onOom = {}) { b.snapshot(g, 3); throw OutOfMemoryError() }
            fail()
        } catch (_: OutOfMemoryError) { }
        assertTrue(cells.none { it.recycled })
        b.finish(g)
        assertTrue(cells.all { it.recycled })
    }

    @Test
    fun staleFinishDoesNotTouchNewerGrid() {
        val b = bursts()
        val old = b.begin()
        val new = b.begin()
        val c = Cell()
        b.add(new, 0, 1f, c)
        b.finish(old); b.abort(old)
        assertFalse(c.recycled)
        assertTrue(b.isLive(new))
    }

    @Test
    fun prismIsOffWhileCompositeModesAreArmed() {
        assertEquals(PrismMode.OFF, MultiShotRules.effectivePrism(MultiMode.Grid4, PrismMode.SPLIT))
        assertEquals(PrismMode.OFF, MultiShotRules.effectivePrism(MultiMode.Half, PrismMode.TRI))
        assertEquals(PrismMode.HALO, MultiShotRules.effectivePrism(MultiMode.None, PrismMode.HALO))
        assertEquals(PrismMode.SPLIT, MultiShotRules.effectivePrism(MultiMode.Double, PrismMode.SPLIT))
    }
}
