package com.vintagelab.camera.looks

/** 0..1 RGB triple, gamma-encoded sRGB (same encoding as the working texture). */
data class Rgb(val r: Float, val g: Float, val b: Float) {
    companion object {
        val WHITE = Rgb(1f, 1f, 1f)
        val STAMP_ORANGE = parseHex("#FF8A1F")!!

        /** Parses `#RRGGBB`; returns null when malformed. Pure Kotlin (no android.graphics). */
        fun parseHex(s: String): Rgb? {
            if (s.length != 7 || s[0] != '#') return null
            val v = s.substring(1).toLongOrNull(16) ?: return null
            return Rgb(
                ((v shr 16) and 0xFF) / 255f,
                ((v shr 8) and 0xFF) / 255f,
                (v and 0xFF) / 255f,
            )
        }
    }
}

typealias CurvePoints = List<Pair<Float, Float>>

data class ToneCurves(
    val r: CurvePoints? = null,
    val g: CurvePoints? = null,
    val b: CurvePoints? = null,
    val all: CurvePoints? = null,
) {
    val isIdentity: Boolean get() = r == null && g == null && b == null && all == null
}

data class TintSpec(val color: Rgb = Rgb.WHITE, val amount: Float = 0f)

data class GrainSpec(val amount: Float = 0f, val size: Float = 0.0012f, val chroma: Float = 0f)

enum class VignetteShape(val id: String, val code: Float) {
    OVAL("oval", 0f), CIRCLE("circle", 1f);

    companion object {
        fun fromId(id: String): VignetteShape? = entries.firstOrNull { it.id == id }
    }
}

data class VignetteSpec(
    val amount: Float = 0f,
    val radius: Float = 1f,
    val softness: Float = 0.5f,
    val shape: VignetteShape = VignetteShape.OVAL,
)

data class CenterGlowSpec(val amount: Float = 0f, val radius: Float = 0.5f)

data class BloomSpec(val amount: Float = 0f, val threshold: Float = 0.8f)

data class DistortionSpec(val barrel: Float = 0f, val circularMask: Boolean = false)

data class LightLeakSpec(
    val probability: Float = 0f,
    val amount: Float = 0f,
    val variants: List<Int> = listOf(0, 1, 2, 3),
)

enum class FrameId(val id: String, val code: Int) {
    NONE("none", 0), INSTANT_WHITE("instant_white", 1), FILM_STRIP("film_strip", 2);

    companion object {
        fun fromId(id: String): FrameId? = entries.firstOrNull { it.id == id }
    }
}

data class DateStampSpec(val enabledByDefault: Boolean = false, val color: Rgb = Rgb.STAMP_ORANGE)

/** One look's data record (SPEC §7.1). Every field has a neutral default. */
data class Preset(
    val id: String,
    val displayNameRes: String = id,
    val lut: String? = null,
    val lutStrength: Float = 1f,
    val exposure: Float = 0f,
    val tone: ToneCurves = ToneCurves(),
    val saturation: Float = 1f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val shadowTint: TintSpec = TintSpec(),
    val highlightTint: TintSpec = TintSpec(),
    val fade: Float = 0f,
    val monochrome: Boolean = false,
    val grain: GrainSpec = GrainSpec(),
    val vignette: VignetteSpec = VignetteSpec(),
    val centerGlow: CenterGlowSpec = CenterGlowSpec(),
    val softFocus: Float = 0f,
    val bloom: BloomSpec = BloomSpec(),
    val sharpen: Float = 0f,
    val distortion: DistortionSpec = DistortionSpec(),
    val lightLeak: LightLeakSpec = LightLeakSpec(),
    val dust: Float = 0f,
    val defaultFrame: FrameId = FrameId.NONE,
    val dateStamp: DateStampSpec = DateStampSpec(),
) {
    companion object {
        /** Built-in identity preset used when presets.json is unusable. */
        val IDENTITY = Preset(id = "none")
    }
}
