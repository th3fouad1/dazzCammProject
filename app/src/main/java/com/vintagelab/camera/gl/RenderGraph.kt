package com.vintagelab.camera.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import com.vintagelab.camera.capture.FrameGeometry
import com.vintagelab.camera.looks.LutLayout
import com.vintagelab.camera.looks.Preset
import com.vintagelab.camera.looks.SourceTransform
import com.vintagelab.camera.looks.UniformSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/** Source of Pass 0: the camera OES texture (preview) or a decoded bitmap texture (capture). */
class RenderInput(
    val texture: Int,
    val oes: Boolean,
    /** SurfaceTexture transform matrix (OES only). */
    val stMatrix: FloatArray?,
    val transform: SourceTransform,
)

/** Where Pass 4 draws: framebuffer 0 = window surface. Viewport in target pixels. */
class RenderTarget(
    val framebuffer: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /** True for the capture FBO: rows come back top-first from glReadPixels. */
    val flipY: Boolean,
)

/** Optional extra inputs (SPEC 7.4): texture of the stored first exposure (capture blend) and the preview ghost. Textures use bitmap row order. */
class RenderExtras(
    val blendTex: Int = 0,
    val ghostTex: Int = 0,
    val ghostAlpha: Float = 0f,
    val ghostMirror: Boolean = false,
)

/**
 * The fixed pass graph of SPEC §5 / §7.2, one instance per GL context:
 * Pass 0 normalize → Pass 1 geometry → Pass 2 pyramid (512/256/128) → Pass 3 combine → Pass 4 overlay.
 * Frozen interface after Phase A: [init], [setLook], [render], [release].
 */
class RenderGraph(
    private val readAsset: (String) -> String,
    private val luts: LutLoader,
    /** Debug: shader failures crash. Release: failing passes fall back to passthrough. */
    private val strict: Boolean,
) {
    private val programs = HashMap<ShaderSources.Pass, ShaderProgram>()
    private var quad: FloatBuffer? = null
    private var lutTexture = 0
    private var lutPreset: Preset? = null
    private var fboA: Fbo? = null
    private var fboB: Fbo? = null
    private val pyramid = arrayOfNulls<Fbo>(3)

    /** True when Pass 0 OES had to use the ESSL 1.00 fallback. */
    var oesFallback = false
        private set

    /** True when neither OES variant compiled: the camera preview cannot be shown on this GPU. */
    var oesUnavailable = false
        private set

    /** True when any pass fell back to passthrough (release builds only). */
    var passthrough = false
        private set

    /** @param needsOes false for the capture context, which only renders sampler2D input. */
    fun init(hasOesEssl3: Boolean, needsOes: Boolean = true) {
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
        }
        val vs3 = ShaderSources.vertex(ShaderSources.Lang.ESSL3, readAsset)
        val copy = build(ShaderSources.Pass.COPY, vs3, ShaderSources.Lang.ESSL3, required = true)!!
        for (pass in ShaderSources.Pass.entries) {
            if (pass == ShaderSources.Pass.COPY) continue
            if (pass == ShaderSources.Pass.PASS0_OES) {
                if (!needsOes) continue
                // Never substitute the sampler2D COPY program for an OES input.
                val p = buildOes(hasOesEssl3)
                if (p == null) oesUnavailable = true else programs[pass] = p
                continue
            }
            val p = build(pass, vs3, ShaderSources.Lang.ESSL3)
            if (p == null) passthrough = true
            programs[pass] = p ?: copy
        }
        programs[ShaderSources.Pass.COPY] = copy
        for (p in programs.values.distinct()) {
            p.use()
            p.set1i("uInput", 0)
            p.set1i("uPyr0", 1)
            p.set1i("uPyr1", 2)
            p.set1i("uPyr2", 3)
            p.set1i("uLookLut", 4)
            p.set1i("uBlend", 5)
            p.set1i("uGhost", 5)
        }
        lutTexture = Textures.create3D(LutLayout.SIZE, identityLut())
        lutPreset = null
    }

    private fun buildOes(hasEssl3: Boolean): ShaderProgram? {
        if (hasEssl3) {
            val p = build(ShaderSources.Pass.PASS0_OES, ShaderSources.vertex(ShaderSources.Lang.ESSL3, readAsset), ShaderSources.Lang.ESSL3, required = false)
            if (p != null) return p
        }
        oesFallback = true
        Log.w(TAG, "using ESSL 1.00 Pass 0 (GL_OES_EGL_image_external)")
        return build(ShaderSources.Pass.PASS0_OES, ShaderSources.vertex(ShaderSources.Lang.ESSL1, readAsset), ShaderSources.Lang.ESSL1, required = false)
    }

    private fun build(pass: ShaderSources.Pass, vs: String, lang: ShaderSources.Lang, required: Boolean = strict): ShaderProgram? =
        try {
            ShaderProgram.build(pass.name, vs, ShaderSources.fragment(pass, lang, readAsset))
        } catch (e: ShaderException) {
            if (required) throw e
            Log.e(TAG, "pass ${pass.name} unavailable", e)
            null
        }

    /** Uploads the baked look cube when the preset changes. */
    fun setLook(preset: Preset) {
        if (preset == lutPreset) return
        lutTexture = Textures.create3D(LutLayout.SIZE, luts.baked(preset), lutTexture)
        lutPreset = preset
    }

    /**
     * Renders the whole graph. [imageW]×[imageH] is the upright image-area render size; the
     * uniforms must already carry geometry for that size (UniformMapper.withGeometry).
     */
    fun render(
        input: RenderInput,
        imageW: Int,
        imageH: Int,
        u: UniformSet,
        target: RenderTarget,
        onInputConsumed: (() -> Unit)? = null,
        extras: RenderExtras? = null,
    ) {
        ensureTargets(imageW, imageH)
        val a = fboA!!
        val b = fboB!!
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)

        // Pass 0 — normalize
        val p0 = programs[if (input.oes) ShaderSources.Pass.PASS0_OES else ShaderSources.Pass.PASS0_2D]
            ?: throw IllegalStateException("Pass 0 unavailable for ${if (input.oes) "OES" else "2D"} input")
        a.bind()
        p0.use()
        bindTex(0, if (input.oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D, input.texture)
        p0.setMat3("uSrcMatrix", input.transform.matrix())
        input.stMatrix?.let { p0.setMat4("uStMatrix", it) }
        val t = input.transform
        p0.set4f(
            "uCropRect",
            t.crop.left.toFloat() / t.bufWidth, t.crop.top.toFloat() / t.bufHeight,
            t.crop.width.toFloat() / t.bufWidth, t.crop.height.toFloat() / t.bufHeight,
        )
        identityLayout(p0)
        draw()
        GLES30.glFlush()
        bindTex(0, if (input.oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D, 0)
        onInputConsumed?.invoke()

        // Pass 1 — geometry
        val p1 = programs.getValue(ShaderSources.Pass.GEOMETRY)
        b.bind()
        p1.use()
        bindTex(0, GLES30.GL_TEXTURE_2D, a.texture)
        applyLook(p1, u)
        identityLayout(p1)
        draw()
        GLES30.glFlush()

        // Pass 2 — pyramid
        val pp = programs.getValue(ShaderSources.Pass.PYRAMID)
        var src = b
        for (lvl in pyramid) {
            val dst = lvl!!
            dst.bind()
            pp.use()
            bindTex(0, GLES30.GL_TEXTURE_2D, src.texture)
            pp.set2f("uFootprint", 1f / dst.width, 1f / dst.height)
            identityLayout(pp)
            draw()
            src = dst
        }
        GLES30.glFlush()

        // Pass 3 — combine (B + pyramid + LUT → A)
        val p3 = programs.getValue(ShaderSources.Pass.COMBINE)
        a.bind()
        p3.use()
        bindTex(0, GLES30.GL_TEXTURE_2D, b.texture)
        bindTex(1, GLES30.GL_TEXTURE_2D, pyramid[0]!!.texture)
        bindTex(2, GLES30.GL_TEXTURE_2D, pyramid[1]!!.texture)
        bindTex(3, GLES30.GL_TEXTURE_2D, pyramid[2]!!.texture)
        bindTex(4, GLES30.GL_TEXTURE_3D, lutTexture)
        val blend = extras?.blendTex ?: 0
        bindTex(5, GLES30.GL_TEXTURE_2D, blend)
        p3.set1f("uBlendOn", if (blend != 0) 1f else 0f)
        p3.set4f("uGel", u.gel.x, u.gel.y, u.gel.z, u.gel.w)
        applyLook(p3, u)
        identityLayout(p3)
        draw()
        GLES30.glFlush()

        // Pass 4 — overlay (frame + stamp) to the target
        val p4 = programs.getValue(ShaderSources.Pass.OVERLAY)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.framebuffer)
        GLES30.glViewport(target.x, target.y, target.width, target.height)
        p4.use()
        bindTex(0, GLES30.GL_TEXTURE_2D, a.texture)
        applyLook(p4, u)
        p4.set4f("uImageRect", u.imageRect.x, u.imageRect.y, u.imageRect.z, u.imageRect.w)
        p4.set1f("uFlipY", if (target.flipY) 1f else 0f)
        val ghost = extras?.ghostTex ?: 0
        bindTex(5, GLES30.GL_TEXTURE_2D, ghost)
        p4.set1f("uGhostAlpha", if (ghost != 0) extras!!.ghostAlpha else 0f)
        p4.set1f("uGhostMirror", if (extras?.ghostMirror == true) 1f else 0f)
        draw()
        GLES30.glFlush()
        for (unit in 1..3) bindTex(unit, GLES30.GL_TEXTURE_2D, 0)
        bindTex(4, GLES30.GL_TEXTURE_3D, 0)
        bindTex(5, GLES30.GL_TEXTURE_2D, 0)
    }

    /** Sets every §7.2 uniform (and the Pass 4 layout ones); unused names are ignored. */
    private fun applyLook(p: ShaderProgram, u: UniformSet) {
        p.set1f("uShortSide", u.shortSide)
        p.set2f("uOutSize", u.outSize.x, u.outSize.y)
        p.set1f("uLutStrength", u.lutStrength)
        p.set3f("uGrain", u.grain.x, u.grain.y, u.grain.z)
        p.set1f("uGrainTime", u.grainTime)
        p.set4f("uVignette", u.vignette.x, u.vignette.y, u.vignette.z, u.vignette.w)
        p.set2f("uCenterGlow", u.centerGlow.x, u.centerGlow.y)
        p.set1f("uSoftFocus", u.softFocus)
        p.set2f("uBloom", u.bloom.x, u.bloom.y)
        p.set1f("uSharpen", u.sharpen)
        p.set2f("uDistortion", u.distortion.x, u.distortion.y)
        p.set2f("uPrism", u.prism.x, u.prism.y)
        p.set4f("uLeak", u.leak.x, u.leak.y, u.leak.z, u.leak.w)
        p.set2f("uLeakPos", u.leakPos.x, u.leakPos.y)
        p.set1f("uDust", u.dust)
        p.set1f("uSeed", u.seed)
        p.set1i("uFrame", u.frame)
        p.set4f("uStampColor", u.stampColor.x, u.stampColor.y, u.stampColor.z, u.stampColor.w)
        p.set1f("uStampOn", u.stampOn)
        p.set4fv("uStampDigits", FloatArray(8) { u.stampDigits.getOrElse(it) { 10f } }, 2)
        p.set2f("uCanvasSize", u.canvasSize.x, u.canvasSize.y)
        val fc = FrameGeometry.INSTANT_COLOR
        p.set3f("uFrameColor", ((fc shr 16) and 0xFF) / 255f, ((fc shr 8) and 0xFF) / 255f, (fc and 0xFF) / 255f)
        val mc = FrameGeometry.MOUNT_COLOR
        p.set3f("uMountColor", ((mc shr 16) and 0xFF) / 255f, ((mc shr 8) and 0xFF) / 255f, (mc and 0xFF) / 255f)
        val f1 = FrameGeometry.F120_COLOR
        p.set3f("uFilmColor", ((f1 shr 16) and 0xFF) / 255f, ((f1 shr 8) and 0xFF) / 255f, (f1 and 0xFF) / 255f)
        p.set4f("uSprocket", FrameGeometry.SPROCKET_PITCH, FrameGeometry.SPROCKET_W, FrameGeometry.SPROCKET_H, FrameGeometry.SPROCKET_RADIUS)
        p.set4f("uTicks", FrameGeometry.F120_TICK_PITCH, FrameGeometry.F120_TICK_W, FrameGeometry.F120_TICK_H, FrameGeometry.F120_BORDER)
        p.set4f("uMount", FrameGeometry.MOUNT_RADIUS, FrameGeometry.MOUNT_SHADOW_BLUR, FrameGeometry.MOUNT_SHADOW_STRENGTH, 0f)
        p.set2f("uMountShadowOff", FrameGeometry.MOUNT_SHADOW_OFFSET_X, FrameGeometry.MOUNT_SHADOW_OFFSET_Y)
        p.set4f("uPano", FrameGeometry.PANO_PITCH, FrameGeometry.PANO_HOLE_W, FrameGeometry.PANO_HOLE_H, FrameGeometry.PANO_REBATE)
        p.set2f("uPanoB", FrameGeometry.PANO_HOLE_RADIUS, FrameGeometry.PANO_BLEED)
        p.set2f("uStampGeom", FrameGeometry.STAMP_INSET, FrameGeometry.STAMP_CAP_HEIGHT)
    }

    /** Passes 0–3 (and their passthrough fallback) render the full image area unflipped. */
    private fun identityLayout(p: ShaderProgram) {
        p.set4f("uImageRect", 0f, 0f, 1f, 1f)
        p.set1f("uFlipY", 0f)
    }

    private fun ensureTargets(w: Int, h: Int) {
        val a = fboA
        if (a != null && a.width == w && a.height == h) return
        releaseTargets()
        fboA = Fbo(w, h)
        fboB = Fbo(w, h)
        val short = minOf(w, h).toFloat()
        PYRAMID_SHORT_SIDES.forEachIndexed { i, s ->
            val k = s / short
            pyramid[i] = Fbo(maxOf(1, (w * k).roundToInt()), maxOf(1, (h * k).roundToInt()))
        }
    }

    /** Frees the full-size intermediate targets (capture context, after each shot). */
    fun trimMemory() = releaseTargets()

    private fun releaseTargets() {
        fboA?.release(); fboA = null
        fboB?.release(); fboB = null
        for (i in pyramid.indices) { pyramid[i]?.release(); pyramid[i] = null }
    }

    private fun bindTex(unit: Int, target: Int, tex: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(target, tex)
    }

    private fun draw() {
        val q = quad ?: return
        q.position(0)
        GLES30.glEnableVertexAttribArray(ShaderProgram.ATTR_POSITION)
        GLES30.glVertexAttribPointer(ShaderProgram.ATTR_POSITION, 2, GLES30.GL_FLOAT, false, 0, q)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(ShaderProgram.ATTR_POSITION)
    }

    fun release() {
        releaseTargets()
        programs.values.distinct().forEach { it.release() }
        programs.clear()
        Textures.delete(lutTexture)
        lutTexture = 0
        lutPreset = null
    }

    companion object {
        private const val TAG = "RenderGraph"
        val PYRAMID_SHORT_SIDES = floatArrayOf(512f, 256f, 128f)

        /** Long-side cap for any render target (SPEC §6.5). Needs a current context. */
        fun maxLongSide(cap: Int = 4096): Int {
            val v = IntArray(2)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, v, 0)
            var m = minOf(cap, v[0])
            GLES30.glGetIntegerv(GLES30.GL_MAX_RENDERBUFFER_SIZE, v, 0)
            m = minOf(m, v[0])
            GLES30.glGetIntegerv(GLES30.GL_MAX_VIEWPORT_DIMS, v, 0)
            return minOf(m, v[0], v[1])
        }

        fun hasOesEssl3(): Boolean =
            GLES30.glGetString(GLES30.GL_EXTENSIONS)?.contains("GL_OES_EGL_image_external_essl3") == true

        private fun identityLut(): ByteArray {
            val f = LutLayout.identity()
            return ByteArray(LutLayout.ENTRIES * 4) { i ->
                if (i % 4 == 3) 0xFF.toByte() else LutLayout.to8(f[(i / 4) * 3 + i % 4]).toByte()
            }
        }
    }
}
