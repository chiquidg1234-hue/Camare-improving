package com.lumacam.gl

import android.content.res.AssetManager
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import com.lumacam.core.look.LookProcessor
import com.lumacam.core.look.Lut3D
import com.lumacam.core.look.ResolvedLook
import com.lumacam.core.look.ToneCurve
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Dibuja cada frame de la cámara aplicando el look. Todas las llamadas deben hacerse en el hilo
 * GL dueño de este objeto.
 *
 * Por frame: (1) si hay contraste local, se calcula la luminancia a 1/4 de resolución y se
 * desenfoca (gaussiano separable), en el espacio de la textura de entrada; (2) para cada salida
 * (pantalla, codificador de video) se dibuja el pipeline completo con su matriz de transformación.
 */
class LookRenderer(private val assets: AssetManager) {
    private lateinit var egl: EglCore
    private lateinit var mainProgram: GlProgram
    private lateinit var downProgram: GlProgram
    private lateinit var blurProgram: GlProgram

    var inputTextureId: Int = 0
        private set
    private var curveTex = 0
    private var lutTex = 0
    private val blurTex = IntArray(2)
    private val blurFbo = IntArray(2)
    private var blurW = 0
    private var blurH = 0

    private var inputW = 0
    private var inputH = 0

    private val outputs = HashMap<Surface, EGLSurface>()
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val size = IntArray(2)

    private var look: ResolvedLook = ResolvedLook.NEUTRAL
    private var uploadedCurve: ToneCurve? = null
    private var uploadedLut: Lut3D? = null
    private val blurWeights = FloatArray(5)

    var isReady = false
        private set
    val glesVersion: Int get() = if (::egl.isInitialized) egl.glesVersion else 0

    fun init() {
        egl = EglCore()
        val vert = asset("shaders/look.vert")
        val lookFrag = asset("shaders/look.frag")
        val downFrag = asset("shaders/luma_down.frag")
        val blurFrag = asset("shaders/blur.frag")
        mainProgram = GlProgram(vert, OES_DEFINE + lookFrag, "look")
        downProgram = GlProgram(vert, OES_DEFINE + downFrag, "luma_down")
        blurProgram = GlProgram(vert, blurFrag, "blur")

        val tex = IntArray(3)
        GLES20.glGenTextures(3, tex, 0)
        inputTextureId = tex[0]
        curveTex = tex[1]
        lutTex = tex[2]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
        setLinearClamp(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTex)
        setLinearClamp(GLES20.GL_TEXTURE_2D)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex)
        setLinearClamp(GLES20.GL_TEXTURE_2D)

        GLES20.glGenTextures(2, blurTex, 0)
        GLES20.glGenFramebuffers(2, blurFbo, 0)
        uploadLook(ResolvedLook.NEUTRAL)
        GlProgram.checkError("init")
        isReady = true
    }

    private fun asset(path: String): String = assets.open(path).bufferedReader().use { it.readText() }

    private fun setLinearClamp(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun setInputSize(width: Int, height: Int) {
        if (width == inputW && height == inputH) return
        inputW = width
        inputH = height
        egl.makeCurrentPbuffer()
        blurW = max(1, width / 4)
        blurH = max(1, height / 4)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurTex[i])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, blurW, blurH, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            setLinearClamp(GLES20.GL_TEXTURE_2D)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFbo[i])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, blurTex[i], 0,
            )
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        updateBlurWeights()
        GlProgram.checkError("setInputSize")
    }

    fun uploadLook(newLook: ResolvedLook) {
        look = newLook
        if (uploadedCurve !== newLook.curve) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTex)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, ToneCurve.SIZE, 1, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, direct(newLook.curve.toRgbaTexture()),
            )
            uploadedCurve = newLook.curve
        }
        if (uploadedLut !== newLook.lut) {
            val lut = newLook.lut
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, lut.atlasWidth, lut.atlasHeight, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, direct(lut.toAtlasRgba()),
            )
            uploadedLut = lut
        }
        updateBlurWeights()
        GlProgram.checkError("uploadLook")
    }

    private var blurSpacing = 1f

    private fun updateBlurWeights() {
        if (inputW == 0) return
        val sigmaQ = max(0.35f, look.sigmaFrac * min(inputW, inputH) / 4f)
        blurSpacing = max(1f, sigmaQ / 2f)
        var sum = 0f
        for (k in 0..4) {
            val d = k * blurSpacing
            blurWeights[k] = exp(-(d * d) / (2f * sigmaQ * sigmaQ))
            sum += if (k == 0) blurWeights[k] else 2f * blurWeights[k]
        }
        for (k in 0..4) blurWeights[k] /= sum
    }

    fun registerOutputSurface(surface: Surface) {
        if (outputs.containsKey(surface)) return
        outputs[surface] = egl.createWindowSurface(surface)
    }

    fun unregisterOutputSurface(surface: Surface) {
        outputs.remove(surface)?.let {
            egl.makeCurrentPbuffer()
            egl.destroySurface(it)
        }
    }

    /** Calcula la luminancia desenfocada del frame actual (una vez por frame). */
    fun prepareFrame() {
        if (look.localContrast <= 0f || inputW == 0) return
        egl.makeCurrentPbuffer()
        // 1/4 de resolución, sólo luminancia.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFbo[0])
        GLES20.glViewport(0, 0, blurW, blurH)
        downProgram.use()
        downProgram.setMatrix("uTexMatrix", identity)
        downProgram.set2f("uTexel", 1f / inputW, 1f / inputH)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
        downProgram.set1i("uTex", 0)
        downProgram.drawQuad()

        blurProgram.use()
        blurProgram.setMatrix("uTexMatrix", identity)
        blurProgram.set1fv("uWeights", blurWeights)
        blurProgram.set1i("uTex", 0)
        // Horizontal: A -> B
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFbo[1])
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurTex[0])
        blurProgram.set2f("uStep", blurSpacing / blurW, 0f)
        blurProgram.drawQuad()
        // Vertical: B -> A
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFbo[0])
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurTex[1])
        blurProgram.set2f("uStep", 0f, blurSpacing / blurH)
        blurProgram.drawQuad()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /** Dibuja el frame actual en [surface] con su matriz de transformación. */
    fun render(surface: Surface, texMatrix: FloatArray, timestampNs: Long, bypass: Boolean) {
        val eglSurface = outputs[surface] ?: return
        if (!egl.makeCurrent(eglSurface)) return
        egl.querySize(eglSurface, size)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, size[0], size[1])

        val p = mainProgram
        p.use()
        p.setMatrix("uTexMatrix", texMatrix)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
        p.set1i("uTex", 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurTex[0])
        p.set1i("uBlur", 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTex)
        p.set1i("uCurve", 2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex)
        p.set1i("uLut", 3)

        val l = look
        p.set2f("uTexel", 1f / max(1, inputW), 1f / max(1, inputH))
        p.set3f("uGains", l.gains[0], l.gains[1], l.gains[2])
        p.set1f("uWb", if (l.wbNeutral) 0f else 1f)
        p.set1f("uSaturation", l.saturation)
        p.set1f("uVibrance", l.vibrance)
        p.set1f("uLocalContrast", if (inputW == 0) 0f else l.localContrast * LookProcessor.LC_GAIN)
        p.set1f("uSharpness", l.sharpness * LookProcessor.SH_GAIN)
        p.set1f("uLutSize", l.lut.size.toFloat())
        p.set1f("uLutMix", l.lutMix)
        p.set1f("uVignette", l.vignette)
        p.set1f("uBypass", if (bypass) 1f else 0f)
        p.drawQuad()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        egl.setPresentationTime(eglSurface, timestampNs)
        egl.swap(eglSurface)
    }

    fun release() {
        if (!::egl.isInitialized) return
        egl.makeCurrentPbuffer()
        for (s in outputs.values) egl.destroySurface(s)
        outputs.clear()
        if (isReady) {
            mainProgram.release(); downProgram.release(); blurProgram.release()
            GLES20.glDeleteTextures(3, intArrayOf(inputTextureId, curveTex, lutTex), 0)
            GLES20.glDeleteTextures(2, blurTex, 0)
            GLES20.glDeleteFramebuffers(2, blurFbo, 0)
        }
        isReady = false
        egl.release()
    }

    companion object {
        const val OES_DEFINE = "#define EXTERNAL_OES\n"

        private fun direct(bytes: ByteArray): ByteBuffer =
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes); position(0)
            }
    }
}
