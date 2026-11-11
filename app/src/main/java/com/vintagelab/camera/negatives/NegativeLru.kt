package com.vintagelab.camera.negatives

data class NegativeEntry(val id: Long, val bytes: Long, val lastUsedMs: Long)

/** Storage cap (SPEC 6.9): newest 200 negatives and 1.5 GB, least recently used go first. */
object NegativeLru {
    const val MAX_COUNT = 200
    const val MAX_BYTES = 1_500_000_000L

    /** Ids to delete so that at most [maxCount] entries and [maxBytes] bytes remain. */
    fun evictions(entries: List<NegativeEntry>, maxCount: Int = MAX_COUNT, maxBytes: Long = MAX_BYTES): List<Long> {
        val order = entries.sortedWith(compareBy<NegativeEntry> { it.lastUsedMs }.thenBy { it.id })
        var count = order.size
        var bytes = order.sumOf { it.bytes }
        val out = ArrayList<Long>()
        for (e in order) {
            if (count <= maxCount && bytes <= maxBytes) break
            out += e.id
            count--
            bytes -= e.bytes
        }
        return out
    }
}
