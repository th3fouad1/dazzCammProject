package com.vintagelab.camera.gl

import android.content.Context
import android.opengl.GLSurfaceView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Viewfinder host (K11). [createView] is called from the `AndroidView` factory (never hold the
 * View: it is disposed when the Camera route leaves composition). [events] carries renderer events.
 */
class PreviewHost(
    private val readAsset: (String) -> String,
    private val luts: LutLoader,
    private val strict: Boolean,
) {
    private val _events = MutableSharedFlow<PreviewEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<PreviewEvent> = _events.asSharedFlow()

    /** Called on the main thread when a renderer is created or its context is lost. */
    var onRendererReady: ((GlPreviewRenderer) -> Unit)? = null
    var onContextLost: (() -> Unit)? = null

    fun createView(context: Context): GLSurfaceView = GLSurfaceView(context).apply {
        setEGLContextClientVersion(3)
        setEGLConfigChooser { egl, display ->
            val attrs = intArrayOf(
                javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_ALPHA_SIZE, 8,
                EncoderEglSurface.EGL_RENDERABLE_TYPE, EncoderEglSurface.EGL_OPENGL_ES3_BIT_KHR,
                EncoderEglSurface.EGL_RECORDABLE_ANDROID, 1,
                javax.microedition.khronos.egl.EGL10.EGL_NONE,
            )
            val count = IntArray(1)
            check(egl.eglChooseConfig(display, attrs, null, 0, count) && count[0] > 0) { "no recordable ES3 EGL config" }
            val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(count[0])
            check(egl.eglChooseConfig(display, attrs, configs, configs.size, count)) { "recordable EGL config query failed" }
            configs[0] ?: error("recordable EGL config missing")
        }
        preserveEGLContextOnPause = true
        val r = GlPreviewRenderer(
            this, readAsset, luts, strict, ContextCompat.getMainExecutor(context),
            object : PreviewListener {
                override fun onPreviewEvent(event: PreviewEvent) {
                    _events.tryEmit(event)
                }

                override fun onContextLost() {
                    onContextLost?.invoke()
                }
            },
        )
        setRenderer(r)
        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        tag = r
        onRendererReady?.invoke(r)
    }

    companion object {
        fun rendererOf(view: GLSurfaceView): GlPreviewRenderer? = view.tag as? GlPreviewRenderer
    }
}
