package com.lumacam.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.lumacam.gl.LookEffect
import com.lumacam.gl.LookSurfaceProcessor
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class CaptureMode { PHOTO, VIDEO }
enum class ResolutionMode { STANDARD, MAXIMUM }
enum class VideoQualityOption(val label: String) { FHD("1080p"), UHD("4K") }

/** Modos de procesado del fabricante (extensiones de CameraX), si el teléfono los ofrece. */
enum class VendorMode(val label: String, val extensionMode: Int) {
    NONE("Ninguno", ExtensionMode.NONE),
    HDR("HDR", ExtensionMode.HDR),
    NIGHT("Noche", ExtensionMode.NIGHT),
    AUTO("Auto", ExtensionMode.AUTO),
}

enum class StabilizationState { OFF, PREVIEW_AND_VIDEO, VIDEO_ONLY, UNSUPPORTED }

data class BindRequest(
    val cameraId: String,
    val mode: CaptureMode,
    val resolutionMode: ResolutionMode,
    val videoQuality: VideoQualityOption,
    val stabilization: Boolean,
    val vendorMode: VendorMode,
    val useEffect: Boolean,
    val fastBurst: Boolean,
    val flashMode: Int,
    val targetRotation: Int,
)

data class BindResult(
    val effectActive: Boolean,
    val vendorMode: VendorMode,
    val stabilization: StabilizationState,
    val photoResolution: Size?,
    val previewResolution: Size?,
    val warnings: List<String>,
)

/** Opciones de Camera2 que se aplican sobre las de CameraX. */
data class CaptureOptions(
    val aeLock: Boolean = false,
    val awbLock: Boolean = false,
    val sceneHdr: Boolean = false,
    val fpsRange: Range<Int>? = null,
    val ois: Boolean = false,
)

/**
 * Envuelve CameraX: enlaza los casos de uso (vista previa, foto, video), el efecto OpenGL y los
 * controles. Todas las funciones públicas deben llamarse desde el hilo principal.
 */
class CameraSession(private val context: Context) {

    val processor = LookSurfaceProcessor(context.assets)
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    private var provider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var extensionsTried = false

    var camera: Camera? = null
        private set
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var recording: Recording? = null

    val cameraInfo: CameraInfo? get() = camera?.cameraInfo

    suspend fun provider(): ProcessCameraProvider =
        provider ?: ProcessCameraProvider.getInstance(context).await().also { provider = it }

    private suspend fun extensions(): ExtensionsManager? {
        if (extensionsTried) return extensionsManager
        val em = try {
            // Algunas librerías de fabricante tardan en cargar: no bloquear la cámara por eso.
            kotlinx.coroutines.withTimeoutOrNull(3000) {
                ExtensionsManager.getInstanceAsync(context, provider()).await()
            }
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.w(TAG, "Extensiones de CameraX no disponibles", t)
            null
        }
        extensionsManager = em
        extensionsTried = true
        return em
    }

    /** Modos del fabricante disponibles para esta cámara. */
    suspend fun availableVendorModes(cameraId: String): List<VendorMode> {
        val em = extensions() ?: return emptyList()
        val selector = selectorFor(cameraId)
        return VendorMode.entries.filter { it != VendorMode.NONE }.filter {
            try {
                em.isExtensionAvailable(selector, it.extensionMode)
            } catch (_: Throwable) {
                false
            }
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun selectorFor(cameraId: String): CameraSelector = CameraSelector.Builder()
        .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId } }
        .build()

    /** Ids de cámara que CameraX puede usar (filtra las que no son compatibles). */
    @OptIn(ExperimentalCamera2Interop::class)
    suspend fun usableCameraIds(): Set<String> =
        provider().availableCameraInfos.map { Camera2CameraInfo.from(it).cameraId }.toSet()

    @OptIn(ExperimentalCamera2Interop::class)
    suspend fun cameraInfoFor(cameraId: String): CameraInfo =
        provider().availableCameraInfos.firstOrNull { Camera2CameraInfo.from(it).cameraId == cameraId }
            ?: throw IllegalArgumentException("Cámara $cameraId no disponible")

    suspend fun bind(owner: LifecycleOwner, req: BindRequest, surfaceProvider: Preview.SurfaceProvider): BindResult {
        val provider = provider()
        stopRecording()
        provider.unbindAll()
        camera = null

        val warnings = ArrayList<String>()
        val baseSelector = selectorFor(req.cameraId)
        val info = cameraInfoFor(req.cameraId)

        var selector = baseSelector
        var vendor = VendorMode.NONE
        if (req.mode == CaptureMode.PHOTO && req.vendorMode != VendorMode.NONE) {
            val em = extensions()
            if (em != null && em.isExtensionAvailable(baseSelector, req.vendorMode.extensionMode)) {
                selector = em.getExtensionEnabledCameraSelector(baseSelector, req.vendorMode.extensionMode)
                vendor = req.vendorMode
            } else {
                warnings += "El modo ${req.vendorMode.label} del fabricante no está disponible."
            }
        }

        // Estabilización (sólo en video).
        var stab = StabilizationState.OFF
        val previewStab = runCatching { Preview.getPreviewCapabilities(info).isStabilizationSupported }.getOrDefault(false)
        val videoStab = runCatching { Recorder.getVideoCapabilities(info).isStabilizationSupported }.getOrDefault(false)
        if (req.mode == CaptureMode.VIDEO && req.stabilization) {
            stab = when {
                previewStab -> StabilizationState.PREVIEW_AND_VIDEO
                videoStab -> StabilizationState.VIDEO_ONLY
                else -> StabilizationState.UNSUPPORTED
            }
            if (stab == StabilizationState.UNSUPPORTED) {
                warnings += "Este teléfono no ofrece estabilización de video a otras apps."
            }
        }

        val targets = if (req.mode == CaptureMode.PHOTO) CameraEffect.PREVIEW
        else CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE

        // Intentos en orden de preferencia: modo del fabricante > look en vista previa/video >
        // estabilización. Si una combinación no es compatible se prueba la siguiente.
        val vendors = if (vendor != VendorMode.NONE) listOf(vendor, VendorMode.NONE) else listOf(VendorMode.NONE)
        val effects = if (req.useEffect) listOf(true, false) else listOf(false)
        val stabs = if (stab == StabilizationState.PREVIEW_AND_VIDEO || stab == StabilizationState.VIDEO_ONLY) {
            listOf(stab, StabilizationState.OFF)
        } else listOf(stab)

        var lastError: Throwable? = null
        for (v in vendors) for (e in effects) for (st in stabs) {
            val sel = if (v == VendorMode.NONE) baseSelector else selector
            val built = buildUseCases(req, st, surfaceProvider)
            try {
                val group = UseCaseGroup.Builder().apply {
                    built.all.forEach { addUseCase(it) }
                    if (e) addEffect(LookEffect(processor, targets))
                }.build()
                val bound = provider.bindToLifecycle(owner, sel, group)
                if (v != vendor) warnings += "No se pudo activar ${vendor.label}; se usa la cámara normal."
                if (req.useEffect && !e) {
                    warnings += "La vista previa con look no es compatible con esta combinación; se muestra sin efectos (la foto sí lleva el look)."
                }
                if (st != stab) warnings += "La estabilización no se pudo activar junto con el resto de opciones."
                camera = bound
                preview = built.preview
                imageCapture = built.imageCapture
                videoCapture = built.videoCapture
                return BindResult(
                    effectActive = e,
                    vendorMode = v,
                    stabilization = st,
                    photoResolution = built.imageCapture?.resolutionInfo?.resolution,
                    previewResolution = built.preview.resolutionInfo?.resolution,
                    warnings = warnings,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Falló el enlace (fabricante=$v, look=$e, estab=$st)", t)
                lastError = t
                provider.unbindAll()
            }
        }
        throw lastError ?: IllegalStateException("No se pudo abrir la cámara")
    }

    private class UseCases(
        val preview: Preview,
        val imageCapture: ImageCapture?,
        val videoCapture: VideoCapture<Recorder>?,
    ) {
        val all: List<UseCase> get() = listOfNotNull(preview, imageCapture, videoCapture)
    }

    private fun buildUseCases(req: BindRequest, stab: StabilizationState, surfaceProvider: Preview.SurfaceProvider): UseCases {
        val aspect = if (req.mode == CaptureMode.PHOTO) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9
        val previewBuilder = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(aspect, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .build(),
            )
        if (stab == StabilizationState.PREVIEW_AND_VIDEO) previewBuilder.setPreviewStabilizationEnabled(true)
        val newPreview = previewBuilder.build().also { it.setSurfaceProvider(surfaceProvider) }

        if (req.mode == CaptureMode.PHOTO) {
            val ic = ImageCapture.Builder()
                .setCaptureMode(
                    if (req.fastBurst) ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY,
                )
                .setJpegQuality(100)
                .setFlashMode(req.flashMode)
                .setTargetRotation(req.targetRotation)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .setAllowedResolutionMode(
                            if (req.resolutionMode == ResolutionMode.MAXIMUM) {
                                ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
                            } else {
                                ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION
                            },
                        )
                        .build(),
                )
                .build()
            return UseCases(newPreview, ic, null)
        }
        val quality = if (req.videoQuality == VideoQualityOption.UHD) Quality.UHD else Quality.FHD
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
            .build()
        val vc = VideoCapture.Builder(recorder)
            .setVideoStabilizationEnabled(stab == StabilizationState.VIDEO_ONLY)
            .setTargetRotation(req.targetRotation)
            .build()
        return UseCases(newPreview, null, vc)
    }

    fun unbind() {
        stopRecording()
        provider?.unbindAll()
        camera = null
    }

    fun setTargetRotation(rotation: Int) {
        imageCapture?.targetRotation = rotation
        videoCapture?.targetRotation = rotation
    }

    fun setZoomRatio(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    fun setExposureIndex(index: Int) {
        camera?.cameraControl?.setExposureCompensationIndex(index)
    }

    fun setFlashMode(mode: Int) {
        imageCapture?.flashMode = mode
    }

    fun focus(point: MeteringPoint) {
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB,
        ).setAutoCancelDuration(5, TimeUnit.SECONDS).build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun applyCaptureOptions(o: CaptureOptions) {
        val cam = camera ?: return
        val b = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, o.aeLock)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, o.awbLock)
        if (o.sceneHdr) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR)
        }
        o.fpsRange?.let { b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        if (o.ois) {
            b.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
            )
        }
        try {
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudieron aplicar opciones de Camera2", t)
        }
    }

    /** Captura un JPEG en memoria. Quien llama debe cerrar el ImageProxy. */
    suspend fun captureImage(executor: Executor): ImageProxy {
        val ic = imageCapture ?: throw IllegalStateException("La cámara de fotos no está lista")
        return suspendCancellableCoroutine { cont ->
            ic.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    if (cont.isActive) cont.resume(image) else image.close()
                }

                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            })
        }
    }

    val photoResolution: Size? get() = imageCapture?.resolutionInfo?.resolution

    val isRecording: Boolean get() = recording != null

    @SuppressLint("MissingPermission")
    fun startRecording(onEvent: (VideoRecordEvent) -> Unit): Boolean {
        val vc = videoCapture ?: return false
        if (recording != null) return false
        val name = "LumaCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/LumaCam")
        }
        val options = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        var pending = vc.output.prepareRecording(context, options)
        val audioGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (audioGranted) pending = pending.withAudioEnabled()
        recording = pending.start(mainExecutor) { event ->
            if (event is VideoRecordEvent.Finalize) recording = null
            onEvent(event)
        }
        return true
    }

    fun stopRecording() {
        recording?.stop()
        recording = null
    }

    fun release() {
        unbind()
        processor.release()
    }

    companion object {
        private const val TAG = "CameraSession"
    }
}

/** Espera un ListenableFuture sin depender de kotlinx-coroutines-guava. */
suspend fun <T> ListenableFuture<T>.await(): T {
    if (isDone) return get()
    return suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (t: Throwable) {
                cont.resumeWithException(t.cause ?: t)
            }
        }, Executor { it.run() })
        cont.invokeOnCancellation { cancel(false) }
    }
}
