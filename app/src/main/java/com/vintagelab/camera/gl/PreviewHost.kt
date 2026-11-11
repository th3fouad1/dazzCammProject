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
