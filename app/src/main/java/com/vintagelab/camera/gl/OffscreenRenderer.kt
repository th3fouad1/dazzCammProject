package com.vintagelab.camera.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import com.vintagelab.camera.capture.FrameGeometry
import com.vintagelab.camera.looks.LookState
import com.vintagelab.camera.looks.Preset
import com.vintagelab.camera.looks.SourceTransform
import com.vintagelab.camera.looks.UniformMapper
import com.vintagelab.camera.looks.Vec4
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Capture-side renderer: its own non-shared ES 3.0 context on a pbuffer, on the `capture-gl`
 * HandlerThread (SPEC §6.3, D5). Compiles its own programs and LUTs from the same assets.
 */
class OffscreenRenderer(
    private val readAsset: (String) -> String,
    private val luts: LutLoader,
    private val strict: Boolean,
) {
    private val thread = HandlerThread("capture-gl").apply { start() }
    private val dispatcher = Handler(thread.looper).asCoroutineDispatcher("capture-gl")
    private var egl: EglCore? = null
    private var graph: RenderGraph? = null
    private var limits = Limits(4096, 4096)

    /** [maxLongSide]: render cap; [maxTexture]: largest decodable input texture. */
    data class Limits(val maxLongSide: Int, val maxTexture: Int)

    suspend fun limits(): Limits = withContext(dispatcher) { ensure(); limits }

    /**
     * Uploads [bitmap] (recycled here right after upload), renders the full graph at the capped
     * size with Pass 4 flipped for readback, and returns a new ARGB_8888 bitmap of the canvas.
     */
    suspend fun render(
        bitmap: Bitmap,
        transform: SourceTransform,
        preset: Preset,
        state: LookState,
        maxLongSide: Int,
    ): Bitmap = withContext(dispatcher) {
        ensure()
        val g = graph!!
        val cap = minOf(maxLongSide, limits.maxLongSide)
        val layout = FrameGeometry.fitLayout(state.frame, transform.outWidth, transform.outHeight, cap, cap)
        val t = transform
        val crop = Vec4(
            t.crop.left.toFloat() / t.bufWidth, t.crop.top.toFloat() / t.bufHeight,
            t.crop.width.toFloat() / t.bufWidth, t.crop.height.toFloat() / t.bufHeight,
        )
        val u = UniformMapper.withGeometry(UniformMapper.map(preset, state), crop, layout)

        var tex = Textures.fromBitmap(bitmap)
        bitmap.recycle()
        val target = Fbo(layout.canvasW, layout.canvasH)
        try {
            g.setLook(preset)
            g.render(
                RenderInput(tex, oes = false, stMatrix = null, transform = transform),
                layout.imageW, layout.imageH, u,
                RenderTarget(target.framebuffer, 0, 0, layout.canvasW, layout.canvasH, flipY = true),
                onInputConsumed = { Textures.delete(tex); tex = 0 },
            )
            val bytes = layout.canvasW * layout.canvasH * 4
            // Not retained across shots: the caller compresses right after, and the readback
            // buffer must be unreachable by then (SPEC §6.5 memory plan, ≤ 2 full-size buffers).
            val buf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.framebuffer)
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
            GLES30.glReadPixels(0, 0, layout.canvasW, layout.canvasH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            val err = GLES30.glGetError()
            check(err == GLES30.GL_NO_ERROR) { "GL error 0x${Integer.toHexString(err)} during capture render" }
            buf.position(0).limit(bytes)
            val out = Bitmap.createBitmap(layout.canvasW, layout.canvasH, Bitmap.Config.ARGB_8888)
            out.copyPixelsFromBuffer(buf)
            out.setHasAlpha(false)
            out
        } finally {
            Textures.delete(tex)
            target.release()
            g.trimMemory()
        }
    }

    /** Drops the intermediate targets (onTrimMemory / OOM retry). */
    suspend fun trim() = withContext(dispatcher) {
        graph?.trimMemory()
    }

    private fun ensure() {
        if (egl != null) return
        val core = EglCore()
        core.init()
        egl = core
        limits = Limits(RenderGraph.maxLongSide(), maxTextureSize())
        graph = RenderGraph(readAsset, luts, strict).also { it.init(hasOesEssl3 = false, needsOes = false) }
    }

    private fun maxTextureSize(): Int {
        val v = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, v, 0)
        return v[0]
    }

    fun release() {
        Handler(thread.looper).post {
            graph?.release()
            graph = null
            egl?.release()
            egl = null
            thread.quitSafely()
        }
    }
}
