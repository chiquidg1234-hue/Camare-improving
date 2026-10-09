package com.lumacam.gl

import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Programa GLSL con caché de ubicaciones de uniforms. */
class GlProgram(vertexSrc: String, fragmentSrc: String, private val name: String) {
    val id: Int
    private val uniforms = HashMap<String, Int>()
    val aPosition: Int
    val aTexCoord: Int

    init {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vs)
        GLES20.glAttachShader(id, fs)
        GLES20.glLinkProgram(id)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(id)
            GLES20.glDeleteProgram(id)
            throw IllegalStateException("Error enlazando $name: $log")
        }
        aPosition = GLES20.glGetAttribLocation(id, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(id, "aTexCoord")
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Error compilando $name (${if (type == GLES20.GL_VERTEX_SHADER) "vértice" else "fragmento"}): $log")
        }
        return shader
    }

    fun use() = GLES20.glUseProgram(id)

    fun loc(uniform: String): Int = uniforms.getOrPut(uniform) { GLES20.glGetUniformLocation(id, uniform) }

    fun set1f(name: String, v: Float) {
        val l = loc(name); if (l >= 0) GLES20.glUniform1f(l, v)
    }

    fun set2f(name: String, a: Float, b: Float) {
        val l = loc(name); if (l >= 0) GLES20.glUniform2f(l, a, b)
    }

    fun set3f(name: String, a: Float, b: Float, c: Float) {
        val l = loc(name); if (l >= 0) GLES20.glUniform3f(l, a, b, c)
    }

    fun set1i(name: String, v: Int) {
        val l = loc(name); if (l >= 0) GLES20.glUniform1i(l, v)
    }

    fun set1fv(name: String, v: FloatArray) {
        val l = loc(name); if (l >= 0) GLES20.glUniform1fv(l, v.size, v, 0)
    }

    fun setMatrix(name: String, m: FloatArray) {
        val l = loc(name); if (l >= 0) GLES20.glUniformMatrix4fv(l, 1, false, m, 0)
    }

    /** Dibuja el rectángulo a pantalla completa. */
    fun drawQuad() {
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, QUAD_POS)
        if (aTexCoord >= 0) {
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, QUAD_TEX)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        if (aTexCoord >= 0) GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    fun release() = GLES20.glDeleteProgram(id)

    companion object {
        private val QUAD_POS: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val QUAD_TEX: FloatBuffer = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

        private fun floatBuffer(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(a); position(0)
            }

        fun checkError(op: String) {
            val e = GLES20.glGetError()
            if (e != GLES20.GL_NO_ERROR) Log.w("LumaGL", "$op: glError 0x${Integer.toHexString(e)}")
        }
    }
}
