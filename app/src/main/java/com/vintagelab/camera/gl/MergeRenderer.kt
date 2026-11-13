package com.vintagelab.camera.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import com.vintagelab.camera.capture.Align
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Boost merge (SPEC 7.7) on the capture GL context: frames are linearized and accumulated with
 * per-pixel weights into an RGBA16F target (ping-pong), aligned by a global sub-pixel shift, and
 * rejected where they differ from the reference at a coarse mip level (motion, misalignment).
 * The resolve divides by the weight sum and applies the plan's linear gain with a soft shoulder.
 * Must be called on the thread that owns the current EGL context.
 */
class MergeRenderer(private val readAsset: (String) -> String) {
    private var accum: ShaderProgram? = null
    private var resolve: ShaderProgram? = null
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
    }

    /** RGBA16F colour attachments need EXT_color_buffer_half_float or EXT_color_buffer_float on ES 3.0. */
    fun supported(): Boolean {
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: return false
        return "GL_EXT_color_buffer_half_float" in ext || "GL_EXT_color_buffer_float" in ext
    }

    private fun ensure() {
        if (accum != null) return
        val vs = ShaderSources.vertex(ShaderSources.Lang.ESSL3, readAsset)
        val variant = ShaderSources.fragmentVariant(ShaderSources.Lang.ESSL3, oes = false)
        accum = ShaderProgram.build("merge_accum", vs, ShaderSources.assemble(ShaderSources.Lang.ESSL3.versionLine, variant, listOf(ACCUM_FS)))
        resolve = ShaderProgram.build("merge_resolve", vs, ShaderSources.assemble(ShaderSources.Lang.ESSL3.versionLine, variant, listOf(RESOLVE_FS)))
    }

    /** Next frame source: a decoded full-size bitmap (recycled here after upload) and its shift to the reference. */
    class Frame(val bitmap: Bitmap, val shift: Align.Shift)

    /**
     * [reference] is recycled after upload. [frames] are pulled one at a time so only one decoded
     * frame lives on the heap. Returns the merged ARGB_8888 bitmap (same size as the reference) and
     * the mean accepted weight of the non-reference frames (0..1, for logging).
     */
    fun merge(reference: Bitmap, frames: Sequence<Frame>, gainEv: Float): Pair<Bitmap, Float> {
        ensure()
        val w = reference.width
        val h = reference.height
        val ref = upload(reference)
        reference.recycle()
        var a = HalfFbo(w, h)
        var b = HalfFbo(w, h)
        val out = Fbo(w, h)
        var frameTex = 0
        var weightSum = 0f
        var count = 0
        try {
            // Reference pass: acc = ref (weight 1).
            accumulate(a.framebuffer, w, h, accTex = 0, frameTex = ref, refTex = ref, shift = Align.Shift(0f, 0f), first = true)
            for (f in frames) {
                frameTex = upload(f.bitmap)
                f.bitmap.recycle()
                accumulate(b.framebuffer, w, h, accTex = a.texture, frameTex = frameTex, refTex = ref, shift = f.shift, first = false)
                Textures.delete(frameTex); frameTex = 0
                val t = a; a = b; b = t
                count++
            }
            // Resolve into RGBA8.
            val p = resolve!!
            out.bind()
            p.use()
            bindTex(0, a.texture); p.set1i("uAcc", 0)
            p.set1f("uGain", Math.pow(2.0, gainEv.toDouble()).toFloat())
            draw()
            val bytes = w * h * 4
            val buf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            // Mean weight of the burst (alpha of the accumulator minus the reference), sampled at the centre.
            val px = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, a.framebuffer)
            GLES30.glReadPixels(w / 2, h / 2, 1, 1, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, px)
            weightSum = halfToFloat(px.getShort(6)) - 1f
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            val err = GLES30.glGetError()
            check(err == GLES30.GL_NO_ERROR) { "GL error 0x${Integer.toHexString(err)} during merge" }
            buf.position(0).limit(bytes)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            bmp.setHasAlpha(false)
            return bmp to if (count > 0) weightSum / count else 0f
        } finally {
            Textures.delete(frameTex)
            Textures.delete(ref)
            a.release(); b.release(); out.release()
        }
    }

    private fun accumulate(fb: Int, w: Int, h: Int, accTex: Int, frameTex: Int, refTex: Int, shift: Align.Shift, first: Boolean) {
        val p = accum!!
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb)
        GLES30.glViewport(0, 0, w, h)
        p.use()
        bindTex(0, frameTex); p.set1i("uFrame", 0)
        bindTex(1, refTex); p.set1i("uRef", 1)
        bindTex(2, if (accTex != 0) accTex else refTex); p.set1i("uAcc", 2)
        p.set2f("uShift", shift.dx / w, shift.dy / h)
        p.set1f("uFirst", if (first) 1f else 0f)
        draw()
    }

    private fun upload(bmp: Bitmap): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val t = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t
    }

    private fun bindTex(unit: Int, tex: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
    }

    private fun draw() {
        quad.position(0)
        GLES30.glEnableVertexAttribArray(ShaderProgram.ATTR_POSITION)
        GLES30.glVertexAttribPointer(ShaderProgram.ATTR_POSITION, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(ShaderProgram.ATTR_POSITION)
    }

    fun release() {
        accum?.release(); accum = null
        resolve?.release(); resolve = null
    }

    private class HalfFbo(w: Int, h: Int) {
        val texture: Int
        val framebuffer: Int

        init {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            texture = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glGenFramebuffers(1, ids, 0)
            framebuffer = ids[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                release()
                error("RGBA16F FBO ${w}x$h incomplete: 0x${Integer.toHexString(status)}")
            }
        }

        fun release() {
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        }
    }

    companion object {
        fun halfToFloat(h: Short): Float {
            val bits = h.toInt() and 0xFFFF
            val s = bits ushr 15
            val e = (bits ushr 10) and 0x1F
            val m = bits and 0x3FF
            val v = when (e) {
                0 -> m / 1024f * Math.pow(2.0, -14.0).toFloat()
                31 -> if (m == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> (1f + m / 1024f) * Math.pow(2.0, (e - 15).toDouble()).toFloat()
            }
            return if (s == 1) -v else v
        }

        private const val COMMON = """
vec3 toLin(vec3 c) { return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c)); }
vec3 toSrgb(vec3 c) { c = clamp(c, 0.0, 1.0); return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c)); }
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
"""

        /** acc' = acc + w * lin(frame(uv + shift)); w from the coarse (mip 3) difference to the reference. */
        private const val ACCUM_FS = COMMON + """
IN vec2 vUv;
uniform sampler2D uFrame;
uniform sampler2D uRef;
uniform sampler2D uAcc;
uniform vec2 uShift;
uniform float uFirst;
void main() {
    if (uFirst > 0.5) {
        FRAG_COLOR = vec4(toLin(texture(uRef, vUv).rgb), 1.0);
        return;
    }
    vec4 acc = texture(uAcc, vUv);
    vec2 fuv = vUv + uShift;
    float inside = step(0.0, fuv.x) * step(0.0, fuv.y) * step(fuv.x, 1.0) * step(fuv.y, 1.0);
    vec3 f = texture(uFrame, fuv).rgb;
    float d = abs(luma(textureLod(uFrame, fuv, 3.0).rgb) - luma(textureLod(uRef, vUv, 3.0).rgb));
    float dc = abs(luma(f) - luma(texture(uRef, vUv).rgb));
    // Coarse difference catches motion / misalignment; the fine one catches edges that moved, with
    // a wide tolerance so ordinary sensor noise still averages in.
    float w = (1.0 - smoothstep(0.012, 0.04, d)) * (1.0 - smoothstep(0.07, 0.18, dc)) * inside;
    FRAG_COLOR = acc + vec4(toLin(f) * w, w);
}
"""

        /** out = shoulder(gain * acc.rgb / acc.a) in sRGB. Knee 0.75 like the look exposure (D31). */
        private const val RESOLVE_FS = COMMON + """
IN vec2 vUv;
uniform sampler2D uAcc;
uniform float uGain;
vec3 shoulder(vec3 y) {
    const float k = 0.75;
    vec3 r = k + (1.0 - k) * (1.0 - exp(-(y - k) / (1.0 - k)));
    return mix(y, r, step(vec3(k), y));
}
void main() {
    vec4 a = texture(uAcc, vUv);
    vec3 lin = a.rgb / max(a.a, 1e-4) * uGain;
    if (uGain > 1.0) lin = shoulder(lin);
    FRAG_COLOR = vec4(toSrgb(lin), 1.0);
}
"""
    }
}
