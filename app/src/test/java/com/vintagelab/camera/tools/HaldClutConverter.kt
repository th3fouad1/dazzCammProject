package com.vintagelab.camera.tools

import com.vintagelab.camera.looks.LookLutBaker
import com.vintagelab.camera.looks.LutLayout
import java.io.File
import kotlin.math.roundToInt

/**
 * tools/haldclut_to_lut33 (SPEC §6.4): resamples a HaldCLUT (level L: image L³×L³, cube L²)
 * to an N³ cube stored as an N²×N RGB PNG in [LutLayout] order.
 *
 * HaldCLUT layout: entry index i = r + g·L² + b·L⁴ (cube size L²), stored row-major.
 * Lives in the unit-test source set (pure JVM, [MiniPng]) so CI's testDebugUnitTest covers it.
 * Regenerate assets: `./gradlew :app:testDebugUnitTest -PgenerateLuts --tests '*GenerateLuts*'`.
 */
object HaldClutConverter {

    /** Reads a HaldCLUT image as interleaved RGB floats of its native cube (size L²). */
    fun readHald(img: ArgbImage): Pair<FloatArray, Int> {
        require(img.width == img.height) { "HaldCLUT must be square" }
        val level = Math.cbrt(img.width.toDouble()).roundToInt()
        require(level * level * level == img.width) { "width ${img.width} is not L³" }
        val cube = level * level
        val px = img.pixels
        val out = FloatArray(px.size * 3)
        for (i in px.indices) {
            val p = px[i]
            out[i * 3] = ((p shr 16) and 0xFF) / 255f
            out[i * 3 + 1] = ((p shr 8) and 0xFF) / 255f
            out[i * 3 + 2] = (p and 0xFF) / 255f
        }
        check(cube.toLong() * cube * cube == px.size.toLong())
        return out to cube
    }

    /** Trilinear resample of an M³ cube (HaldCLUT index order == [LutLayout] order) to N³. */
    fun resample(src: FloatArray, m: Int, n: Int = LutLayout.SIZE): FloatArray {
        val out = FloatArray(n * n * n * 3)
        val tmp = FloatArray(3)
        val s = 1f / (n - 1)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            LookLutBaker.sampleTrilinear(src, r * s, g * s, b * s, tmp, m)
            val o = LutLayout.index(r, g, b, n) * 3
            out[o] = tmp[0]; out[o + 1] = tmp[1]; out[o + 2] = tmp[2]
        }
        return out
    }

    fun convert(hald: ArgbImage, n: Int = LutLayout.SIZE): FloatArray {
        val (cube, m) = readHald(hald)
        return resample(cube, m, n)
    }

    fun toImage(cube: FloatArray, n: Int = LutLayout.SIZE): ArgbImage {
        return ArgbImage(n * n, n, LutLayout.toArgbPixels(cube, n))
    }

    fun fromImage(img: ArgbImage, n: Int = LutLayout.SIZE): FloatArray =
        LutLayout.fromArgbPixels(img.pixels, n)

    /** Identity HaldCLUT of [level] (for tests). */
    fun identityHald(level: Int): ArgbImage {
        val cube = level * level
        val w = level * level * level
        val px = IntArray(w * w)
        for (b in 0 until cube) for (g in 0 until cube) for (r in 0 until cube) {
            val i = r + g * cube + b * cube * cube
            fun q(v: Int) = (v * 255f / (cube - 1) + 0.5f).toInt()
            px[i] = (0xFF shl 24) or (q(r) shl 16) or (q(g) shl 8) or q(b)
        }
        return ArgbImage(w, w, px)
    }

    /** "Color Negative - Kodak Gold 200.png" → "kodak_gold_200". */
    fun snakeId(fileName: String): String {
        val base = fileName.substringBeforeLast('.').substringAfter(" - ")
        return base.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    }

    fun convertDirectory(inDir: File, outDir: File): List<String> {
        outDir.mkdirs()
        val files = inDir.listFiles { f -> f.extension.equals("png", true) }?.sortedBy { it.name }
            ?: error("no HaldCLUTs in $inDir")
        return files.map { f ->
            val id = snakeId(f.name)
            val cube = convert(MiniPng.read(f))
            MiniPng.writeRgb(toImage(cube), File(outDir, "$id.png"))
            id
        }
    }

}
