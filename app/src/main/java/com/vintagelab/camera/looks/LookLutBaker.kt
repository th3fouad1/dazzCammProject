package com.vintagelab.camera.looks

import kotlin.math.pow

/**
 * Bakes **film LUT ∘ parametric colour ops** of a preset into one 33³ RGBA8 cube
 * (uploaded as `sampler3D uLookLut`). Baked on the CPU in pure Kotlin so both GL contexts
 * upload byte-identical data (SPEC §6.4; CPU instead of GPU recorded as a Phase A deviation).
 *
 * Per grid colour c (gamma sRGB), in order:
 * 1. exposure (in linear light, 2^EV)
 * 2. film LUT (trilinear) mixed with c by lutStrength
 * 3. temperature / tint
 * 4. tone curves: per-channel r/g/b, then `all`
 * 5. saturation (Rec.709 luma)
 * 6. shadow / highlight tint (luma-weighted, hue shift that keeps brightness)
 * 7. fade (lifts blacks)
 * 8. monochrome
 */
object LookLutBaker {
    private const val N = LutLayout.SIZE
    const val TEMP_GAIN = 0.25f

    /** @param filmLut interleaved RGB floats of an N³ cube (see [LutLayout]) or null for identity. */
    fun bakeFloat(preset: Preset, filmLut: FloatArray?): FloatArray {
        require(filmLut == null || filmLut.size == LutLayout.ENTRIES * 3)
        val curveR = ToneCurve.of(preset.tone.r)
        val curveG = ToneCurve.of(preset.tone.g)
        val curveB = ToneCurve.of(preset.tone.b)
        val curveAll = ToneCurve.of(preset.tone.all)
        val expGain = 2f.pow(preset.exposure)
        val out = FloatArray(LutLayout.ENTRIES * 3)
        val c = FloatArray(3)
        val s = 1f / (N - 1)
        for (bi in 0 until N) for (gi in 0 until N) for (ri in 0 until N) {
            c[0] = ri * s; c[1] = gi * s; c[2] = bi * s

            if (preset.exposure != 0f) {
                for (k in 0..2) c[k] = linearToSrgb(exposeLinear(srgbToLinear(c[k]), expGain))
            }
            if (filmLut != null && preset.lutStrength > 0f) {
                val r = c[0]; val g = c[1]; val b = c[2]
                sampleTrilinear(filmLut, r, g, b, c)
                val k = preset.lutStrength
                c[0] = r + (c[0] - r) * k; c[1] = g + (c[1] - g) * k; c[2] = b + (c[2] - b) * k
            }
            // White balance: +temperature warms (R up, B down), +tint shifts to magenta (G down).
            if (preset.temperature != 0f || preset.tint != 0f) {
                // temperature +-1 is roughly +-1500 K-equivalent: R/B gains of 1 -/+ 0.25, luma renormalized.
                val l0 = luma(c)
                c[0] *= 1f + TEMP_GAIN * preset.temperature
                c[2] *= 1f - TEMP_GAIN * preset.temperature
                c[1] *= 1f - 0.08f * preset.tint
                val l1 = luma(c)
                if (l1 > 1e-5f && l0 > 0f) { val k = l0 / l1; c[0] *= k; c[1] *= k; c[2] *= k }
                clamp01(c)
            }
            curveR?.let { c[0] = it.eval(c[0]) }
            curveG?.let { c[1] = it.eval(c[1]) }
            curveB?.let { c[2] = it.eval(c[2]) }
            curveAll?.let { for (k in 0..2) c[k] = it.eval(c[k]) }
            if (preset.saturation != 1f) {
                val l = luma(c)
                for (k in 0..2) c[k] = l + (c[k] - l) * preset.saturation
                clamp01(c)
            }
            applyTint(c, preset.shadowTint, shadows = true)
            applyTint(c, preset.highlightTint, shadows = false)
            if (preset.fade > 0f) {
                val lift = preset.fade * 0.5f
                for (k in 0..2) c[k] = lift + c[k] * (1f - lift)
            }
            if (preset.monochrome) {
                val l = luma(c)
                c[0] = l; c[1] = l; c[2] = l
            }
            clamp01(c)
            val o = LutLayout.index(ri, gi, bi) * 3
            out[o] = c[0]; out[o + 1] = c[1]; out[o + 2] = c[2]
        }
        return out
    }

    /** RGBA8 bytes in `glTexImage3D` order (width=r, height=g, depth=b). */
    fun bake(preset: Preset, filmLut: FloatArray?): ByteArray {
        val f = bakeFloat(preset, filmLut)
        val out = ByteArray(LutLayout.ENTRIES * 4)
        for (i in 0 until LutLayout.ENTRIES) {
            out[i * 4] = LutLayout.to8(f[i * 3]).toByte()
            out[i * 4 + 1] = LutLayout.to8(f[i * 3 + 1]).toByte()
            out[i * 4 + 2] = LutLayout.to8(f[i * 3 + 2]).toByte()
            out[i * 4 + 3] = 0xFF.toByte()
        }
        return out
    }

    fun sampleTrilinear(lut: FloatArray, r: Float, g: Float, b: Float, out: FloatArray, n: Int = N) {
        val fr = r.coerceIn(0f, 1f) * (n - 1)
        val fg = g.coerceIn(0f, 1f) * (n - 1)
        val fb = b.coerceIn(0f, 1f) * (n - 1)
        val r0 = fr.toInt().coerceAtMost(n - 2); val tr = fr - r0
        val g0 = fg.toInt().coerceAtMost(n - 2); val tg = fg - g0
        val b0 = fb.toInt().coerceAtMost(n - 2); val tb = fb - b0
        for (k in 0..2) {
            fun v(dr: Int, dg: Int, db: Int) = lut[LutLayout.index(r0 + dr, g0 + dg, b0 + db, n) * 3 + k]
            val c00 = v(0, 0, 0) + (v(1, 0, 0) - v(0, 0, 0)) * tr
            val c10 = v(0, 1, 0) + (v(1, 1, 0) - v(0, 1, 0)) * tr
            val c01 = v(0, 0, 1) + (v(1, 0, 1) - v(0, 0, 1)) * tr
            val c11 = v(0, 1, 1) + (v(1, 1, 1) - v(0, 1, 1)) * tr
            val c0 = c00 + (c10 - c00) * tg
            val c1 = c01 + (c11 - c01) * tg
            out[k] = c0 + (c1 - c0) * tb
        }
    }

    private fun applyTint(c: FloatArray, t: TintSpec, shadows: Boolean) {
        if (t.amount <= 0f) return
        val l = luma(c)
        val w = if (shadows) (1f - l) * (1f - l) else l * l
        val tl = 0.2126f * t.color.r + 0.7152f * t.color.g + 0.0722f * t.color.b
        val k = t.amount * w
        c[0] += (t.color.r - tl) * k
        c[1] += (t.color.g - tl) * k
        c[2] += (t.color.b - tl) * k
        clamp01(c)
    }

    /** Linear exposure with a soft shoulder for pushes: highlights roll off instead of clipping flat. */
    fun exposeLinear(v: Float, gain: Float): Float {
        val y = v * gain
        if (gain <= 1f || y <= SHOULDER_KNEE) return y
        val r = 1f - SHOULDER_KNEE
        return SHOULDER_KNEE + r * (1f - kotlin.math.exp(-(y - SHOULDER_KNEE) / r))
    }

    private const val SHOULDER_KNEE = 0.75f

    private fun luma(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    private fun clamp01(c: FloatArray) {
        for (k in 0..2) c[k] = c[k].coerceIn(0f, 1f)
    }

    fun srgbToLinear(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    fun linearToSrgb(v: Float): Float {
        val x = v.coerceIn(0f, 1f)
        return if (x <= 0.0031308f) x * 12.92f else 1.055f * x.pow(1f / 2.4f) - 0.055f
    }
}
