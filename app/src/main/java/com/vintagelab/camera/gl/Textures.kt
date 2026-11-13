package com.vintagelab.camera.gl

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** RGBA8 colour texture + framebuffer (no sRGB formats, SPEC §5.2). */
class Fbo(val width: Int, val height: Int) {
    val texture: Int = Textures.create2D(width, height)
    val framebuffer: Int

    init {
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            release()
            error("FBO ${width}x$height incomplete: 0x${Integer.toHexString(status)}")
        }
    }

    fun bind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, width, height)
    }

    fun release() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
    }
}

object Textures {
    fun create2D(w: Int, h: Int): Int {
        val t = gen()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        params(GLES30.GL_TEXTURE_2D)
        return t
    }

    /** Uploads a bitmap (row 0 → t = 0). The caller recycles the bitmap afterwards. */
    fun fromBitmap(bmp: Bitmap): Int {
        val t = gen()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        params(GLES30.GL_TEXTURE_2D)
        return t
    }

    fun createOes(): Int {
        val t = gen()
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t)
        params(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        return t
    }

    /** 33³ RGBA8 look LUT as sampler3D (LINEAR, CLAMP_TO_EDGE). */
    fun create3D(size: Int, rgba: ByteArray, existing: Int = 0): Int {
        val t = if (existing != 0) existing else gen()
        val buf = ByteBuffer.allocateDirect(rgba.size).order(ByteOrder.nativeOrder())
        buf.put(rgba).position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, t)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA8, size, size, size, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        params(GLES30.GL_TEXTURE_3D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        return t
    }

    fun delete(t: Int) {
        if (t != 0) GLES30.glDeleteTextures(1, intArrayOf(t), 0)
    }

    private fun gen(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        return ids[0]
    }

    private fun params(target: Int) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }
}
