package com.lumacam.gl

import android.content.res.AssetManager
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import com.lumacam.core.look.ResolvedLook
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Procesador de superficies de CameraX: recibe los frames de la cámara en una textura OES,
 * aplica el look con OpenGL ES y los dibuja en la vista previa y/o en el codificador de video.
 */
class LookSurfaceProcessor(assets: AssetManager) : SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {

    private val thread = HandlerThread("LumaGL").also { it.start() }
    private val handler = Handler(thread.looper)
    val executor: Executor = Executor { r ->
        if (!handler.post(r)) Log.w(TAG, "Hilo GL terminado; se descarta tarea")
    }

    private val renderer = LookRenderer(assets)
    private val outputs = LinkedHashMap<SurfaceOutput, Surface>()
    private var inputTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)
    private val outMatrix = FloatArray(16)
    private var released = false

    @Volatile
    private var pendingLook: ResolvedLook? = null

    /** Mostrar la imagen original (mantener pulsado para comparar). */
    @Volatile
    var bypass: Boolean = false

    /** true si OpenGL arrancó bien; false si hay que usar la vista previa sin efectos. */
    val ready = CompletableDeferred<Boolean>()
    var initError: String? = null
        private set
    val glesVersion: Int get() = renderer.glesVersion

    private val frameCounter = AtomicInteger()
    private val totalFrames = java.util.concurrent.atomic.AtomicLong()

    /** Frames dibujados desde que se creó el procesador. */
    val framesRendered: Long get() = totalFrames.get()

    init {
        handler.post {
            try {
                renderer.init()
                ready.complete(true)
            } catch (t: Throwable) {
                Log.e(TAG, "No se pudo iniciar OpenGL", t)
                initError = t.message ?: t.javaClass.simpleName
                ready.complete(false)
            }
        }
    }

    fun setLook(look: ResolvedLook) {
        pendingLook = look
    }

    /** Frames dibujados desde la última llamada (para mostrar FPS). */
    fun takeFrameCount(): Int = frameCounter.getAndSet(0)

    override fun onInputSurface(request: SurfaceRequest) {
        if (released || !renderer.isReady) {
            request.willNotProvideSurface()
            return
        }
        val res = request.resolution
        val st = SurfaceTexture(renderer.inputTextureId)
        st.setDefaultBufferSize(res.width, res.height)
        val surface = Surface(st)
        renderer.setInputSize(res.width, res.height)
        request.provideSurface(surface, executor) {
            st.setOnFrameAvailableListener(null)
            st.release()
            surface.release()
            if (inputTexture === st) inputTexture = null
        }
        st.setOnFrameAvailableListener(this, handler)
        inputTexture = st
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (released) {
            surfaceOutput.close()
            return
        }
        val surface = surfaceOutput.getSurface(executor) {
            // EVENT_REQUEST_CLOSE: CameraX ya no usará esta salida.
            surfaceOutput.close()
            outputs.remove(surfaceOutput)?.let { s -> renderer.unregisterOutputSurface(s) }
        }
        try {
            renderer.registerOutputSurface(surface)
            outputs[surfaceOutput] = surface
        } catch (t: Throwable) {
            Log.e(TAG, "No se pudo crear la superficie de salida", t)
            surfaceOutput.close()
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        if (released || surfaceTexture !== inputTexture) return
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(texMatrix)
            pendingLook?.let {
                renderer.uploadLook(it)
                pendingLook = null
            }
            if (outputs.isEmpty()) return
            renderer.prepareFrame()
            val ts = surfaceTexture.timestamp
            val bp = bypass
            for ((output, surface) in outputs) {
                output.updateTransformMatrix(outMatrix, texMatrix)
                renderer.render(surface, outMatrix, ts, bp)
            }
            frameCounter.incrementAndGet()
            totalFrames.incrementAndGet()
        } catch (t: Throwable) {
            Log.e(TAG, "Error dibujando frame", t)
        }
    }

    fun release() {
        handler.post {
            if (released) return@post
            released = true
            for (o in outputs.keys) o.close()
            outputs.clear()
            inputTexture?.setOnFrameAvailableListener(null)
            renderer.release()
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "LookProcessor"
    }
}

/** Efecto de CameraX que conecta [LookSurfaceProcessor] con la vista previa y/o el video. */
class LookEffect(processor: LookSurfaceProcessor, targets: Int) :
    CameraEffect(targets, processor.executor, processor, { t -> Log.e("LookEffect", "Error del efecto", t) })
