package com.vintagelab.camera.gl

import android.opengl.GLES30
import android.util.Log

class ShaderException(msg: String) : RuntimeException(msg)

/** A linked GL program with a uniform-location cache. Unknown/unused uniforms (-1) are ignored. */
class ShaderProgram private constructor(val id: Int, val name: String) {
    private val locations = HashMap<String, Int>()

    fun use() = GLES30.glUseProgram(id)

    fun loc(uniform: String): Int = locations.getOrPut(uniform) { GLES30.glGetUniformLocation(id, uniform) }

    fun set1f(n: String, v: Float) { val l = loc(n); if (l >= 0) GLES30.glUniform1f(l, v) }
    fun set1i(n: String, v: Int) { val l = loc(n); if (l >= 0) GLES30.glUniform1i(l, v) }
    fun set2f(n: String, x: Float, y: Float) { val l = loc(n); if (l >= 0) GLES30.glUniform2f(l, x, y) }
    fun set3f(n: String, x: Float, y: Float, z: Float) { val l = loc(n); if (l >= 0) GLES30.glUniform3f(l, x, y, z) }
    fun set4f(n: String, x: Float, y: Float, z: Float, w: Float) {
        val l = loc(n); if (l >= 0) GLES30.glUniform4f(l, x, y, z, w)
    }
    fun set4fv(n: String, v: FloatArray, count: Int) { val l = loc(n); if (l >= 0) GLES30.glUniform4fv(l, count, v, 0) }
    fun setMat3(n: String, m: FloatArray) { val l = loc(n); if (l >= 0) GLES30.glUniformMatrix3fv(l, 1, false, m, 0) }
    fun setMat4(n: String, m: FloatArray) { val l = loc(n); if (l >= 0) GLES30.glUniformMatrix4fv(l, 1, false, m, 0) }

    fun release() = GLES30.glDeleteProgram(id)

    companion object {
        private const val TAG = "ShaderProgram"
        const val ATTR_POSITION = 0

        /** Compiles and links; on failure logs GL_RENDERER + info log and throws [ShaderException]. */
        fun build(name: String, vertexSrc: String, fragmentSrc: String): ShaderProgram {
            val vs = compile(name, GLES30.GL_VERTEX_SHADER, vertexSrc)
            val fs = try {
                compile(name, GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
            } catch (e: ShaderException) {
                GLES30.glDeleteShader(vs); throw e
            }
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, vs)
            GLES30.glAttachShader(p, fs)
            GLES30.glBindAttribLocation(p, ATTR_POSITION, "aPosition")
            GLES30.glLinkProgram(p)
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            val status = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(p)
                GLES30.glDeleteProgram(p)
                fail("link $name", log)
            }
            return ShaderProgram(p, name)
        }

        private fun compile(name: String, type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val status = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(s)
                GLES30.glDeleteShader(s)
                fail("compile $name (${if (type == GLES30.GL_VERTEX_SHADER) "vs" else "fs"})", log)
            }
            return s
        }

        private fun fail(what: String, log: String?): Nothing {
            val renderer = GLES30.glGetString(GLES30.GL_RENDERER)
            Log.e(TAG, "$what failed on GL_RENDERER=$renderer:\n$log")
            throw ShaderException("$what failed: $log")
        }
    }
}
