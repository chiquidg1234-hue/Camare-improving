package com.lumacam.dual

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.lumacam.camera.CameraSession
import com.lumacam.core.dual.DualLayout
import com.lumacam.core.dual.PipCorner
import com.lumacam.core.look.ResolvedLook
import com.lumacam.photo.PhotoPipeline
import com.lumacam.photo.PhotoResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DualBindResult(
    /** Las dos cámaras a la vez; si no, una por turno. */
    val concurrent: Boolean,
    val warnings: List<String>,
)

/**
 * Modo DUAL (trasera + frontal, estilo BeReal). Si el teléfono permite usar las dos cámaras a la
 * vez (Android "concurrent camera"), las dos alimentan el lienzo de [DualRenderer]. Si no, se usa
 * una por turno: la foto doble se hace en dos pasos (primero una cámara y enseguida la otra) y el
 * video graba la cámara activa, con cambio de cámara en plena grabación.
 *
 * Slot 0 = cámara trasera, slot 1 = frontal. Llamar desde el hilo principal.
 */
class DualController(private val context: Context, private val session: CameraSession) {
    val renderer = DualRenderer(context.assets)
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private val captureExecutor = Executors.newSingleThreadExecutor()

    private var owner: LifecycleOwner? = null
    private val cameraIds = arrayOf("", "")
    private var video = false
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private val captures = arrayOfNulls<ImageCapture>(2)
    private val cameras = arrayOfNulls<Camera>(2)
    private var recorder: DualRecorder? = null

    var concurrent = false
        private set
    /** En el modo por turnos, la cámara que está activa. */
    var liveSlot = 0
        private set

    val isRecording: Boolean get() = recorder != null

    suspend fun bind(
        owner: LifecycleOwner,
        backId: String,
        frontId: String,
        video: Boolean,
        flashMode: Int,
        mainSlot: Int,
    ): DualBindResult {
        this.owner = owner
        cameraIds[0] = backId
        cameraIds[1] = frontId
        this.video = video
        this.flashMode = flashMode
        val provider = session.provider()
        session.unbind()
        provider.unbindAll()
        clearBound()
        if (video) renderer.setCanvas(1080, 1920) else renderer.setCanvas(1080, 1440)
        renderer.mainSlot = mainSlot

        val pair = concurrentPair()
        if (pair != null) {
            // Foto: vista previa + captura en cada cámara; si no cabe, sólo vista previa.
            val attempts = if (video) listOf(false) else listOf(true, false)
            for (withCapture in attempts) {
                try {
                    val configs = (0..1).map { slot ->
                        ConcurrentCamera.SingleCameraConfig(pair[slot].cameraSelector, group(slot, withCapture), owner)
                    }
                    val cc = provider.bindToLifecycle(configs)
                    cc.cameras.forEachIndexed { i, cam -> if (i < 2) cameras[i] = cam }
                    concurrent = true
                    renderer.single = false
                    renderer.invalidate()
                    val warnings = if (!video && !withCapture) {
                        listOf("La foto doble usa la resolución de la vista previa (el teléfono no permite fotos de alta resolución con las dos cámaras a la vez).")
                    } else {
                        emptyList()
                    }
                    return DualBindResult(true, warnings)
                } catch (t: Throwable) {
                    Log.w(TAG, "No se pudieron abrir las dos cámaras (captura=$withCapture)", t)
                    provider.unbindAll()
                    clearBound()
                }
            }
        }
        concurrent = false
        renderer.single = true
        bindSingle(mainSlot)
        return DualBindResult(
            false,
            listOf(
                if (video) {
                    "Este teléfono no deja usar las dos cámaras a la vez: el video graba una cámara y puedes cambiar de cámara mientras grabas."
                } else {
                    "Este teléfono no deja usar las dos cámaras a la vez: la foto doble se hace en dos pasos seguidos, como BeReal."
                },
            ),
        )
    }

    /** Pareja trasera + frontal que el teléfono puede usar a la vez, o null. */
    @OptIn(ExperimentalCamera2Interop::class)
    private suspend fun concurrentPair(): List<CameraInfo>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)) return null
        val combos = try {
            session.provider().availableConcurrentCameraInfos
        } catch (t: Throwable) {
            Log.w(TAG, "Sin información de cámaras simultáneas", t)
            return null
        }
        val candidates = combos.mapNotNull { combo ->
            val back = combo.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            val front = combo.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
            if (back != null && front != null) listOf(back, front) else null
        }
        // Mejor la cámara trasera principal, si está en alguna pareja.
        return candidates.firstOrNull { runCatching { Camera2CameraInfo.from(it[0]).cameraId }.getOrNull() == cameraIds[0] }
            ?: candidates.firstOrNull()
    }

    private fun group(slot: Int, withCapture: Boolean): UseCaseGroup {
        val aspect = if (video) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
        val preview = Preview.Builder()
            .setTargetRotation(Surface.ROTATION_0)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(aspect, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .build(),
            )
            .build()
        preview.setSurfaceProvider(mainExecutor, renderer.surfaceProvider(slot))
        val builder = UseCaseGroup.Builder().addUseCase(preview)
        if (withCapture) {
            val ic = ImageCapture.Builder()
                .setTargetRotation(Surface.ROTATION_0)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(95)
                .setFlashMode(if (slot == 0) flashMode else ImageCapture.FLASH_MODE_OFF)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build(),
                )
                .build()
            captures[slot] = ic
            builder.addUseCase(ic)
        }
        return builder.build()
    }

    /** Modo por turnos: abre sólo la cámara [slot]. */
    private suspend fun bindSingle(slot: Int) {
        val o = owner ?: return
        val provider = session.provider()
        provider.unbindAll()
        clearBound()
        liveSlot = slot
        renderer.mainSlot = slot
        cameras[slot] = provider.bindToLifecycle(o, session.selectorFor(cameraIds[slot]), group(slot, withCapture = !video))
        renderer.invalidate()
    }

    private fun clearBound() {
        captures.fill(null)
        cameras.fill(null)
    }

    /**
     * Botón de cambiar: con las dos cámaras a la vez intercambia cuál va grande; por turnos,
     * abre la otra cámara (también durante la grabación). Devuelve la cámara principal.
     */
    suspend fun swap(): Int {
        if (concurrent) {
            renderer.mainSlot = 1 - renderer.mainSlot
            renderer.invalidate()
        } else {
            bindSingle(1 - liveSlot)
        }
        return renderer.mainSlot
    }

    fun setLayout(layout: DualLayout, corner: PipCorner) {
        renderer.layout = layout
        renderer.corner = corner
        renderer.invalidate()
    }

    fun setFlashMode(mode: Int) {
        flashMode = mode
        captures[0]?.flashMode = mode
    }

    /** Zoom de la cámara principal (pellizco). */
    fun pinchZoom(scale: Float) {
        val cam = cameras[renderer.mainSlot] ?: return
        val zs = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio((zs.zoomRatio * scale).coerceIn(zs.minZoomRatio, zs.maxZoomRatio))
    }

    // ---------- Foto ----------

    /**
     * Foto doble: las dos cámaras compuestas como en la vista previa, con el look. [progress]
     * recibe textos para el usuario.
     */
    suspend fun takePhoto(
        look: ResolvedLook,
        jpegQuality: Int,
        layout: DualLayout,
        corner: PipCorner,
        onShutter: () -> Unit,
        progress: (String, Float) -> Unit,
    ): PhotoResult {
        val main = renderer.mainSlot
        val shots = arrayOfNulls<DualShot>(2)
        var sourceJpeg: ByteArray? = null
        try {
            if (concurrent) {
                val c0 = captures[0]
                val c1 = captures[1]
                if (c0 == null || c1 == null) return saveCanvas(jpegQuality, onShutter, progress)
                progress("Capturando las dos cámaras…", 0.1f)
                val images = coroutineScope {
                    val a = async { captureImage(c0) }
                    val b = async { captureImage(c1) }
                    listOf(a.await(), b.await())
                }
                onShutter()
                for (slot in 0..1) {
                    val frame = withContext(Dispatchers.Default) { images[slot].use { decode(it) } }
                    shots[slot] = DualShot(frame.bitmap, frame.rotationDegrees, mirror = slot == 1)
                    if (slot == main) sourceJpeg = frame.jpegBytes
                }
            } else {
                // Por turnos: primero la cámara activa y enseguida la otra (como BeReal).
                val first = liveSlot
                val second = 1 - first
                progress("Capturando…", 0.1f)
                val a = captureImage(captures[first] ?: throw IllegalStateException("La cámara no está lista"))
                onShutter()
                val fa = withContext(Dispatchers.Default) { a.use { decode(it) } }
                shots[first] = DualShot(fa.bitmap, fa.rotationDegrees, mirror = first == 1)
                if (first == main) sourceJpeg = fa.jpegBytes
                progress(if (second == 1) "Ahora la cámara frontal… ¡sonríe!" else "Ahora la cámara trasera…", 0.35f)
                bindSingle(second)
                waitForSettledFrame(second)
                val b = captureImage(captures[second] ?: throw IllegalStateException("La otra cámara no está lista"))
                onShutter()
                val fb = withContext(Dispatchers.Default) { b.use { decode(it) } }
                shots[second] = DualShot(fb.bitmap, fb.rotationDegrees, mirror = second == 1)
                if (second == main) sourceJpeg = fb.jpegBytes
                // Volver a la cámara de antes mientras se procesa.
                bindSingle(first)
            }
            progress("Componiendo y aplicando el look…", 0.6f)
            val mainShot = shots[main] ?: throw IllegalStateException("Falta la foto principal")
            val other = shots[1 - main]
            val composed = DualPhoto.compose(mainShot, other, layout, corner, look)
            shots.forEach { it?.bitmap?.recycle() }
            progress("Guardando…", 0.9f)
            val summary = listOfNotNull("DUAL", look.describe()).joinToString(" · ")
            val result = PhotoPipeline.saveFinished(context, composed, jpegQuality, sourceJpeg, summary)
            composed.recycle()
            return result
        } catch (t: Throwable) {
            shots.forEach { it?.bitmap?.recycle() }
            if (!concurrent && liveSlot != renderer.mainSlot) runCatching { bindSingle(renderer.mainSlot) }
            throw t
        }
    }

    /** Respaldo: guarda el lienzo tal como se ve (resolución de la vista previa). */
    private suspend fun saveCanvas(jpegQuality: Int, onShutter: () -> Unit, progress: (String, Float) -> Unit): PhotoResult {
        progress("Capturando…", 0.2f)
        val bmp: Bitmap = suspendCancellableCoroutine { cont ->
            renderer.capture { b -> if (cont.isActive) cont.resume(b) else b?.recycle() }
        } ?: throw IllegalStateException("Aún no hay imagen de las cámaras")
        onShutter()
        progress("Guardando…", 0.8f)
        val result = PhotoPipeline.saveFinished(context, bmp, jpegQuality, null, "DUAL")
        bmp.recycle()
        return result
    }

    /** Espera la primera imagen de [slot] y un momento para que se ajusten exposición y enfoque. */
    private suspend fun waitForSettledFrame(slot: Int) {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < 4000) {
            val first = renderer.firstFrameAt(slot)
            if (renderer.hasFrame(slot) && first >= start && SystemClock.uptimeMillis() - first >= SETTLE_MS) return
            delay(50)
        }
    }

    private fun decode(image: ImageProxy) = PhotoPipeline.decode(image, sampleSizeFor(image.width, image.height), keepJpeg = true)

    private fun sampleSizeFor(w: Int, h: Int): Int {
        var s = 1
        while (maxOf(w, h) / s > 4096) s *= 2
        return s
    }

    private suspend fun captureImage(ic: ImageCapture): ImageProxy = suspendCancellableCoroutine { cont ->
        ic.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                if (cont.isActive) cont.resume(image) else image.close()
            }

            override fun onError(exception: ImageCaptureException) {
                if (cont.isActive) cont.resumeWithException(exception)
            }
        })
    }

    // ---------- Video ----------

    /** Empieza a grabar el lienzo. */
    fun startRecording(audio: Boolean) {
        check(recorder == null) { "Ya se está grabando" }
        val r = DualRecorder(context)
        val surface = r.start(audio)
        renderer.setEncoderSurface(surface)
        recorder = r
    }

    /** Termina la grabación y devuelve el video guardado (o null si no se grabó nada). */
    fun stopRecording(): Uri? {
        val r = recorder ?: return null
        recorder = null
        renderer.setEncoderSurface(null)
        return r.stop()
    }

    fun unbind() {
        stopRecording()
        session.unbind()
        clearBound()
    }

    fun release() {
        stopRecording()
        renderer.release()
        captureExecutor.shutdown()
    }

    companion object {
        private const val TAG = "DualController"
        /** Tiempo para que la otra cámara ajuste exposición y enfoque antes de la segunda foto. */
        private const val SETTLE_MS = 700L
    }
}
