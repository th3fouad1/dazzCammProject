package com.vintagelab.camera.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vintagelab.camera.capture.FrameGeometry
import com.vintagelab.camera.looks.CropMath
import com.vintagelab.camera.looks.FrameId
import com.vintagelab.camera.looks.LookState
import com.vintagelab.camera.looks.Preset
import com.vintagelab.camera.looks.PresetRepository
import com.vintagelab.camera.looks.SourceTransform
import com.vintagelab.camera.looks.UniformMapper
import com.vintagelab.camera.looks.Vec4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * SPEC §5.1: the graph rendered at full resolution then area-downscaled matches the graph rendered
 * at preview resolution, for the same sampler2D input (procedural chart), look and seed.
 */
@RunWith(AndroidJUnit4::class)
class ParityTest {
    private lateinit var egl: EglCore
    private lateinit var graph: RenderGraph
    private lateinit var presets: PresetRepository
    private var chartTex = 0

    private val fullW = 4032
    private val fullH = 3024
    private val prevW = 1440
    private val prevH = 1080

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        egl = EglCore().also { it.init() }
        val read: (String) -> String = { p -> ctx.assets.open(p).bufferedReader().use { it.readText() } }
        graph = RenderGraph(read, LutLoader(ctx.assets), strict = true).also { it.init(hasOesEssl3 = false, needsOes = false) }
        presets = PresetRepository.load(ctx)
        val chart = chart(fullW, fullH)
        chartTex = Textures.fromBitmap(chart)
        chart.recycle()
    }

    @After
    fun tearDown() {
        Textures.delete(chartTex)
        graph.release()
        egl.release()
    }

    @Test
    fun everyPreset_grainOff_meanAndP99() {
        for (p in presets.presets) {
            val preset = p.copy(grain = p.grain.copy(amount = 0f))
            val full = downscale(render(preset, fullW, fullH), fullW, fullH, prevW, prevH)
            val prev = render(preset, prevW, prevH)
            val diffs = IntArray(prevW * prevH * 3)
            var sum = 0L
            for (i in 0 until prevW * prevH) for (k in 0..2) {
                val d = abs((full[i * 4 + k].toInt() and 0xFF) - (prev[i * 4 + k].toInt() and 0xFF))
                diffs[i * 3 + k] = d
                sum += d
            }
            val mean = sum.toDouble() / diffs.size
            diffs.sort()
            val p99 = diffs[(diffs.size * 0.99).toInt()]
            assertTrue("${p.id}: mean abs diff $mean > 3", mean <= 3.0)
            assertTrue("${p.id}: p99 $p99 > 12", p99 <= 12)
        }
    }

    @Test
    fun everyPreset_grainOn_blockStatsWithin5Percent() {
        for (p in presets.presets) {
            if (p.grain.amount <= 0f) continue
            val full = downscale(render(p, fullW, fullH), fullW, fullH, prevW, prevH)
            val prev = render(p, prevW, prevH)
            var bad = 0
            var total = 0
            for (by in 0 until prevH / 64) for (bx in 0 until prevW / 64) {
                val a = blockStats(full, bx, by)
                val b = blockStats(prev, bx, by)
                total++
                val meanOk = abs(a.first - b.first) <= 0.05 * maxOf(b.first, 1.0)
                val stdOk = abs(a.second - b.second) <= 0.05 * maxOf(b.second, 1.0)
                if (!meanOk || !stdOk) bad++
            }
            assertTrue("${p.id}: $bad / $total blocks outside ±5 %", bad == 0)
        }
    }

    private fun state() = LookState(seed = 1234L, frame = FrameId.NONE, dateStampOn = false, grainTime = 2.0f)

    /** Renders the chart through the whole graph at w×h (4:3 crop of the 4032×3024 chart). */
    private fun render(preset: Preset, w: Int, h: Int): ByteArray {
        val crop = CropMath.centerCrop(fullW, fullH, com.vintagelab.camera.looks.AspectRatio.R4_3)
        val t = SourceTransform(fullW, fullH, crop, 0, mirror = false)
        val layout = FrameGeometry.layout(FrameId.NONE, w, h)
        val u = UniformMapper.withGeometry(UniformMapper.map(preset, state()), Vec4(0f, 0f, 1f, 1f), layout)
        val target = Fbo(w, h)
        graph.setLook(preset)
        graph.render(RenderInput(chartTex, false, null, t), w, h, u, RenderTarget(target.framebuffer, 0, 0, w, h, flipY = true))
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.framebuffer)
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        target.release()
        graph.trimMemory()
        return ByteArray(w * h * 4).also { buf.get(it) }
    }

    /** Area (box) downscale of RGBA8 for a non-integer ratio. */
    private fun downscale(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray {
        val out = ByteArray(dw * dh * 4)
        val fx = sw.toDouble() / dw
        val fy = sh.toDouble() / dh
        for (y in 0 until dh) {
            val y0 = y * fy
            val y1 = (y + 1) * fy
            for (x in 0 until dw) {
                val x0 = x * fx
                val x1 = (x + 1) * fx
                val acc = DoubleArray(4)
                var wsum = 0.0
                var sy = y0.toInt()
                while (sy < y1 && sy < sh) {
                    val wy = minOf(y1, sy + 1.0) - maxOf(y0, sy.toDouble())
                    var sx = x0.toInt()
                    while (sx < x1 && sx < sw) {
                        val wx = minOf(x1, sx + 1.0) - maxOf(x0, sx.toDouble())
                        val wgt = wx * wy
                        val o = (sy * sw + sx) * 4
                        for (k in 0..3) acc[k] += (src[o + k].toInt() and 0xFF) * wgt
                        wsum += wgt
                        sx++
                    }
                    sy++
                }
                for (k in 0..3) out[(y * dw + x) * 4 + k] = (acc[k] / wsum + 0.5).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /** Luminance mean/std of one 64×64 block (0..255 units). */
    private fun blockStats(img: ByteArray, bx: Int, by: Int): Pair<Double, Double> {
        var s = 0.0
        var s2 = 0.0
        for (y in by * 64 until by * 64 + 64) for (x in bx * 64 until bx * 64 + 64) {
            val o = (y * prevW + x) * 4
            val l = 0.2126 * (img[o].toInt() and 0xFF) + 0.7152 * (img[o + 1].toInt() and 0xFF) + 0.0722 * (img[o + 2].toInt() and 0xFF)
            s += l
            s2 += l * l
        }
        val n = 64.0 * 64.0
        val mean = s / n
        return mean to sqrt(maxOf(0.0, s2 / n - mean * mean))
    }

    /** Procedural chart: checker (left half) + RGB gradients (right half). */
    private fun chart(w: Int, h: Int): Bitmap {
        val px = IntArray(w * h)
        val sq = h / 16
        for (y in 0 until h) for (x in 0 until w) {
            px[y * w + x] = if (x < w / 2) {
                val on = ((x / sq) + (y / sq)) % 2 == 0
                val v = if (on) 0xD0 else 0x30
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            } else {
                val r = (x - w / 2) * 255 / (w / 2)
                val g = y * 255 / h
                val b = ((x - w / 2) + y) * 255 / (w / 2 + h)
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
