package com.vintagelab.camera.looks

import com.vintagelab.camera.capture.DateStamp
import com.vintagelab.camera.capture.FrameGeometry
import com.vintagelab.camera.capture.FlashGel
import com.vintagelab.camera.capture.FrameLayout
import kotlin.math.PI

data class Vec2(val x: Float, val y: Float)
data class Vec3(val x: Float, val y: Float, val z: Float)
data class Vec4(val x: Float, val y: Float, val z: Float, val w: Float)

/** Per-shot (or per-preview-session) state that is not part of the preset. */
data class LookState(
    val seed: Long,
    val frame: FrameId,
    val dateStampOn: Boolean,
    /** 8 glyph codes from [DateStamp.codes]. */
    val stampCodes: List<Int> = List(DateStamp.SLOTS) { DateStamp.GLYPH_BLANK },
    val grainTime: Float = 0f,
    /** Flash gel applied to this shot (capture only; NONE in the preview). */
    val gel: FlashGel = FlashGel.NONE,
)

/**
 * Every uniform of SPEC §7.2 (names in comments), plus the Pass 4 layout uniforms derived from
 * [FrameGeometry]. Geometry fields are filled by [UniformMapper.withGeometry] per render target.
 */
data class UniformSet(
    val cropRect: Vec4 = Vec4(0f, 0f, 1f, 1f),        // uCropRect (source uv x,y,w,h)
    val shortSide: Float = 1f,                         // uShortSide (px, image area)
    val outSize: Vec2 = Vec2(1f, 1f),                  // uOutSize (px, image area)
    val lutStrength: Float = 1f,                       // uLutStrength
    val grain: Vec3 = Vec3(0f, 0.0012f, 0f),           // uGrain (amount, size, chroma)
    val grainTime: Float = 0f,                         // uGrainTime
    val vignette: Vec4 = Vec4(0f, 1f, 0.5f, 0f),       // uVignette (amount, radius, softness, shape)
    val centerGlow: Vec2 = Vec2(0f, 0.5f),             // uCenterGlow (amount, radius)
    val softFocus: Float = 0f,                         // uSoftFocus
    val bloom: Vec2 = Vec2(0f, 0.8f),                  // uBloom (amount, threshold)
    val sharpen: Float = 0f,                           // uSharpen
    val distortion: Vec2 = Vec2(0f, 0f),               // uDistortion (barrel, circularMask)
    val leak: Vec4 = Vec4(0f, 0f, 0f, 0f),             // uLeak (enabled, variant, amount, angle)
    val leakPos: Vec2 = Vec2(0f, 0f),                  // uLeakPos (image uv, y down)
    val dust: Float = 0f,                              // uDust
    val seed: Float = 0f,                              // uSeed [0,1)
    val frame: Int = 0,                                // uFrame
    val stampColor: Vec4 = Vec4(1f, 0.541f, 0.122f, 1f), // uStampColor
    val stampOn: Float = 0f,                           // uStampOn
    val stampDigits: List<Float> = List(8) { DateStamp.GLYPH_BLANK.toFloat() }, // uStampDigits[2]
    // Pass 4 layout (not in §7.2's list; derived from FrameGeometry):
    val canvasSize: Vec2 = Vec2(1f, 1f),               // uCanvasSize (px)
    val imageRect: Vec4 = Vec4(0f, 0f, 1f, 1f),        // uImageRect (canvas uv, y down)
    val gel: Vec4 = Vec4(1f, 1f, 1f, 0f),            // uGel (rgb tint, strength); strength 0 = no gel
)

/** Pure `Preset → UniformSet` mapping (SPEC §7.2). Frozen interface after Phase A. */
object UniformMapper {

    /**
     * Note on lutStrength: the preset's `lutStrength` mixes the *film* LUT inside the baked cube
     * ([LookLutBaker]); `uLutStrength` is the mix of the whole baked look against the input and is
     * 1 for every preset (reserved for a future user intensity control).
     */
    fun map(preset: Preset, state: LookState): UniformSet {
        val rng = SplitMix64(state.seed)
        val u0 = rng.nextFloat(); val u1 = rng.nextFloat(); val u2 = rng.nextFloat()
        val u3 = rng.nextFloat(); val u4 = rng.nextFloat()
        val l = preset.lightLeak
        val leakOn = l.probability > 0f && l.amount > 0f && l.variants.isNotEmpty() && u0 < l.probability
        val variant = if (l.variants.isEmpty()) 0
        else l.variants[(u1 * l.variants.size).toInt().coerceAtMost(l.variants.size - 1)]
        val t = 0.15f + 0.7f * u4
        val leakPos = when ((u3 * 4).toInt().coerceAtMost(3)) {
            0 -> Vec2(0f, t)
            1 -> Vec2(1f, t)
            2 -> Vec2(t, 0f)
            else -> Vec2(t, 1f)
        }
        val stamp = preset.dateStamp.color
        return UniformSet(
            lutStrength = 1f,
            grain = Vec3(preset.grain.amount, preset.grain.size, preset.grain.chroma),
            grainTime = state.grainTime,
            vignette = Vec4(
                preset.vignette.amount, preset.vignette.radius,
                preset.vignette.softness, preset.vignette.shape.code,
            ),
            centerGlow = Vec2(preset.centerGlow.amount, preset.centerGlow.radius),
            softFocus = preset.softFocus,
            bloom = Vec2(preset.bloom.amount, preset.bloom.threshold),
            sharpen = preset.sharpen,
            distortion = Vec2(preset.distortion.barrel, if (preset.distortion.circularMask) 1f else 0f),
            leak = Vec4(if (leakOn) 1f else 0f, variant.toFloat(), l.amount, (u2 * 2.0 * PI).toFloat()),
            leakPos = leakPos,
            dust = preset.dust,
            seed = seedToUnit(state.seed),
            frame = state.frame.code,
            stampColor = Vec4(stamp.r, stamp.g, stamp.b, 1f),
            stampOn = if (state.dateStampOn) 1f else 0f,
            stampDigits = state.stampCodes.map { it.toFloat() },
        ).withGel(state.gel)
    }

    /** Gel: tints the centre glow (forced on if the look has none) plus a slight overall cast. */
    fun UniformSet.withGel(gel: FlashGel): UniformSet =
        if (gel == FlashGel.NONE) this else copy(
            centerGlow = Vec2(maxOf(centerGlow.x, FlashGel.MIN_GLOW), maxOf(centerGlow.y, FlashGel.MIN_GLOW_RADIUS)),
            gel = Vec4(gel.r, gel.g, gel.b, 1f),
        )

    /** Uniforms for the stored first exposure of a double exposure: colour work only, no per-image effects (those run once on the blend). */
    fun firstExposure(set: UniformSet): UniformSet = set.copy(
        grain = Vec3(0f, set.grain.y, 0f),
        vignette = set.vignette.copy(x = 0f),
        centerGlow = Vec2(0f, set.centerGlow.y),
        leak = set.leak.copy(x = 0f),
        dust = 0f,
        stampOn = 0f,
        gel = Vec4(1f, 1f, 1f, 0f),
    )

    /** Fills the resolution-dependent fields for one render target. */
    fun withGeometry(set: UniformSet, cropRectUv: Vec4, layout: FrameLayout): UniformSet {
        val r = layout.imageRectUv()
        return set.copy(
            cropRect = cropRectUv,
            shortSide = layout.shortSide.toFloat(),
            outSize = Vec2(layout.imageW.toFloat(), layout.imageH.toFloat()),
            canvasSize = Vec2(layout.canvasW.toFloat(), layout.canvasH.toFloat()),
            imageRect = Vec4(r[0], r[1], r[2], r[3]),
        )
    }

    /** Well-mixed seed in [0,1) for shader-side procedural layouts (dust, grain offset). */
    fun seedToUnit(seed: Long): Float = SplitMix64(seed xor SEED_SALT).nextFloat()

    private const val SEED_SALT = 0x5DEECE66DL
}

/** Small deterministic PRNG so seed → leak/dust choices are identical in both GL contexts. */
class SplitMix64(private var state: Long) {
    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun nextFloat(): Float = (nextLong() ushr 40).toFloat() / (1L shl 24).toFloat()
}
