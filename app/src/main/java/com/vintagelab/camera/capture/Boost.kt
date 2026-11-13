package com.vintagelab.camera.capture

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * App-made multi-frame modes (SPEC 7.7, B-003). The phone's own HDR / Night are not exposed to
 * third-party apps (Appendix G), so the app takes a short burst and merges it on the GPU.
 */
enum class BoostMode { OFF, NIGHT, HDR }

/** Burst recipe for a mode. Exposure control is internal (manual ISO / shutter stays a non-goal). */
data class BurstPlan(
    val frames: Int,
    /** Exposure compensation applied before AE/AWB lock, in EV. */
    val ev: Float,
    /** Linear gain applied after the merge (HDR lifts the underexposed merge back, shoulder protects highlights). */
    val gainEv: Float,
    /** Night: allow longer exposures by lowering the AE frame-rate floor. */
    val longExposure: Boolean,
)

object BoostRules {
    fun plan(mode: BoostMode): BurstPlan? = when (mode) {
        BoostMode.OFF -> null
        BoostMode.NIGHT -> BurstPlan(frames = 6, ev = 0f, gainEv = 0f, longExposure = true)
        BoostMode.HDR -> BurstPlan(frames = 5, ev = -1.3f, gainEv = 1.3f, longExposure = false)
    }

    fun next(mode: BoostMode): BoostMode = BoostMode.entries[(mode.ordinal + 1) % BoostMode.entries.size]

    /** A boost burst replaces the shot only for plain photos without flash. */
    fun applies(mode: BoostMode, flashOff: Boolean, multi: MultiMode): Boolean =
        mode != BoostMode.OFF && flashOff && multi == MultiMode.None

    /** Exposure-compensation index for [ev] given the camera's step and supported range. */
    fun evIndex(ev: Float, step: Float, lower: Int, upper: Int): Int {
        if (step <= 0f || ev == 0f) return 0
        return (ev / step).roundToInt().coerceIn(lower, upper)
    }

    /**
     * Night AE frame-rate range: the lowest floor that is still >= [minFloor] fps (exposures up to
     * 1/minFloor s), keeping the highest ceiling for it. Null when the camera offers nothing lower than its default.
     */
    fun nightFpsRange(ranges: List<Pair<Int, Int>>, minFloor: Int = 10): Pair<Int, Int>? =
        ranges.filter { it.first >= minFloor && it.first < 24 }
            .sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenByDescending { it.second })
            .firstOrNull()
}

/** Single-channel image for alignment (row-major, [w] x [h]). */
class Luma(val w: Int, val h: Int, val v: FloatArray) {
    init { require(v.size == w * h) }
    operator fun get(x: Int, y: Int) = v[y * w + x]

    /** 2x box downsample. */
    fun half(): Luma {
        val nw = w / 2
        val nh = h / 2
        val o = FloatArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            val i = 2 * y * w + 2 * x
            o[y * nw + x] = (v[i] + v[i + 1] + v[i + w] + v[i + w + 1]) * 0.25f
        }
        return Luma(nw, nh, o)
    }
}

/**
 * Global translation alignment by coarse-to-fine SAD search, with a parabolic sub-pixel fit at the
 * finest level. Shifts are in pixels of the level-0 image: frame(x + dx, y + dy) ~ ref(x, y).
 */
object Align {
    data class Shift(val dx: Float, val dy: Float)

    fun estimate(ref: Luma, frame: Luma, maxShift: Int = 48, levels: Int = 4): Shift {
        require(ref.w == frame.w && ref.h == frame.h)
        val refs = ArrayList<Luma>().apply { add(ref) }
        val frs = ArrayList<Luma>().apply { add(frame) }
        repeat(levels - 1) { refs += refs.last().half(); frs += frs.last().half() }
        var dx = 0
        var dy = 0
        for (l in levels - 1 downTo 0) {
            if (l != levels - 1) { dx *= 2; dy *= 2 }
            // Wide search only at the coarsest level; finer levels refine by +-1 px on a sparse grid.
            val r = if (l == levels - 1) (maxShift shr l).coerceAtLeast(2) else 1
            val stride = if (l <= 1) 2 else 1
            var best = Float.MAX_VALUE
            var bx = dx
            var by = dy
            for (sy in dy - r..dy + r) for (sx in dx - r..dx + r) {
                val c = sad(refs[l], frs[l], sx, sy, stride)
                if (c < best) { best = c; bx = sx; by = sy }
            }
            dx = bx; dy = by
        }
        val c0 = sad(ref, frame, dx, dy, 2)
        val fx = subPixel(sad(ref, frame, dx - 1, dy, 2), c0, sad(ref, frame, dx + 1, dy, 2))
        val fy = subPixel(sad(ref, frame, dx, dy - 1, 2), c0, sad(ref, frame, dx, dy + 1, 2))
        return Shift(dx + fx, dy + fy)
    }

    /** Mean absolute difference over the overlap, sampled every [stride] px; MAX if the overlap is too small. */
    fun sad(ref: Luma, frame: Luma, sx: Int, sy: Int, stride: Int = 1): Float {
        val x0 = maxOf(0, -sx)
        val y0 = maxOf(0, -sy)
        val x1 = minOf(ref.w, frame.w - sx)
        val y1 = minOf(ref.h, frame.h - sy)
        if (x1 - x0 < ref.w / 2 || y1 - y0 < ref.h / 2) return Float.MAX_VALUE
        var s = 0f
        var n = 0
        var y = y0
        while (y < y1) {
            var x = x0
            val ro = y * ref.w
            val fo = (y + sy) * frame.w + sx
            while (x < x1) { s += abs(ref.v[ro + x] - frame.v[fo + x]); n++; x += stride }
            y += stride
        }
        return s / n
    }

    private fun subPixel(cm: Float, c0: Float, cp: Float): Float {
        if (cm == Float.MAX_VALUE || cp == Float.MAX_VALUE) return 0f
        val d = cm - 2f * c0 + cp
        if (d <= 1e-6f) return 0f
        return (0.5f * (cm - cp) / d).coerceIn(-0.5f, 0.5f)
    }
}
