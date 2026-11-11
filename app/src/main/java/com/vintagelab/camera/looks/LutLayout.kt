package com.vintagelab.camera.looks

/**
 * Single source of truth for the cube ↔ image layout (used by the HaldCLUT converter,
 * [LookLutBaker] and the GL loader).
 *
 * Cube entry (r,g,b) of an N³ LUT has linear index `i = r + N*g + N*N*b` — the same order as a
 * HaldCLUT and as `glTexImage3D` (width=r, height=g, depth=b). Stored as an image of
 * width N*N and height N, row-major: `x = i % (N*N) = r + N*g`, `y = i / (N*N) = b`.
 */
object LutLayout {
    const val SIZE = 33
    const val IMAGE_WIDTH = SIZE * SIZE
    const val IMAGE_HEIGHT = SIZE
    const val ENTRIES = SIZE * SIZE * SIZE

    fun index(r: Int, g: Int, b: Int, n: Int = SIZE): Int = r + n * (g + n * b)

    fun imageX(r: Int, g: Int, n: Int = SIZE): Int = r + n * g

    fun imageY(b: Int): Int = b

    /** Identity cube as interleaved RGB floats, length N³·3. */
    fun identity(n: Int = SIZE): FloatArray {
        val out = FloatArray(n * n * n * 3)
        val s = 1f / (n - 1)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val o = index(r, g, b, n) * 3
            out[o] = r * s; out[o + 1] = g * s; out[o + 2] = b * s
        }
        return out
    }

    /** Decodes an ARGB pixel array of a `N*N × N` image to interleaved RGB floats. */
    fun fromArgbPixels(pixels: IntArray, n: Int = SIZE): FloatArray {
        require(pixels.size == n * n * n) { "expected ${n * n * n} pixels, got ${pixels.size}" }
        val out = FloatArray(n * n * n * 3)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val p = pixels[imageY(b) * n * n + imageX(r, g, n)]
            val o = index(r, g, b, n) * 3
            out[o] = ((p shr 16) and 0xFF) / 255f
            out[o + 1] = ((p shr 8) and 0xFF) / 255f
            out[o + 2] = (p and 0xFF) / 255f
        }
        return out
    }

    /** Encodes interleaved RGB floats to opaque ARGB pixels of a `N*N × N` image. */
    fun toArgbPixels(cube: FloatArray, n: Int = SIZE): IntArray {
        val out = IntArray(n * n * n)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val o = index(r, g, b, n) * 3
            out[imageY(b) * n * n + imageX(r, g, n)] = (0xFF shl 24) or
                (to8(cube[o]) shl 16) or (to8(cube[o + 1]) shl 8) or to8(cube[o + 2])
        }
        return out
    }

    fun to8(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}
