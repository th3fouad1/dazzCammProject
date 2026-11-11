package com.vintagelab.camera.ui

import com.vintagelab.camera.ui.theme.ChromeMetrics
import kotlin.math.abs

enum class LayoutMode { Regular, Compact, Mini }

/** A size in dp. */
data class DpSize2(val w: Float, val h: Float)

/** Pure layout rules of DESIGN §4.1–4.2 (unit tested against the §4.3 table). */
object LayoutMath {
    fun windowLandscape(safeW: Float, safeH: Float) = safeW > safeH

    fun metrics(mode: LayoutMode, landscape: Boolean): ChromeMetrics = when (mode) {
        LayoutMode.Regular -> if (landscape) ChromeMetrics.RegularLandscape else ChromeMetrics.RegularPortrait
        LayoutMode.Compact -> ChromeMetrics.Compact
        LayoutMode.Mini -> ChromeMetrics.Mini
    }

    /** The slot left for the viewfinder once the chrome of [mode] is placed. */
    fun slot(safeW: Float, safeH: Float, mode: LayoutMode): DpSize2 {
        val landscape = windowLandscape(safeW, safeH)
        val chrome = metrics(mode, landscape).total
        return if (landscape) DpSize2(maxOf(0f, safeW - chrome), safeH) else DpSize2(safeW, maxOf(0f, safeH - chrome))
    }

    /** Largest rect of [aspect] (w/h) contained in [slot]. */
    fun contain(slot: DpSize2, aspect: Float): DpSize2 =
        if (slot.w / slot.h > aspect) DpSize2(slot.h * aspect, slot.h) else DpSize2(slot.w, slot.w / aspect)

    /** First of Regular → Compact → Mini whose reference 4:3 viewfinder covers ≥ 40 % of the safe area. */
    fun chooseMode(safeW: Float, safeH: Float, imagePortrait: Boolean): LayoutMode {
        val ref = if (imagePortrait) 3f / 4f else 4f / 3f
        for (mode in listOf(LayoutMode.Regular, LayoutMode.Compact)) {
            val vf = contain(slot(safeW, safeH, mode), ref)
            if (vf.w * vf.h >= 0.4f * safeW * safeH) return mode
        }
        return LayoutMode.Mini
    }
}

/** DESIGN §10.4 K1: CameraX's linear zoom is linear in 1/ratio (field of view). */
object ZoomMath {
    fun linearFromRatio(r: Float, min: Float, max: Float): Float {
        if (max <= min) return 0f
        return ((1f / r - 1f / min) / (1f / max - 1f / min)).coerceIn(0f, 1f)
    }

    fun ratioFromLinear(f: Float, min: Float, max: Float): Float {
        if (max <= min) return min
        return 1f / (1f / min + f.coerceIn(0f, 1f) * (1f / max - 1f / min))
    }

    fun format(r: Float): String = String.format(java.util.Locale.ROOT, "%.1f", r)
}

/** DESIGN §5.4 viewfinder swipe → look index. */
object SwipeRules {
    const val MIN_TRAVEL = 64f
    const val MIN_VELOCITY = 600f
    const val FAR_TRAVEL = 120f

    /**
     * @param travel signed travel along the carousel axis in dp (negative = toward start/top).
     * @return the new index (unchanged at either end: no wrap), or null when it isn't a swipe.
     */
    fun target(travel: Float, velocity: Float, startsInSystemGesture: Boolean, index: Int, count: Int): Int? {
        if (startsInSystemGesture || count == 0) return null
        val a = abs(travel)
        val isSwipe = (a >= MIN_TRAVEL && abs(velocity) >= MIN_VELOCITY) || a >= FAR_TRAVEL
        if (!isSwipe) return null
        val next = if (travel < 0) index + 1 else index - 1
        return next.coerceIn(0, count - 1)
    }
}
