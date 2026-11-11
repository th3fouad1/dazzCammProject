package com.vintagelab.camera.looks

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

/** Logging sink so the parser stays pure Kotlin (unit-testable without Android). */
interface PresetLog {
    fun warn(msg: String)
    fun error(msg: String)

    object Android : PresetLog {
        private const val TAG = "Presets"
        override fun warn(msg: String) { Log.w(TAG, msg) }
        override fun error(msg: String) { Log.e(TAG, msg) }
    }

    object Silent : PresetLog {
        override fun warn(msg: String) = Unit
        override fun error(msg: String) = Unit
    }
}

/**
 * Immutable set of looks loaded from `assets/presets/presets.json` (SPEC §7.1).
 * Frozen interface after Phase A: [presets], [get], [default].
 */
class PresetRepository(val presets: List<Preset>) {
    init {
        require(presets.isNotEmpty())
    }

    val default: Preset get() = presets.first()

    fun get(id: String): Preset = presets.firstOrNull { it.id == id } ?: default

    companion object {
        const val ASSET_PATH = "presets/presets.json"

        fun load(context: Context, log: PresetLog = PresetLog.Android): PresetRepository {
            val text = try {
                context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            } catch (e: java.io.IOException) {
                log.error("cannot read $ASSET_PATH: ${e.message}")
                ""
            }
            return PresetRepository(PresetParser.parse(text, log))
        }
    }
}

/** presets.json (schemaVersion 1) → validated presets. Never throws. */
object PresetParser {
    const val SCHEMA_VERSION = 1
    private val json = Json { isLenient = false }

    fun parse(text: String, log: PresetLog = PresetLog.Silent): List<Preset> {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            log.error("malformed presets.json: ${e.message}")
            null
        } ?: run {
            log.error("presets.json root is not an object; using identity preset")
            return listOf(Preset.IDENTITY)
        }
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
        if (version != SCHEMA_VERSION) log.warn("schemaVersion $version != $SCHEMA_VERSION")
        val array = root["presets"] as? JsonArray ?: run {
            log.error("presets.json has no 'presets' array; using identity preset")
            return listOf(Preset.IDENTITY)
        }
        val out = ArrayList<Preset>()
        val seen = HashSet<String>()
        for ((index, element) in array.withIndex()) {
            val obj = element as? JsonObject
            if (obj == null) {
                log.error("preset #$index is not an object; skipped")
                continue
            }
            val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (id.isNullOrBlank()) {
                log.error("preset #$index has no id; skipped")
                continue
            }
            if (!seen.add(id)) {
                log.error("duplicate preset id '$id'; skipped")
                continue
            }
            try {
                out += PresetReader(id, obj, log).read()
            } catch (e: IllegalArgumentException) {
                log.error("preset '$id' unreadable (${e.message}); skipped")
            }
        }
        if (out.isEmpty()) {
            log.error("no valid presets; using identity preset")
            return listOf(Preset.IDENTITY)
        }
        return out
    }
}

private class PresetReader(val id: String, val obj: JsonObject, val log: PresetLog) {

    fun read(): Preset {
        val d = Preset(id)
        return Preset(
            id = id,
            displayNameRes = string(obj, "displayNameRes") ?: d.displayNameRes,
            lut = string(obj, "lut"),
            lutStrength = num(obj, "lutStrength", d.lutStrength, 0f, 1f),
            exposure = num(obj, "exposure", d.exposure, -2f, 2f),
            tone = tone(obj["tone"]),
            saturation = num(obj, "saturation", d.saturation, 0f, 2f),
            temperature = num(obj, "temperature", d.temperature, -1f, 1f),
            tint = num(obj, "tint", d.tint, -1f, 1f),
            shadowTint = tint(obj["shadowTint"], "shadowTint"),
            highlightTint = tint(obj["highlightTint"], "highlightTint"),
            fade = num(obj, "fade", d.fade, 0f, 1f),
            monochrome = bool(obj, "monochrome", d.monochrome),
            grain = (obj["grain"] as? JsonObject)?.let {
                val g = GrainSpec()
                GrainSpec(
                    amount = num(it, "amount", g.amount, 0f, 1f, "grain."),
                    size = num(it, "size", g.size, 0.0005f, 0.01f, "grain."),
                    chroma = num(it, "chroma", g.chroma, 0f, 1f, "grain."),
                )
            } ?: GrainSpec(),
            vignette = (obj["vignette"] as? JsonObject)?.let {
                val v = VignetteSpec()
                val shapeId = string(it, "shape")
                val shape = shapeId?.let { s ->
                    VignetteShape.fromId(s) ?: run { log.warn("$id: unknown vignette.shape '$s'"); null }
                } ?: v.shape
                VignetteSpec(
                    amount = num(it, "amount", v.amount, 0f, 1f, "vignette."),
                    radius = num(it, "radius", v.radius, 0f, 1.5f, "vignette."),
                    softness = num(it, "softness", v.softness, 0f, 1f, "vignette."),
                    shape = shape,
                )
            } ?: VignetteSpec(),
            centerGlow = (obj["centerGlow"] as? JsonObject)?.let {
                val c = CenterGlowSpec()
                CenterGlowSpec(
                    amount = num(it, "amount", c.amount, 0f, 1f, "centerGlow."),
                    radius = num(it, "radius", c.radius, 0f, 1f, "centerGlow."),
                )
            } ?: CenterGlowSpec(),
            softFocus = num(obj, "softFocus", d.softFocus, 0f, 1f),
            bloom = (obj["bloom"] as? JsonObject)?.let {
                val b = BloomSpec()
                BloomSpec(
                    amount = num(it, "amount", b.amount, 0f, 1f, "bloom."),
                    threshold = num(it, "threshold", b.threshold, 0f, 1f, "bloom."),
                )
            } ?: BloomSpec(),
            sharpen = num(obj, "sharpen", d.sharpen, 0f, 1f),
            distortion = (obj["distortion"] as? JsonObject)?.let {
                DistortionSpec(
                    barrel = num(it, "barrel", 0f, 0f, 1f, "distortion."),
                    circularMask = bool(it, "circularMask", false),
                )
            } ?: DistortionSpec(),
            lightLeak = (obj["lightLeak"] as? JsonObject)?.let {
                val l = LightLeakSpec()
                val variants = (it["variants"] as? JsonArray)?.mapNotNull { e ->
                    val v = (e as? JsonPrimitive)?.intOrNull
                    if (v == null || v !in 0..3) {
                        log.warn("$id: lightLeak.variants entry $e out of 0..3; dropped"); null
                    } else v
                }?.distinct() ?: l.variants
                LightLeakSpec(
                    probability = num(it, "probability", l.probability, 0f, 1f, "lightLeak."),
                    amount = num(it, "amount", l.amount, 0f, 1f, "lightLeak."),
                    variants = variants,
                )
            } ?: LightLeakSpec(),
            dust = num(obj, "dust", d.dust, 0f, 1f),
            defaultFrame = string(obj, "defaultFrame")?.let {
                FrameId.fromId(it) ?: run { log.warn("$id: unknown defaultFrame '$it'"); null }
            } ?: FrameId.NONE,
            dateStamp = (obj["dateStamp"] as? JsonObject)?.let {
                DateStampSpec(
                    enabledByDefault = bool(it, "enabledByDefault", false),
                    color = color(it, "color", Rgb.STAMP_ORANGE, "dateStamp."),
                )
            } ?: DateStampSpec(),
        )
    }

    private fun string(o: JsonObject, key: String): String? {
        val e = o[key] ?: return null
        if (e is JsonNull) return null
        val p = e as? JsonPrimitive
        if (p == null || !p.isString) {
            log.warn("$id: '$key' is not a string; default used"); return null
        }
        return p.content
    }

    private fun num(o: JsonObject, key: String, def: Float, min: Float, max: Float, prefix: String = ""): Float {
        val e = o[key] ?: return def
        val v = (e as? JsonPrimitive)?.takeIf { !it.isString }?.floatOrNull
        if (v == null || v.isNaN()) {
            log.warn("$id: '$prefix$key' is not a number; default $def used"); return def
        }
        if (v < min || v > max) {
            val c = v.coerceIn(min, max)
            log.warn("$id: '$prefix$key'=$v out of [$min,$max]; clamped to $c")
            return c
        }
        return v
    }

    private fun bool(o: JsonObject, key: String, def: Boolean): Boolean {
        val e = o[key] ?: return def
        return (e as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: run { log.warn("$id: '$key' is not a boolean; default used"); def }
    }

    private fun color(o: JsonObject, key: String, def: Rgb, prefix: String): Rgb {
        val s = string(o, key) ?: return def
        return Rgb.parseHex(s) ?: run { log.warn("$id: '$prefix$key'='$s' is not #RRGGBB"); def }
    }

    private fun tint(e: JsonElement?, name: String): TintSpec {
        val o = e as? JsonObject ?: return TintSpec()
        return TintSpec(
            color = color(o, "color", Rgb.WHITE, "$name."),
            amount = num(o, "amount", 0f, 0f, 1f, "$name."),
        )
    }

    private fun tone(e: JsonElement?): ToneCurves {
        val o = e as? JsonObject ?: return ToneCurves()
        return ToneCurves(
            r = curve(o["r"], "r"), g = curve(o["g"], "g"),
            b = curve(o["b"], "b"), all = curve(o["all"], "all"),
        )
    }

    /** 2–16 `[x,y]` points in [0,1], x strictly increasing; otherwise identity + WARN. */
    private fun curve(e: JsonElement?, channel: String): CurvePoints? {
        if (e == null || e is JsonNull) return null
        val arr = e as? JsonArray
        val pts = arr?.map { p ->
            val pair = p as? JsonArray
            val x = (pair?.getOrNull(0) as? JsonPrimitive)?.floatOrNull
            val y = (pair?.getOrNull(1) as? JsonPrimitive)?.floatOrNull
            if (pair == null || pair.size != 2 || x == null || y == null) {
                log.warn("$id: tone.$channel has a malformed point; curve ignored"); return null
            }
            if (x !in 0f..1f || y !in 0f..1f) log.warn("$id: tone.$channel point ($x,$y) clamped")
            x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
        }
        if (pts == null || pts.size !in 2..16) {
            log.warn("$id: tone.$channel needs 2–16 points; curve ignored"); return null
        }
        for (i in 1 until pts.size) {
            if (pts[i].first <= pts[i - 1].first) {
                log.warn("$id: tone.$channel x not strictly increasing; curve ignored"); return null
            }
        }
        return pts
    }
}
