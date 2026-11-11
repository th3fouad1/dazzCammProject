package com.vintagelab.camera.capture

/**
 * Grid burst bookkeeping shared by every ViewModel (SPEC 7.4): globally unique generations, only the newest one is
 * accepted, an aborted generation is tombstoned so a late cell cannot recreate storage, and the stored cells are
 * immutable sources until [finish] / [abort] (so an OOM retry of the final composition is idempotent).
 * Generic over the cell type so the rules are unit-testable without android.graphics.
 */
class GridBursts<B : Any>(private val recycle: (B) -> Unit) {
    private class Grid<B>(val gen: Long, val aspect: Float) {
        val cells = ArrayList<B>(GridComposite.COLS * GridComposite.ROWS)
    }

    private val lock = Any()
    private var counter = 0L
    private var latest = 0L
    private var abortedGen = 0L
    private var grid: Grid<B>? = null

    /** Starts a new generation: supersedes every older one and drops their cells. */
    fun begin(): Long = synchronized(lock) {
        val id = ++counter
        latest = id
        dropLocked()
        id
    }

    fun isLive(gen: Long): Boolean = synchronized(lock) { gen == latest && gen != abortedGen }

    /** Tombstones [gen] (if still the newest) and frees its cells. */
    fun abort(gen: Long) = synchronized(lock) {
        if (gen == latest) abortedGen = gen
        if (grid?.gen == gen) dropLocked()
    }

    /** Aborts whatever generation is current (background) and frees all cells. */
    fun abortCurrent() = synchronized(lock) {
        abortedGen = latest
        dropLocked()
    }

    /** Frees the cells of [gen] only (a stale caller never touches a newer grid). */
    fun finish(gen: Long) = synchronized(lock) { if (grid?.gen == gen) dropLocked() }

    fun aspectOf(gen: Long): Float? = synchronized(lock) { grid?.takeIf { it.gen == gen }?.aspect }

    /** Cells stored so far for a live [gen]; throws [GridAborted] if superseded, aborted or not [expected] cells long. */
    fun snapshot(gen: Long, expected: Int): List<B> = synchronized(lock) {
        val g = grid
        if (!isLive(gen) || g == null || g.gen != gen || g.cells.size != expected) throw GridAborted()
        ArrayList(g.cells)
    }

    /** Runs [block] under the store lock (cells cannot be recycled meanwhile). */
    fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }

    /**
     * Stores cell [index] of [gen]. Index 0 creates the grid, only for a live generation. A rejected [cell] is recycled
     * and [GridAborted] is thrown, so a late/superseded job never leaves anything behind.
     */
    fun add(gen: Long, index: Int, aspect: Float, cell: B) {
        synchronized(lock) {
            val ok = isLive(gen) && if (index == 0) grid?.gen != gen else grid?.gen == gen && grid?.cells?.size == index
            if (!ok) {
                recycle(cell)
                throw GridAborted()
            }
            val g = if (index == 0) Grid<B>(gen, aspect).also { dropLocked(); grid = it } else grid!!
            g.cells.add(cell)
        }
    }

    val hasCells: Boolean get() = synchronized(lock) { grid != null }

    private fun dropLocked() {
        val g = grid ?: return
        grid = null
        g.cells.forEach(recycle)
        g.cells.clear()
    }
}

/** Runs [body] at [cap], once more at [retryCap] after [onOom] on OutOfMemoryError. The body must be idempotent. */
inline fun <T> retryOnOom(cap: Int, retryCap: Int, onOom: () -> Unit, body: (Int) -> T): T =
    try {
        body(cap)
    } catch (_: OutOfMemoryError) {
        onOom()
        body(retryCap)
    }
