package com.vintagelab.camera.looks

/**
 * Monotone cubic Hermite interpolation (Fritsch–Carlson) through the given points.
 * Outside [x0, xn] the curve is held at the end values. Monotone input → monotone output.
 */
class ToneCurve(points: CurvePoints) {
    private val xs = FloatArray(points.size) { points[it].first }
    private val ys = FloatArray(points.size) { points[it].second }
    private val ms = FloatArray(points.size)

    init {
        require(points.size >= 2) { "need at least 2 points" }
        val n = points.size
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        ms[0] = d[0]
        ms[n - 1] = d[n - 2]
        for (i in 1 until n - 1) {
            ms[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        }
        for (i in 0 until n - 1) {
            if (d[i] == 0f) {
                ms[i] = 0f; ms[i + 1] = 0f
                continue
            }
            val a = ms[i] / d[i]
            val b = ms[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / kotlin.math.sqrt(s)
                ms[i] = t * a * d[i]
                ms[i + 1] = t * b * d[i]
            }
        }
    }

    fun eval(x: Float): Float {
        val n = xs.size
        if (x <= xs[0]) return ys[0]
        if (x >= xs[n - 1]) return ys[n - 1]
        var i = 0
        while (i < n - 2 && x > xs[i + 1]) i++
        val h = xs[i + 1] - xs[i]
        val t = (x - xs[i]) / h
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2 * t3 - 3 * t2 + 1
        val h10 = t3 - 2 * t2 + t
        val h01 = -2 * t3 + 3 * t2
        val h11 = t3 - t2
        return h00 * ys[i] + h10 * h * ms[i] + h01 * ys[i + 1] + h11 * h * ms[i + 1]
    }

    companion object {
        fun of(points: CurvePoints?): ToneCurve? = points?.let { ToneCurve(it) }
    }
}
