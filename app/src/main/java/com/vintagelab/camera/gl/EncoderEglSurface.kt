package com.vintagelab.camera.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/** Encoder window surface using the GLSurfaceView's current ES3 context. GL-thread only. */
class EncoderEglSurface(surface: Surface) {
    private val display: EGLDisplay = EGL14.eglGetCurrentDisplay()
    private val context = EGL14.eglGetCurrentContext()
    private val eglSurface: EGLSurface

    init {
        check(display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT) { "no current preview EGL context" }
        val configId = IntArray(1)
        check(EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, configId, 0)) { "cannot query preview EGL config" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attrs = intArrayOf(EGL14.EGL_CONFIG_ID, configId[0], EGL14.EGL_NONE)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] == 1) { "preview EGL config unavailable" }
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "encoder EGL surface failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
    }

    /** Switches to the encoder, renders, timestamps and swaps, then restores the preview surface. */
    fun present(timestampNs: Long, render: () -> Unit): Boolean {
        val oldDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val oldRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) return false
        return try {
            render()
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, timestampNs)
            EGL14.eglSwapBuffers(display, eglSurface)
        } finally {
            EGL14.eglMakeCurrent(display, oldDraw, oldRead, context)
        }
    }

    fun release() {
        EGL14.eglDestroySurface(display, eglSurface)
    }

    companion object {
        /** Required on the GLSurfaceView config so its context is compatible with MediaCodec surfaces. */
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val EGL_OPENGL_ES3_BIT_KHR = 0x40
        const val EGL_RENDERABLE_TYPE = 0x3040
    }
}
