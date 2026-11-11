package com.vintagelab.camera.capture

import kotlin.math.floor
import kotlin.math.roundToInt

/** SPEC 7.4: which multi-shot capture mode is armed. */
enum class MultiMode { None, Double, Half }

/** SPEC 7.4: progress of the armed mode. Off only when the mode is [MultiMode.None]. */
enum class MultiExposure { Off, Waiting, HasFirst }

/** What the next shutter press produces. */
enum class ShotKind { Normal, First, Second }

/** Armed mode + progress; a pure value so the flows are unit-testable without Android. */
data class MultiShotState(val mode: MultiMode = MultiMode.None, val phase: MultiExposure = MultiExposure.Off)

/** Result of a reducer step: the new state and whether a stored first exposure was lost. */
data class MultiShotStep(val state: MultiShotState, val discardedFirst: Boolean = false)

/** Pure reducers for the double-exposure and half-frame flows (SPEC 7.4). */
object MultiShotRules {
    /** Toggling the active mode disarms it; toggling another mode arms that one. Any stored first is discarded. */
    fun toggle(s: MultiShotState, mode: MultiMode): MultiShotStep {
        val lost = s.phase == MultiExposure.HasFirst
        val next = if (mode == MultiMode.None || s.mode == mode) MultiShotState() else MultiShotState(mode, MultiExposure.Waiting)
        return MultiShotStep(next, lost)
    }

    fun nextShot(s: MultiShotState): ShotKind = when (s.phase) {
        MultiExposure.Off -> ShotKind.Normal
        MultiExposure.Waiting -> ShotKind.First
        MultiExposure.HasFirst -> ShotKind.Second
    }

    /** First exposure developed and stored. Ignored if the mode changed while it was developing. */
    fun onFirstStored(s: MultiShotState, jobMode: MultiMode): MultiShotStep =
        if (s.mode == jobMode && s.phase == MultiExposure.Waiting) MultiShotStep(s.copy(phase = MultiExposure.HasFirst))
        else MultiShotStep(s, discardedFirst = true)

    /** First exposure failed to develop or store: stay armed, nothing stored. */
    fun onFirstFailed(s: MultiShotState): MultiShotStep = MultiShotStep(s)

    /** Second shot saved: the stored first is consumed and the mode stays armed for the next pair. */
    fun onSecondSaved(s: MultiShotState): MultiShotStep =
        MultiShotStep(if (s.mode == MultiMode.None) s else s.copy(phase = MultiExposure.Waiting))

    /** Second shot failed. [firstDropped]: the stored first was lost (OOM, trim, missing) so the pair restarts. */
    fun onSecondFailed(s: MultiShotState, firstDropped: Boolean): MultiShotStep =
        if (firstDropped && s.phase == MultiExposure.HasFirst) MultiShotStep(s.copy(phase = MultiExposure.Waiting), discardedFirst = true)
        else MultiShotStep(s)

    /** User cancel: discard shot 1, stay armed. */
    fun cancel(s: MultiShotState): MultiShotStep =
        if (s.phase == MultiExposure.HasFirst) MultiShotStep(s.copy(phase = MultiExposure.Waiting), discardedFirst = true) else MultiShotStep(s)

    /** Backgrounding, onTrimMemory or OOM while a first exposure is held. */
    fun onDropped(s: MultiShotState): MultiShotStep = cancel(s)

    /** At most one shot in flight while armed, so shot 2 never races the storing of shot 1. */
    fun maxOutstanding(s: MultiShotState): Int = if (s.mode == MultiMode.None) 2 else 1

    /** Half-frame forces 4:3 capture (each half is cropped to 0.72) and frames that force a ratio are skipped. */
    fun ratioLocked(s: MultiShotState): Boolean = s.mode == MultiMode.Half || s.phase == MultiExposure.HasFirst

    /** Double exposure needs shot 2 to share shot 1's image-area aspect. */
    fun frameLocked(s: MultiShotState): Boolean = s.mode == MultiMode.Double && s.phase == MultiExposure.HasFirst
}

/** SPEC 7.4: half-frame diptych geometry. Two upright halves of aspect [HALF_ASPECT] side by side. */
object Diptych {
    const val HALF_ASPECT = 0.72f

    /** Gap between halves, in units of S (the half height, which is the canvas short side). */
    const val GAP = 0.015f

    /** Dark film-base colour of the gap (RGB). */
    const val GAP_COLOR = 0x101010

    data class Layout(val halfW: Int, val halfH: Int, val gap: Int) {
        val canvasW: Int get() = 2 * halfW + gap
        val canvasH: Int get() = halfH
        val rightX: Int get() = halfW + gap
    }

    /** Largest layout whose canvas long side (its width) is at most [cap] px. */
    fun layout(cap: Int): Layout {
        var h = floor(cap / (2 * HALF_ASPECT + GAP)).toInt().coerceAtLeast(2)
        while (true) {
            val l = layoutForHeight(h)
            if (l.canvasW <= cap || h <= 2) return l
            h--
        }
    }

    fun layoutForHeight(h: Int): Layout =
        Layout((h * HALF_ASPECT).roundToInt().coerceAtLeast(1), h, (h * GAP).roundToInt().coerceAtLeast(1))
}

/** SPEC 7.4: colour of the flash gel. */
enum class FlashGel(val r: Float, val g: Float, val b: Float) {
    NONE(1f, 1f, 1f),
    WARM(1.00f, 0.72f, 0.42f),
    RED(1.00f, 0.22f, 0.18f),
    BLUE(0.30f, 0.50f, 1.00f),
    GREEN(0.35f, 1.00f, 0.45f);

    fun next(): FlashGel = entries[(ordinal + 1) % entries.size]

    companion object {
        /** Strength of the overall colour cast on the whole frame (centre glow is tinted at full gel colour). */
        const val CAST = 0.18f

        /** Minimum centre glow so a gel is visible on looks without one. */
        const val MIN_GLOW = 0.35f
        const val MIN_GLOW_RADIUS = 0.55f

        /** The gel applies only when a flash actually fired (EXIF flash bit, or a screen flash on the front lens). */
        fun effective(selected: FlashGel, exifFlashFired: Boolean, screenFlashOn: Boolean): FlashGel =
            if (exifFlashFired || screenFlashOn) selected else NONE
    }
}
