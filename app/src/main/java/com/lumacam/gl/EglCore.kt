package com.lumacam.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * Contexto EGL propio para el hilo de render. Usa una configuración "recordable" para poder
 * dibujar también en la superficie del codificador de video.
 */
class EglCore {
    val display: EGLDisplay
    val config: EGLConfig
    val context: EGLContext
    val glesVersion: Int
    private val pbuffer: EGLSurface

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "Sin pantalla EGL" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize falló" }

        var chosen = chooseConfig(3)
        var ver = 3
        if (chosen == null) {
            chosen = chooseConfig(2)
            ver = 2
        }
        config = checkNotNull(chosen) { "No hay configuración EGL compatible" }

        var ctx = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, ver, EGL14.EGL_NONE), 0,
        )
        if ((ctx == null || ctx == EGL14.EGL_NO_CONTEXT) && ver == 3) {
            ver = 2
            ctx = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
        }
        check(ctx != null && ctx != EGL14.EGL_NO_CONTEXT) { "eglCreateContext falló: ${EGL14.eglGetError()}" }
        context = ctx
        glesVersion = ver

        pbuffer = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        check(pbuffer != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface falló" }
        makeCurrentPbuffer()
    }

    private fun chooseConfig(version: Int): EGLConfig? {
        val renderable = if (version >= 3) EGLExt.EGL_OPENGL_ES3_BIT_KHR else EGL14.EGL_OPENGL_ES2_BIT
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, renderable,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] <= 0) return null
        return configs[0]
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        val s = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(s != null && s != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface falló: ${EGL14.eglGetError()}" }
        return s
    }

    fun makeCurrent(surface: EGLSurface): Boolean =
        EGL14.eglMakeCurrent(display, surface, surface, context)

    fun makeCurrentPbuffer(): Boolean = makeCurrent(pbuffer)

    fun swap(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun setPresentationTime(surface: EGLSurface, nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanos)
    }

    fun querySize(surface: EGLSurface, out: IntArray) {
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, out, 0)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, out, 1)
    }

    fun destroySurface(surface: EGLSurface) {
        EGL14.eglDestroySurface(display, surface)
    }

    /**
     * Libera el contexto. No se llama a eglTerminate porque la pantalla EGL por defecto es
     * compartida con CameraX y el resto del proceso.
     */
    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, pbuffer)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
    }
}
