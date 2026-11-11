package com.vintagelab.camera.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import com.vintagelab.camera.capture.FrameGeometry
import com.vintagelab.camera.capture.FrameLayout
import com.vintagelab.camera.looks.IntRect
import com.vintagelab.camera.looks.LookState
import com.vintagelab.camera.looks.Preset
import com.vintagelab.camera.looks.SourceTransform
import com.vintagelab.camera.looks.UniformMapper
import com.vintagelab.camera.looks.UniformSet
import com.vintagelab.camera.looks.Vec4
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** What the viewfinder shows: the selected preset and the current look seed/frame/stamp. */
data class PreviewLook(val preset: Preset, val state: LookState)

/** Tap → metering point in preview buffer pixels (for SurfaceOrientedMeteringPointFactory). */
data class MeteringTarget(val x: Float, val y: Float, val bufWidth: Int, val bufHeight: Int)

/** Renderer → UI events (K11). */
sealed interface PreviewEvent {
    /** First camera frame drawn after a new surface (bind, rebind, context loss). */
    data object FirstFrame : PreviewEvent

    /** First frame drawn with a newly selected preset. */
    data object LookApplied : PreviewEvent

    /** Neither Pass 0 OES variant compiled: preview impossible on this GPU. */
    data object GpuUnsupported : PreviewEvent

    /** A pass fell back to passthrough (release builds). */
    data object Passthrough : PreviewEvent
}

/** Callbacks are delivered on the main thread. */
interface PreviewListener {
    fun onPreviewEvent(event: PreviewEvent)

    /** GL context was recreated: the camera must issue a new SurfaceRequest. */
    fun onContextLost()
}

/**
 * Viewfinder renderer (SPEC §6.3). Owns the camera OES texture + SurfaceTexture, created on the
 * GL thread for each [SurfaceRequest]; renders the full pass graph on every camera frame
 * (RENDERMODE_WHEN_DIRTY). The hosting view must forward onPause/onResume.
 *
 * Surface ownership: a provided Surface/SurfaceTexture is released **only** in its
 * provideSurface result listener (posted to the GL thread so it cannot race updateTexImage).
 * On GL context loss the old texture name is marked dead (never deleted in the new context).
 */
class GlPreviewRenderer(
    private val view: GLSurfaceView,
    private val readAsset: (String) -> String,
    private val luts: LutLoader,
    private val strict: Boolean,
    /** Main thread executor (CameraX callbacks). */
    private val mainExecutor: Executor,
    private val listener: PreviewListener,
) : GLSurfaceView.Renderer, Preview.SurfaceProvider {

    /** Set from the UI thread; read on the GL thread. */
    @Volatile
    var look: PreviewLook? = null
        set(value) {
            field = value
            view.requestRender()
        }

    /** Last grain time presented; the capture job freezes it at shutter (SPEC §5.2). */
    @Volatile
    var lastGrainTime: Float = 0f
        private set

    /** One SurfaceRequest and the TransformationInfo that belongs to it. */
    private class RequestSlot(val request: SurfaceRequest) {
        @Volatile
        var info: SurfaceRequest.TransformationInfo? = null
    }

    private class CameraSurface(
        val slot: RequestSlot,
        val texture: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface,
        val resolution: Size,
        val generation: Int,
    ) {
        /** Texture name belongs to a lost GL context: never delete or sample it. */
        @Volatile
        var dead = false
        var framesDrawn = 0
    }

    private val pending = AtomicReference<RequestSlot?>(null)

    /** Most recent request; TransformationInfo callbacks from older requests are ignored. */
    @Volatile
    private var currentSlot: RequestSlot? = null
    private var active: CameraSurface? = null
    private var graph: RenderGraph? = null
    private var generation = 0
    private var viewW = 1
    private var viewH = 1
    private val stMatrix = FloatArray(16)
    private val startNanos = System.nanoTime()
    private var cachedLook: PreviewLook? = null
    private var cachedUniforms: UniformSet? = null
    private var appliedPreset: Preset? = null

    private data class TapMapping(val layout: FrameLayout, val transform: SourceTransform, val bufW: Int, val bufH: Int)

    @Volatile
    private var tapMapping: TapMapping? = null

    // ---- Preview.SurfaceProvider (main thread) ----

    override fun onSurfaceRequested(request: SurfaceRequest) {
        val slot = RequestSlot(request)
        currentSlot = slot
        request.setTransformationInfoListener(mainExecutor) { info ->
            if (currentSlot === slot) slot.info = info
        }
        pending.getAndSet(slot)?.request?.willNotProvideSurface()
        view.requestRender()
    }

    // ---- GLSurfaceView.Renderer (GL thread) ----

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val hadContext = graph != null || active != null
        generation++
        // The old context is gone: its texture name is dead. The Java Surface/SurfaceTexture stay
        // alive until CameraX's result listener fires for that request.
        active?.dead = true
        active = null
        val g = RenderGraph(readAsset, luts, strict).also { it.init(RenderGraph.hasOesEssl3()) }
        graph = g
        cachedLook = null
        appliedPreset = null
        if (g.oesUnavailable) post(PreviewEvent.GpuUnsupported)
        if (g.passthrough) post(PreviewEvent.Passthrough)
        if (hadContext) {
            Log.w(TAG, "GL context recreated; requesting a new camera surface")
            mainExecutor.execute { listener.onContextLost() }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = maxOf(1, width)
        viewH = maxOf(1, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        pending.getAndSet(null)?.let { attach(it) }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val cam = active ?: return
        if (cam.dead) return
        val info = cam.slot.info ?: return
        val lk = look ?: return
        val g = graph ?: return
        if (g.oesUnavailable) return
        try {
            cam.surfaceTexture.updateTexImage()
        } catch (e: RuntimeException) {
            Log.w(TAG, "updateTexImage failed", e)
            return
        }
        // TransformationInfo is the source of truth (SPEC §6.3). When the camera writes its own
        // orientation transform into the SurfaceTexture (hasCameraTransform), that matrix would
        // rotate a second time, so sample raw buffer coordinates instead (a plain V flip).
        val texMatrix = if (info.hasCameraTransform()) {
            RAW_BUFFER_MATRIX
        } else {
            cam.surfaceTexture.getTransformMatrix(stMatrix)
            stMatrix
        }

        val bufW = cam.resolution.width
        val bufH = cam.resolution.height
        val c = info.cropRect
        val crop = IntRect(c.left, c.top, c.right, c.bottom)
        if (crop.width <= 0 || crop.height <= 0) return
        val rotation = ((info.rotationDegrees % 360) + 360) % 360
        val transform = SourceTransform(bufW, bufH, crop, rotation, info.isMirroring)

        // Render size = min(view, stream); canvas (image + frame) fitted into the view.
        val layout = FrameGeometry.fitLayout(lk.state.frame, transform.outWidth, transform.outHeight, viewW, viewH)
        val grainTime = (System.nanoTime() - startNanos) / 1e9f
        if (lk != cachedLook) {
            cachedUniforms = UniformMapper.map(lk.preset, lk.state)
            cachedLook = lk
        }
        val cropUv = Vec4(
            crop.left.toFloat() / bufW, crop.top.toFloat() / bufH,
            crop.width.toFloat() / bufW, crop.height.toFloat() / bufH,
        )
        val u = UniformMapper.withGeometry(cachedUniforms!!, cropUv, layout).copy(grainTime = grainTime)

        val x = (viewW - layout.canvasW) / 2
        val y = (viewH - layout.canvasH) / 2
        g.setLook(lk.preset)
        g.render(
            RenderInput(cam.texture, oes = true, stMatrix = texMatrix, transform = transform),
            layout.imageW, layout.imageH, u,
            RenderTarget(0, x, y, layout.canvasW, layout.canvasH, flipY = false),
        )
        lastGrainTime = grainTime
        tapMapping = TapMapping(layout, transform, bufW, bufH)
        if (cam.framesDrawn++ == 0) post(PreviewEvent.FirstFrame)
        if (appliedPreset != lk.preset) {
            if (appliedPreset != null) post(PreviewEvent.LookApplied)
            appliedPreset = lk.preset
        }
    }

    private fun post(e: PreviewEvent) = mainExecutor.execute { listener.onPreviewEvent(e) }

    private fun attach(slot: RequestSlot) {
        val request = slot.request
        val res = request.resolution
        val tex = Textures.createOes()
        val st = SurfaceTexture(tex)
        st.setDefaultBufferSize(res.width, res.height)
        st.setOnFrameAvailableListener { view.requestRender() }
        val surface = Surface(st)
        val cam = CameraSurface(slot, tex, st, surface, res, generation)
        active = cam
        try {
            request.provideSurface(surface, mainExecutor) { release(cam) }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "surface request no longer valid", e)
            active = null
            surface.release()
            st.release()
            Textures.delete(tex)
        }
    }

    /** CameraX is done with [cam]'s surface (main thread): release on the GL thread. */
    private fun release(cam: CameraSurface) {
        val onGl = Runnable {
            if (active === cam) active = null
            cam.surface.release()
            cam.surfaceTexture.release()
            if (!cam.dead && cam.generation == generation) Textures.delete(cam.texture)
        }
        if (view.isAttachedToWindow) {
            view.queueEvent(onGl)
        } else {
            // GL thread is gone with the view; nothing can race updateTexImage any more.
            cam.dead = true
            cam.surface.release()
            cam.surfaceTexture.release()
        }
    }

    /**
     * Maps a normalized point in the **image area** (K10: [0,1]², upright display orientation,
     * frame margin excluded) to the preview buffer.
     */
    fun mapNormalized(nx: Float, ny: Float): MeteringTarget? {
        val m = tapMapping ?: return null
        if (nx !in 0f..1f || ny !in 0f..1f) return null
        val (bx, by) = m.transform.uprightToBuffer(nx, ny)
        return MeteringTarget(bx * m.bufW, by * m.bufH, m.bufW, m.bufH)
    }

    private companion object {
        const val TAG = "GlPreviewRenderer"

        /** Column-major: (s, t) -> (s, 1 - t); undoes the shader's GL-orientation flip. */
        val RAW_BUFFER_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
        )
    }
}
