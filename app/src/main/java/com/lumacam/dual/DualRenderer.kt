package com.lumacam.dual

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import com.lumacam.core.dual.DualGeometry
import com.lumacam.core.dual.DualLayout
import com.lumacam.core.dual.PipCorner
import com.lumacam.core.look.LookProcessor
import com.lumacam.core.look.Lut3D
import com.lumacam.core.look.ResolvedLook
import com.lumacam.core.look.ToneCurve
import com.lumacam.gl.EglCore
import com.lumacam.gl.GlProgram
import com.lumacam.gl.LookRenderer
import kotlinx.coroutines.CompletableDeferred
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Lienzo del modo DUAL: recibe la imagen de las dos cámaras (cada una en su SurfaceTexture), les
 * aplica el look y las compone (ventanita o mitades) en un lienzo vertical. El lienzo se copia a
 * la pantalla y, mientras se graba, a la superficie del codificador de video. Todo el trabajo de
 * GL ocurre en un hilo propio; las funciones públicas se pueden llamar desde cualquier hilo.
 */
class DualRenderer(private val assets: AssetManager) {
    private val thread = HandlerThread("LumaDualGL").apply { start() }
    private val handler = Handler(thread.looper)
    private val glExecutor = Executor { handler.post(it) }

    private var egl: EglCore? = null
    private lateinit var lookProgram: GlProgram
    private lateinit var maskProgram: GlProgram
    private var aQuad = -1
    private var curveTex = 0
    private var lutTex = 0
    private var blankTex = 0
    private val composite = Fbo()
    private val pip = Fbo()
    private val slots = arrayOf(Slot(), Slot())

    private var screen: EGLSurface? = null
    private var screenSurface: Surface? = null
    private var encoder: EGLSurface? = null
    private var encoderSurface: Surface? = null
    private val size = IntArray(2)

    private var canvasW = 1080
    private var canvasH = 1440
    private var look: ResolvedLook = ResolvedLook.NEUTRAL
    private var uploadedCurve: ToneCurve? = null
    private var uploadedLut: Lut3D? = null
    private var released = false
    private var renderPosted = false
    private var lastRender = 0L

    // Se cambian desde el hilo principal y se leen al dibujar.
    @Volatile var layout: DualLayout = DualLayout.PIP
    @Volatile var corner: PipCorner = PipCorner.TOP_LEFT
    /** Cámara a pantalla completa (0 = trasera, 1 = frontal). */
    @Volatile var mainSlot: Int = 0
    /** Sólo se ve una cámara (teléfonos que no permiten las dos a la vez). */
    @Volatile var single: Boolean = false

    val ready = CompletableDeferred<Boolean>()
    @Volatile var initError: String? = null
        private set
    @Volatile var framesRendered: Long = 0
        private set

    private val texBuffer: FloatBuffer = floatBuffer(8)
    private val vec = FloatArray(4)
    private val out = FloatArray(4)

    init {
        handler.post {
            try {
                val e = EglCore()
                egl = e
                val look = asset("shaders/look.frag")
                lookProgram = GlProgram(asset("shaders/dual.vert"), LookRenderer.OES_DEFINE + look, "dual_look")
                maskProgram = GlProgram(asset("shaders/blit.vert"), asset("shaders/mask.frag"), "dual_mask")
                aQuad = GLES20.glGetAttribLocation(lookProgram.id, "aQuad")
                val tex = IntArray(3)
                GLES20.glGenTextures(3, tex, 0)
                curveTex = tex[0]
                lutTex = tex[1]
                blankTex = tex[2]
                for (t in tex) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
                    linearClamp(GLES20.GL_TEXTURE_2D)
                }
                // Textura de 1x1 para uBlur (en DUAL no se usa contraste local).
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blankTex)
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, direct(byteArrayOf(0, 0, 0, -1)),
                )
                upload(ResolvedLook.NEUTRAL)
                GlProgram.checkError("dual init")
                ready.complete(true)
            } catch (t: Throwable) {
                Log.e(TAG, "No se pudo iniciar OpenGL para DUAL", t)
                initError = t.message ?: t.javaClass.simpleName
                ready.complete(false)
            }
        }
    }

    private fun asset(path: String): String = assets.open(path).bufferedReader().use { it.readText() }

    // ---------- Entradas (cámaras) ----------

    /** SurfaceProvider para la vista previa de la cámara [slot] (0 = trasera, 1 = frontal). */
    fun surfaceProvider(slot: Int): Preview.SurfaceProvider = Preview.SurfaceProvider { request ->
        handler.post { provide(slot, request) }
    }

    private fun provide(slotIndex: Int, request: SurfaceRequest) {
        val e = egl
        if (e == null || released) {
            request.willNotProvideSurface()
            return
        }
        e.makeCurrentPbuffer()
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        linearClamp(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        val st = SurfaceTexture(ids[0])
        val res = request.resolution
        st.setDefaultBufferSize(res.width, res.height)
        val stream = Stream(ids[0], st, Surface(st), res.width, res.height)
        st.setOnFrameAvailableListener({
            stream.frameAvailable = true
            scheduleRender()
        }, handler)
        request.setTransformationInfoListener(glExecutor) { info ->
            stream.turn = info.rotationDegrees
            // Conectada directo a la cámara: la matriz del SurfaceTexture ya trae el giro (y el
            // espejo de la frontal). Si no, hay que aplicarlos a mano.
            if (info.hasCameraTransform()) {
                stream.rotation = 0
                stream.mirror = false
            } else {
                stream.rotation = info.rotationDegrees
                stream.mirror = info.isMirroring
            }
        }
        val slot = slots[slotIndex]
        slot.incoming?.let { retire(it) }
        slot.incoming = stream
        request.provideSurface(stream.surface, glExecutor) {
            stream.producerDone = true
            if (stream.superseded) destroy(stream)
        }
    }

    private fun retire(s: Stream) {
        s.superseded = true
        if (s.producerDone) destroy(s)
    }

    private fun destroy(s: Stream) {
        if (s.destroyed) return
        s.destroyed = true
        egl?.makeCurrentPbuffer()
        s.surface.release()
        s.st.release()
        GLES20.glDeleteTextures(1, intArrayOf(s.texId), 0)
    }

    /** ¿Hay imagen de la cámara [slot]? */
    fun hasFrame(slot: Int): Boolean = slots[slot].current?.hasFrame == true

    /** Momento (uptime) en que llegó la primera imagen de la última conexión de [slot]. */
    fun firstFrameAt(slot: Int): Long = slots[slot].firstFrameAt

    // ---------- Salidas ----------

    /** Superficie de la pantalla (null al destruirse). Espera a que el hilo GL la tome o la suelte. */
    fun setScreenSurface(surface: Surface?) = runSync {
        if (surface === screenSurface) return@runSync
        val e = egl ?: return@runSync
        screen?.let { e.makeCurrentPbuffer(); e.destroySurface(it) }
        screen = null
        screenSurface = surface
        if (surface != null && surface.isValid) {
            screen = runCatching { e.createWindowSurface(surface) }.getOrNull()
            scheduleRender()
        }
    }

    /** Superficie del codificador de video mientras se graba (null = no grabar). */
    fun setEncoderSurface(surface: Surface?) = runSync {
        if (surface === encoderSurface) return@runSync
        val e = egl ?: return@runSync
        encoder?.let { e.makeCurrentPbuffer(); e.destroySurface(it) }
        encoder = null
        encoderSurface = surface
        if (surface != null) encoder = runCatching { e.createWindowSurface(surface) }.getOrNull()
    }

    fun setCanvas(width: Int, height: Int) {
        handler.post {
            canvasW = width
            canvasH = height
            scheduleRender()
        }
    }

    fun setLook(newLook: ResolvedLook) {
        handler.post {
            if (egl != null && !released) upload(newLook)
            scheduleRender()
        }
    }

    /** Pide redibujar (p. ej. al cambiar la distribución). */
    fun invalidate() {
        handler.post { scheduleRender() }
    }

    /** Copia del lienzo actual (respaldo para la foto si no hay captura de alta resolución). */
    fun capture(callback: (Bitmap?) -> Unit) {
        handler.post {
            val bmp = try {
                if (egl == null || released || slots.none { it.current?.hasFrame == true }) null else readComposite()
            } catch (t: Throwable) {
                Log.w(TAG, "No se pudo copiar el lienzo", t)
                null
            }
            callback(bmp)
        }
    }

    fun release() {
        handler.post {
            released = true
            val e = egl
            if (e != null) {
                e.makeCurrentPbuffer()
                screen?.let { e.destroySurface(it) }
                encoder?.let { e.destroySurface(it) }
                screen = null
                encoder = null
                for (slot in slots) {
                    slot.current?.let { destroy(it) }
                    slot.incoming?.let { destroy(it) }
                    slot.current = null
                    slot.incoming = null
                }
                composite.release()
                pip.release()
                if (ready.isCompleted && initError == null) {
                    lookProgram.release()
                    maskProgram.release()
                    GLES20.glDeleteTextures(3, intArrayOf(curveTex, lutTex, blankTex), 0)
                }
                e.release()
                egl = null
            }
            thread.quitSafely()
        }
    }

    // ---------- Dibujo ----------

    private fun scheduleRender() {
        if (renderPosted || released) return
        renderPosted = true
        val wait = MIN_FRAME_MS - (SystemClock.uptimeMillis() - lastRender)
        if (wait > 0) handler.postDelayed(renderTask, wait) else handler.post(renderTask)
    }

    private val renderTask = Runnable {
        renderPosted = false
        lastRender = SystemClock.uptimeMillis()
        try {
            drawFrame()
        } catch (t: Throwable) {
            Log.w(TAG, "Error dibujando DUAL", t)
        }
    }

    private fun drawFrame() {
        val e = egl ?: return
        if (released) return
        e.makeCurrentPbuffer()
        for (slot in slots) {
            for (s in listOfNotNull(slot.current, slot.incoming)) {
                if (!s.frameAvailable || s.destroyed) continue
                s.frameAvailable = false
                try {
                    s.st.updateTexImage()
                    s.st.getTransformMatrix(s.matrix)
                    s.hasFrame = true
                } catch (t: Throwable) {
                    Log.w(TAG, "updateTexImage falló", t)
                }
            }
            // La conexión nueva sustituye a la anterior en cuanto trae imagen.
            val inc = slot.incoming
            if (inc != null && inc.hasFrame) {
                slot.current?.let { retire(it) }
                slot.current = inc
                slot.incoming = null
                slot.firstFrameAt = SystemClock.uptimeMillis()
            }
        }
        if (slots.none { it.current?.hasFrame == true }) return
        drawComposite()
        screen?.let { blitTo(e, it) }
        encoder?.let {
            blitTo(e, it, presentationNs = System.nanoTime())
        }
        framesRendered++
    }

    private fun drawComposite() {
        composite.ensure(canvasW, canvasH)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, composite.fbo)
        GLES20.glViewport(0, 0, canvasW, canvasH)
        GLES20.glClearColor(0.04f, 0.045f, 0.05f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val main = mainSlot.coerceIn(0, 1)
        val regions = DualGeometry.regions(layout, canvasW, canvasH, corner, single)
        slots[main].current?.takeIf { it.hasFrame }?.let { drawStream(it, regions.main.toPixels(canvasW, canvasH), canvasH) }

        val box = regions.second ?: return
        val other = slots[1 - main].current?.takeIf { it.hasFrame }
        val px = box.toPixels(canvasW, canvasH)
        if (layout == DualLayout.SPLIT) {
            if (other != null) drawStream(other, px, canvasH) else clearRect(px, canvasH)
            return
        }
        // Ventanita: se dibuja en su propia textura y se pega con esquinas redondeadas.
        pip.ensure(px[2], px[3])
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, pip.fbo)
        GLES20.glViewport(0, 0, px[2], px[3])
        GLES20.glClearColor(0.12f, 0.13f, 0.14f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (other != null) drawStream(other, intArrayOf(0, 0, px[2], px[3]), px[3])

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, composite.fbo)
        GLES20.glViewport(px[0], canvasH - px[1] - px[3], px[2], px[3])
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        drawTexture(pip.tex, px[2].toFloat(), px[3].toFloat(), regions.radius * canvasW, regions.border * canvasW)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun clearRect(px: IntArray, targetH: Int) {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(px[0], targetH - px[1] - px[3], px[2], px[3])
        GLES20.glClearColor(0.12f, 0.13f, 0.14f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    /** Dibuja una cámara con el look en el rectángulo [px] (izq, arriba, ancho, alto) del destino. */
    private fun drawStream(s: Stream, px: IntArray, targetH: Int) {
        GLES20.glViewport(px[0], targetH - px[1] - px[3], px[2], px[3])
        val regionAspect = px[2].toFloat() / px[3]
        val imageAspect = DualGeometry.uprightAspect(s.bufW, s.bufH, s.turn)
        val st = DualGeometry.texCoords(regionAspect, imageAspect, s.rotation, s.mirror)
        texBuffer.position(0)
        for (i in 0 until 4) {
            vec[0] = st[i * 2]
            vec[1] = st[i * 2 + 1]
            vec[2] = 0f
            vec[3] = 1f
            Matrix.multiplyMV(out, 0, s.matrix, 0, vec, 0)
            texBuffer.put(out[0])
            texBuffer.put(out[1])
        }
        texBuffer.position(0)

        val p = lookProgram
        p.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, s.texId)
        p.set1i("uTex", 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blankTex)
        p.set1i("uBlur", 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTex)
        p.set1i("uCurve", 2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex)
        p.set1i("uLut", 3)
        val l = look
        p.set2f("uTexel", 1f / maxOf(1, s.bufW), 1f / maxOf(1, s.bufH))
        p.set3f("uGains", l.gains[0], l.gains[1], l.gains[2])
        p.set1f("uWb", if (l.wbNeutral) 0f else 1f)
        p.set1f("uSaturation", l.saturation)
        p.set1f("uVibrance", l.vibrance)
        p.set1f("uLocalContrast", 0f)
        p.set1f("uSharpness", l.sharpness * LookProcessor.SH_GAIN)
        p.set1f("uLutSize", l.lut.size.toFloat())
        p.set1f("uLutMix", l.lutMix)
        p.set1f("uVignette", l.vignette)
        p.set1f("uBypass", 0f)

        GLES20.glEnableVertexAttribArray(p.aPosition)
        GLES20.glVertexAttribPointer(p.aPosition, 2, GLES20.GL_FLOAT, false, 0, QUAD_POS)
        GLES20.glEnableVertexAttribArray(p.aTexCoord)
        GLES20.glVertexAttribPointer(p.aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
        if (aQuad >= 0) {
            GLES20.glEnableVertexAttribArray(aQuad)
            GLES20.glVertexAttribPointer(aQuad, 2, GLES20.GL_FLOAT, false, 0, QUAD_TEX)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(p.aPosition)
        GLES20.glDisableVertexAttribArray(p.aTexCoord)
        if (aQuad >= 0) GLES20.glDisableVertexAttribArray(aQuad)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun drawTexture(tex: Int, w: Float, h: Float, radius: Float, border: Float) {
        val m = maskProgram
        m.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        m.set1i("uTex", 0)
        m.set2f("uSize", w, h)
        m.set1f("uRadius", radius)
        m.set1f("uBorder", border)
        m.set3f("uBorderColor", 0.02f, 0.02f, 0.02f)
        m.drawQuad()
    }

    private fun blitTo(e: EglCore, target: EGLSurface, presentationNs: Long? = null) {
        if (!e.makeCurrent(target)) return
        e.querySize(target, size)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, size[0], size[1])
        drawTexture(composite.tex, size[0].toFloat(), size[1].toFloat(), 0f, 0f)
        if (presentationNs != null) e.setPresentationTime(target, presentationNs)
        e.swap(target)
        e.makeCurrentPbuffer()
    }

    private fun readComposite(): Bitmap {
        egl?.makeCurrentPbuffer()
        drawComposite()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, composite.fbo)
        val w = composite.w
        val h = composite.h
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        buf.rewind()
        val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buf)
        // OpenGL lee de abajo hacia arriba.
        val flip = android.graphics.Matrix().apply { preScale(1f, -1f) }
        val upright = Bitmap.createBitmap(raw, 0, 0, w, h, flip, false)
        raw.recycle()
        return upright
    }

    private fun upload(newLook: ResolvedLook) {
        egl?.makeCurrentPbuffer()
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
    }

    private fun runSync(block: () -> Unit) {
        if (Thread.currentThread() === thread) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        val posted = handler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        if (posted) latch.await(1500, TimeUnit.MILLISECONDS)
    }

    private class Stream(val texId: Int, val st: SurfaceTexture, val surface: Surface, val bufW: Int, val bufH: Int) {
        /** Giro del sensor (para saber si la imagen derecha es vertical). */
        var turn = 0
        var rotation = 0
        var mirror = false
        var frameAvailable = false
        @Volatile var hasFrame = false
        var producerDone = false
        var superseded = false
        var destroyed = false
        val matrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    }

    private class Slot {
        @Volatile var current: Stream? = null
        var incoming: Stream? = null
        @Volatile var firstFrameAt = 0L
    }

    private class Fbo {
        var fbo = 0
        var tex = 0
        var w = 0
        var h = 0

        fun ensure(width: Int, height: Int) {
            if (fbo != 0 && width == w && height == h) return
            if (fbo == 0) {
                val a = IntArray(1)
                GLES20.glGenFramebuffers(1, a, 0)
                fbo = a[0]
                GLES20.glGenTextures(1, a, 0)
                tex = a[0]
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            linearClamp(GLES20.GL_TEXTURE_2D)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0,
            )
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            w = width
            h = height
        }

        fun release() {
            if (fbo != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
            }
            fbo = 0
            tex = 0
            w = 0
            h = 0
        }
    }

    companion object {
        private const val TAG = "DualRenderer"
        /** Como mucho ~30 cuadros por segundo aunque lleguen imágenes de dos cámaras. */
        private const val MIN_FRAME_MS = 30L

        private val QUAD_POS: FloatBuffer = floatBuffer(8).apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
        private val QUAD_TEX: FloatBuffer = floatBuffer(8).apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }

        private fun floatBuffer(n: Int): FloatBuffer =
            ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        private fun direct(bytes: ByteArray): ByteBuffer =
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes); position(0)
            }

        private fun linearClamp(target: Int) {
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
    }
}
