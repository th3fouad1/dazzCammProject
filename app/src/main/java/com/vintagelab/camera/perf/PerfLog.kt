package com.vintagelab.camera.perf

import android.opengl.GLES30
import android.util.Log
import com.vintagelab.camera.BuildConfig

/**
 * Debug-only instrumentation (SPEC §5.3, Appendix D). Every entry point is a no-op unless
 * [BuildConfig.DEBUG]; read with `adb logcat -s VLPerf`.
 */
object PerfLog {
    const val TAG = "VLPerf"
    val enabled: Boolean get() = BuildConfig.DEBUG

    fun nowNs(): Long = if (BuildConfig.DEBUG) System.nanoTime() else 0L

    // ---- capture stages (one job in flight, so a single shot record is enough) ----

    private class Shot {
        var tapNs = 0L
        var capturedNs = 0L
        var decodeMs = 0.0
        var renderMs = 0.0
        var readbackMs = 0.0
        var encodeMs = 0.0
    }

    @Volatile
    private var shot = Shot()

    fun shotTap() {
        if (!enabled) return
        shot = Shot().also { it.tapNs = System.nanoTime() }
    }

    fun shotCaptured() {
        if (!enabled) return
        shot.capturedNs = System.nanoTime()
    }

    fun shotDecode(startNs: Long) {
        if (enabled) shot.decodeMs = (System.nanoTime() - startNs) / 1e6
    }

    fun shotRender(uploadRenderMs: Double, readbackMs: Double) {
        if (!enabled) return
        shot.renderMs = uploadRenderMs
        shot.readbackMs = readbackMs
    }

    fun shotEncode(startNs: Long) {
        if (enabled) shot.encodeMs = (System.nanoTime() - startNs) / 1e6
    }

    fun shotSaved(label: String) {
        if (!enabled) return
        val s = shot
        val now = System.nanoTime()
        Log.i(
            TAG,
            "SHOT %s capture=%.0f decode=%.0f upload+render=%.0f readback=%.0f compress+write=%.0f total=%.0f ms".format(
                label,
                (s.capturedNs - s.tapNs) / 1e6, s.decodeMs, s.renderMs, s.readbackMs, s.encodeMs,
                (now - s.tapNs) / 1e6,
            ),
        )
    }

    // ---- preview frames ----

    /** Rolling window of [WINDOW] frames; logs p50/p95 of CPU onDrawFrame, GPU time and frame interval. */
    class PreviewStats(private val label: () -> String) {
        private val cpu = DoubleArray(WINDOW)
        private val gpu = DoubleArray(WINDOW)
        private val interval = DoubleArray(WINDOW)
        private var n = 0
        private var nGpu = 0
        private var lastStartNs = 0L
        private var frameStartNs = 0L

        private var timer: GpuTimer? = null

        fun begin() {
            if (!enabled) return
            if (timer == null) timer = GpuTimer()
            frameStartNs = System.nanoTime()
            if (lastStartNs != 0L && n < WINDOW) interval[n] = (frameStartNs - lastStartNs) / 1e6
            lastStartNs = frameStartNs
            timer?.begin()
        }

        /** Call on every exit path of a draw. */
        fun end() {
            if (!enabled) return
            val t = timer ?: return
            t.end()
            val ms = (System.nanoTime() - frameStartNs) / 1e6
            if (n < WINDOW) cpu[n] = ms
            t.poll()?.let { if (nGpu < WINDOW) gpu[nGpu++] = it }
            if (++n >= WINDOW) {
                val slow = interval.count { it > 50.0 }
                Log.i(
                    TAG,
                    "PREVIEW %s cpu p50=%.2f p95=%.2f | gpu(%s) p50=%.2f p95=%.2f | interval p50=%.1f p95=%.1f max=%.1f ms (%.1f fps) slow(>50ms)=%d/%d".format(
                        label(),
                        pct(cpu, WINDOW, 0.5), pct(cpu, WINDOW, 0.95),
                        "sync n=$nGpu", pct(gpu, nGpu, 0.5), pct(gpu, nGpu, 0.95),
                        pct(interval, WINDOW, 0.5), pct(interval, WINDOW, 0.95), interval.max(),
                        1000.0 / pct(interval, WINDOW, 0.5).coerceAtLeast(0.001), slow, WINDOW,
                    ),
                )
                n = 0
                nGpu = 0
            }
        }

        fun release() {
            timer?.release()
            timer = null
        }

        private fun pct(a: DoubleArray, len: Int, p: Double): Double {
            if (len == 0) return Double.NaN
            val s = a.copyOf(len).also { it.sort() }
            return s[((len - 1) * p).toInt().coerceIn(0, len - 1)]
        }
    }

    /**
     * The Android SDK exposes no Java binding for EXT_disjoint_timer_query (it would need JNI), so GPU
     * time is a proxy: every [EVERY]th frame ends with glFinish and reports begin-to-GPU-idle time
     * (CPU submit + GPU execution of that frame). Sampled frames serialise CPU and GPU, so the proxy
     * is an upper bound on true GPU time; the other frames run unperturbed.
     */
    private class GpuTimer {
        val supported: Boolean = true
        private var count = 0
        private var sampling = false
        private var beginNs = 0L
        private var result = Double.NaN

        fun begin() {
            sampling = ++count % EVERY == 0
            beginNs = System.nanoTime()
        }

        fun end() {
            if (!sampling) return
            GLES30.glFinish()
            result = (System.nanoTime() - beginNs) / 1e6
        }

        fun poll(): Double? {
            if (!sampling) return null
            sampling = false
            return result
        }

        fun release() {}
    }

    private const val WINDOW = 60
    private const val EVERY = 4
}
