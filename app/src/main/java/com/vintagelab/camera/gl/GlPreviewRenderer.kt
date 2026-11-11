package com.vintagelab.camera.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
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

/**
 * Viewfinder renderer (SPEC §6.3). Owns the camera OES texture + SurfaceTexture, created on the
 * GL thread for each [SurfaceRequest]; renders the full pass graph on every camera frame
 * (RENDERMODE_WHEN_DIRTY). The hosting view must forward onPause/onResume.
 */
class GlPreviewRenderer(
    private val view: GLSurfaceView,
    private val readAsset: (String) -> String,
    private val luts: LutLoader,
    private val strict: Boolean,
    /** Main thread executor (CameraX callbacks). */
    private val mainExecutor: Executor,
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

    /** Called on the main thread after a GL context loss: rebind the surface provider. */
    var onContextLost: (() -> Unit)? = null

    private class CameraSurface(
        val request: SurfaceRequest,
        val texture: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface,
        val resolution: Size,
        val generation: Int,
    )

    private val pending = AtomicReference<SurfaceRequest?>(null)
    private var active: CameraSurface? = null
    private var graph: RenderGraph? = null
    private var generation = 0
    private var viewW = 1
    private var viewH = 1
    private val stMatrix = FloatArray(16)
    private val startNanos = System.nanoTime()
    private var cachedLook: PreviewLook? = null
    private var cachedUniforms: UniformSet? = null

    @Volatile
    private var transformationInfo: SurfaceRequest.TransformationInfo? = null

    private data class TapMapping(
        val viewW: Int,
        val viewH: Int,
        val layout: FrameLayout,
        val transform: SourceTransform,
        val bufW: Int,
        val bufH: Int,
    )

    @Volatile
    private var tapMapping: TapMapping? = null

    // ---- Preview.SurfaceProvider (main thread) ----

    override fun onSurfaceRequested(request: SurfaceRequest) {
        request.setTransformationInfoListener(mainExecutor) { transformationInfo = it }
        pending.getAndSet(request)?.willNotProvideSurface()
        view.requestRender()
    }

    // ---- GLSurfaceView.Renderer (GL thread) ----

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val hadContext = graph != null || active != null
        generation++
        active?.let {
            // The texture died with the old context; CameraX must issue a new request.
            it.surface.release()
            it.surfaceTexture.release()
        }
        active = null
        graph = RenderGraph(readAsset, luts, strict).also { it.init(RenderGraph.hasOesEssl3()) }
        cachedLook = null
        if (hadContext) {
            Log.w(TAG, "GL context recreated; requesting a new camera surface")
            mainExecutor.execute { onContextLost?.invoke() }
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
        val info = transformationInfo ?: return
        val lk = look ?: return
        val g = graph ?: return
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
        tapMapping = TapMapping(viewW, viewH, layout, transform, bufW, bufH)
    }

    private fun attach(request: SurfaceRequest) {
        val res = request.resolution
        val tex = Textures.createOes()
        val st = SurfaceTexture(tex)
        st.setDefaultBufferSize(res.width, res.height)
        st.setOnFrameAvailableListener { view.requestRender() }
        val surface = Surface(st)
        val gen = generation
        val cam = CameraSurface(request, tex, st, surface, res, gen)
        active = cam
        try {
            request.provideSurface(surface, mainExecutor) {
                // Release only once CameraX is done with the surface.
                surface.release()
                st.release()
                view.queueEvent {
                    if (gen == generation && EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT) Textures.delete(tex)
                    if (active === cam) active = null
                }
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "surface request no longer valid", e)
            surface.release()
            st.release()
            Textures.delete(tex)
            if (active === cam) active = null
        }
    }

    /** Maps a tap in view pixels (origin top-left) to the preview buffer; null outside the image. */
    fun mapTap(x: Float, y: Float): MeteringTarget? {
        val m = tapMapping ?: return null
        val left = (m.viewW - m.layout.canvasW) / 2f + m.layout.imageLeft
        val top = (m.viewH - m.layout.canvasH) / 2f + m.layout.imageTop
        val u = (x - left) / m.layout.imageW
        val v = (y - top) / m.layout.imageH
        if (u !in 0f..1f || v !in 0f..1f) return null
        val (bx, by) = m.transform.uprightToBuffer(u, v)
        return MeteringTarget(bx * m.bufW, by * m.bufH, m.bufW, m.bufH)
    }

    /** Tap position (view pixels) is inside the image area. */
    fun isInImage(x: Float, y: Float): Boolean = mapTap(x, y) != null

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
