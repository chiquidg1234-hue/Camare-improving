package com.lumacam.ui

import android.app.ActivityManager
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.video.VideoRecordEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import com.lumacam.camera.BindRequest
import com.lumacam.camera.BindResult
import com.lumacam.camera.CameraCaps
import com.lumacam.camera.CameraSession
import com.lumacam.camera.CaptureMode
import com.lumacam.camera.CaptureOptions
import com.lumacam.camera.DeviceProbe
import com.lumacam.camera.ResolutionMode
import com.lumacam.camera.StabilizationState
import com.lumacam.camera.VendorMode
import com.lumacam.camera.VideoQualityOption
import com.lumacam.core.capture.CapturePlanner
import com.lumacam.core.look.Adjustments
import com.lumacam.core.look.LookId
import com.lumacam.core.look.Looks
import com.lumacam.core.look.ResolvedLook
import com.lumacam.core.zoom.Facing
import com.lumacam.core.zoom.UltraWideKind
import com.lumacam.core.zoom.ZoomPlan
import com.lumacam.core.zoom.ZoomPlanner
import com.lumacam.core.zoom.ZoomStop
import com.lumacam.photo.CapturedFrame
import com.lumacam.photo.PhotoPipeline
import com.lumacam.settings.AppSettings
import com.lumacam.settings.SettingsStore
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

data class ExposureUi(
    val supported: Boolean = false,
    val index: Int = 0,
    val min: Int = 0,
    val max: Int = 0,
    val step: Float = 0f,
)

data class CaptureProgress(val text: String, val progress: Float)

data class FocusIndicator(val x: Float, val y: Float, val id: Long)

data class UiState(
    val settings: AppSettings,
    val permissionsGranted: Boolean = false,
    val audioGranted: Boolean = false,
    val cameraReady: Boolean = false,
    val currentCameraId: String? = null,
    val zoomRatio: Float = 1f,
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val zoomStops: List<ZoomStop> = emptyList(),
    val zoomPlan: ZoomPlan? = null,
    val exposure: ExposureUi = ExposureUi(),
    val aeLock: Boolean = false,
    val awbLock: Boolean = false,
    val vendorModes: List<VendorMode> = emptyList(),
    val sceneHdrAvailable: Boolean = false,
    val hasFlash: Boolean = false,
    val hasFront: Boolean = false,
    val bind: BindResult? = null,
    /** Avisos permanentes (zoom, compatibilidad). */
    val warnings: List<String> = emptyList(),
    val message: String? = null,
    val capture: CaptureProgress? = null,
    val recording: Boolean = false,
    val recordingSeconds: Long = 0,
    val lastThumbnail: Bitmap? = null,
    val lastUri: Uri? = null,
    val lastIsVideo: Boolean = false,
    val previewFps: Int = 0,
    val diagnostics: String = "",
    val bypass: Boolean = false,
    val focus: FocusIndicator? = null,
) {
    val look get() = Looks.byId(settings.lookId)
    val onUltraWide: Boolean
        get() = zoomPlan?.ultraWide == UltraWideKind.SEPARATE_CAMERA && currentCameraId == zoomPlan?.ultraWideCameraId
}

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    val session = CameraSession(app)

    private val _state = MutableStateFlow(UiState(settings = store.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var caps: List<CameraCaps> = emptyList()
    private var plan: ZoomPlan? = null
    private var frontId: String? = null
    private var owner: LifecycleOwner? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var targetRotation = Surface.ROTATION_0
    private var bindJob: Job? = null
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private var zoomObserver: Observer<ZoomState>? = null
    private var observedCamera: Camera? = null
    private var probed = false

    private val settings get() = _state.value.settings

    init {
        pushLook()
        viewModelScope.launch {
            while (true) {
                delay(1000)
                _state.update { it.copy(previewFps = session.processor.takeFrameCount()) }
            }
        }
    }

    // ---------- Ciclo de vida ----------

    fun onPermissions(camera: Boolean, audio: Boolean) {
        _state.update { it.copy(permissionsGranted = camera, audioGranted = audio) }
        startIfReady()
    }

    fun attach(owner: LifecycleOwner, provider: Preview.SurfaceProvider) {
        this.owner = owner
        this.surfaceProvider = provider
        startIfReady()
    }

    fun detach() {
        owner = null
        surfaceProvider = null
        bindJob?.cancel()
        removeZoomObserver()
        session.unbind()
        _state.update { it.copy(cameraReady = false, recording = false) }
    }

    private fun startIfReady() {
        if (owner == null || surfaceProvider == null || !_state.value.permissionsGranted) return
        viewModelScope.launch {
            var zoom: Float? = null
            if (!probed) {
                probed = true
                caps = withContext(Dispatchers.IO) { DeviceProbe.probe(getApplication()) }
                val usable = try {
                    session.usableCameraIds()
                } catch (t: Throwable) {
                    caps.map { it.id }.toSet()
                }
                val descriptors = caps.filter { it.id in usable }.map { it.descriptor }
                plan = ZoomPlanner.plan(descriptors)
                frontId = descriptors.firstOrNull { it.facing == Facing.FRONT }?.id
                val p = plan
                if (p == null) {
                    _state.update { it.copy(message = "No se encontró ninguna cámara utilizable.") }
                    return@launch
                }
                val startId = if (settings.front && frontId != null) frontId!! else p.cameraId
                zoom = if (startId == p.cameraId) p.initialZoomRatio else 1f
                _state.update {
                    it.copy(
                        zoomPlan = p,
                        currentCameraId = startId,
                        hasFront = frontId != null,
                        warnings = listOfNotNull(p.warning),
                    )
                }
            }
            rebind(zoom)
        }
    }

    private fun currentCaps(): CameraCaps? = caps.firstOrNull { it.id == _state.value.currentCameraId }

    private fun stopsFor(cameraId: String?): List<ZoomStop> {
        val p = plan ?: return emptyList()
        if (cameraId != null && cameraId == frontId) {
            return listOf(ZoomStop("1x", cameraId, 1f))
        }
        return p.stops
    }

    private fun rebind(zoomAfter: Float? = null) {
        val o = owner ?: return
        val sp = surfaceProvider ?: return
        val id = _state.value.currentCameraId ?: return
        bindJob?.cancel()
        bindJob = viewModelScope.launch {
            val s = settings
            val glOk = session.processor.ready.await()
            val vendorModes = try {
                session.availableVendorModes(id)
            } catch (t: Throwable) {
                emptyList()
            }
            val req = BindRequest(
                cameraId = id,
                mode = s.mode,
                resolutionMode = s.resolutionMode,
                videoQuality = s.videoQuality,
                stabilization = s.stabilization,
                vendorMode = if (s.vendorMode in vendorModes) s.vendorMode else VendorMode.NONE,
                useEffect = glOk,
                fastBurst = s.multiFrame,
                flashMode = s.flashMode,
                targetRotation = targetRotation,
            )
            try {
                removeZoomObserver()
                val result = session.bind(o, req, sp)
                observeCamera()
                zoomAfter?.let { session.setZoomRatio(it) }
                val bindWarnings = result.warnings.toMutableList()
                if (!glOk) {
                    bindWarnings += "OpenGL no arrancó (${session.processor.initError}); la vista previa va sin look, la foto sí lo lleva."
                }
                val c = currentCaps()
                _state.update {
                    it.copy(
                        cameraReady = true,
                        bind = result,
                        vendorModes = vendorModes,
                        sceneHdrAvailable = c?.hdrSceneMode == true,
                        hasFlash = c?.hasFlash == true,
                        zoomStops = stopsFor(id),
                        warnings = (listOfNotNull(plan?.warning) + bindWarnings).distinct(),
                        diagnostics = buildDiagnostics(result, vendorModes, glOk),
                    )
                }
                applyCaptureOptions()
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                _state.update { it.copy(cameraReady = false, message = "No se pudo abrir la cámara: ${t.message}") }
            }
        }
    }

    private fun observeCamera() {
        val cam = session.camera ?: return
        val obs = Observer<ZoomState> { z ->
            _state.update { it.copy(zoomRatio = z.zoomRatio, minZoom = z.minZoomRatio, maxZoom = z.maxZoomRatio) }
        }
        cam.cameraInfo.zoomState.observeForever(obs)
        zoomObserver = obs
        observedCamera = cam
        val es = cam.cameraInfo.exposureState
        _state.update {
            it.copy(
                exposure = ExposureUi(
                    supported = es.isExposureCompensationSupported,
                    index = es.exposureCompensationIndex,
                    min = es.exposureCompensationRange.lower,
                    max = es.exposureCompensationRange.upper,
                    step = es.exposureCompensationStep.toFloat(),
                ),
            )
        }
    }

    private fun removeZoomObserver() {
        zoomObserver?.let { obs -> observedCamera?.cameraInfo?.zoomState?.removeObserver(obs) }
        zoomObserver = null
        observedCamera = null
    }

    // ---------- Look ----------

    private fun resolvedLook(s: AppSettings = settings): ResolvedLook =
        ResolvedLook.resolve(Looks.byId(s.lookId), s.intensity, s.adjustments)

    private fun pushLook() {
        session.processor.setLook(resolvedLook())
    }

    private fun updateSettings(rebindNeeded: Boolean = false, f: (AppSettings) -> AppSettings) {
        val new = f(settings)
        if (new == settings) return
        _state.update { it.copy(settings = new) }
        store.save(new)
        if (rebindNeeded) rebind() else pushLook()
    }

    fun selectLook(id: LookId) {
        updateSettings { it.copy(lookId = id) }
        applyCaptureOptions()
    }

    fun setIntensity(v: Float) = updateSettings { it.copy(intensity = v.coerceIn(0f, 1f)) }

    fun setAdjustments(a: Adjustments) = updateSettings { it.copy(adjustments = a.clamped()) }

    fun resetAdjustments() = updateSettings { it.copy(adjustments = Adjustments.NEUTRAL) }

    fun setBypass(b: Boolean) {
        session.processor.bypass = b
        _state.update { it.copy(bypass = b) }
    }

    // ---------- Ajustes que requieren volver a enlazar ----------

    fun setMode(mode: CaptureMode) {
        if (_state.value.recording || _state.value.capture != null) return
        updateSettings(rebindNeeded = true) { it.copy(mode = mode) }
    }

    fun setMultiFrame(on: Boolean) = updateSettings(rebindNeeded = true) { it.copy(multiFrame = on) }
    fun setFrames(n: Int) = updateSettings { it.copy(frames = n.coerceIn(0, CapturePlanner.MAX_FRAMES)) }
    fun setJpegQuality(q: Int) = updateSettings { it.copy(jpegQuality = q.coerceIn(80, 100)) }
    fun setResolutionMode(m: ResolutionMode) = updateSettings(rebindNeeded = true) { it.copy(resolutionMode = m) }
    fun setVideoQuality(q: VideoQualityOption) = updateSettings(rebindNeeded = true) { it.copy(videoQuality = q) }
    fun setStabilization(on: Boolean) = updateSettings(rebindNeeded = true) { it.copy(stabilization = on) }
    fun setVendorMode(m: VendorMode) = updateSettings(rebindNeeded = true) { it.copy(vendorMode = m) }

    fun setGrid(on: Boolean) = updateSettings { it.copy(grid = on) }

    fun setLongExposureNight(on: Boolean) {
        updateSettings { it.copy(longExposureNight = on) }
        applyCaptureOptions()
    }

    fun setSceneHdr(on: Boolean) {
        updateSettings { it.copy(sceneHdr = on) }
        applyCaptureOptions()
    }

    fun cycleFlash() {
        val next = when (settings.flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        updateSettings { it.copy(flashMode = next) }
        session.setFlashMode(next)
    }

    fun switchFacing() {
        if (_state.value.recording || _state.value.capture != null) return
        val p = plan ?: return
        val front = frontId ?: return
        val toFront = _state.value.currentCameraId != front
        updateSettings { it.copy(front = toFront) }
        val id = if (toFront) front else p.cameraId
        _state.update { it.copy(currentCameraId = id) }
        rebind(if (toFront) 1f else p.initialZoomRatio)
    }

    // ---------- Controles ----------

    fun selectZoomStop(stop: ZoomStop) {
        if (stop.cameraId != _state.value.currentCameraId) {
            if (_state.value.recording) return
            _state.update { it.copy(currentCameraId = stop.cameraId) }
            rebind(stop.zoomRatio)
        } else {
            session.setZoomRatio(stop.zoomRatio)
        }
    }

    fun pinchZoom(scale: Float) {
        val s = _state.value
        val target = (s.zoomRatio * scale).coerceIn(s.minZoom, s.maxZoom)
        session.setZoomRatio(target)
    }

    fun setExposureIndex(index: Int) {
        val e = _state.value.exposure
        if (!e.supported) return
        val i = index.coerceIn(e.min, e.max)
        session.setExposureIndex(i)
        _state.update { it.copy(exposure = e.copy(index = i)) }
    }

    fun toggleAeLock() {
        _state.update { it.copy(aeLock = !it.aeLock) }
        applyCaptureOptions()
    }

    fun toggleAwbLock() {
        _state.update { it.copy(awbLock = !it.awbLock) }
        applyCaptureOptions()
    }

    fun focus(point: MeteringPoint, x: Float, y: Float) {
        session.focus(point)
        _state.update { it.copy(focus = FocusIndicator(x, y, System.nanoTime())) }
    }

    fun onRotationChanged(rotation: Int) {
        if (rotation == targetRotation) return
        targetRotation = rotation
        session.setTargetRotation(rotation)
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun captureOptions(burstLock: Boolean = false): CaptureOptions {
        val s = settings
        val st = _state.value
        val c = currentCaps()
        val night = s.lookId == LookId.NIGHT && s.longExposureNight && s.mode == CaptureMode.PHOTO
        return CaptureOptions(
            aeLock = st.aeLock || burstLock,
            awbLock = st.awbLock || burstLock,
            sceneHdr = s.sceneHdr && c?.hdrSceneMode == true && s.mode == CaptureMode.PHOTO &&
                (st.bind?.vendorMode ?: VendorMode.NONE) == VendorMode.NONE,
            fpsRange = if (night) c?.longExposureFpsRange() else null,
            ois = c?.hasOis == true,
        )
    }

    private fun applyCaptureOptions(burstLock: Boolean = false) {
        session.applyCaptureOptions(captureOptions(burstLock))
    }

    // ---------- Captura ----------

    fun onShutter() {
        if (!_state.value.cameraReady) return
        if (settings.mode == CaptureMode.VIDEO) toggleRecording() else capturePhoto()
    }

    private fun memoryBudget(): Long {
        val am = getApplication<Application>().getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return min((mi.availMem * 0.5).toLong(), 1_800_000_000L).coerceAtLeast(150_000_000L)
    }

    private fun capturePhoto() {
        if (_state.value.capture != null) return
        val s = settings
        val bind = _state.value.bind
        val look = resolvedLook(s)
        val res = session.photoResolution ?: Size(4000, 3000)
        val vendorActive = (bind?.vendorMode ?: VendorMode.NONE) != VendorMode.NONE
        val requested = when {
            !s.multiFrame -> 1
            vendorActive -> 1 // el modo del fabricante ya hace su propio multi-frame
            s.flashMode != ImageCapture.FLASH_MODE_OFF -> 1 // con flash no tiene sentido la ráfaga
            s.frames > 0 -> s.frames
            else -> Looks.byId(s.lookId).suggestedFrames
        }
        val capturePlan = CapturePlanner.plan(res.width, res.height, requested, memoryBudget())
        val n = capturePlan.frames
        viewModelScope.launch {
            _state.update { it.copy(capture = CaptureProgress(if (n > 1) "Mantén quieto… 1/$n" else "Capturando…", 0f)) }
            val decodes = ArrayList<Deferred<CapturedFrame>>()
            val decoded = java.util.Collections.synchronizedList(ArrayList<CapturedFrame>())
            try {
                if (n > 1) applyCaptureOptions(burstLock = true)
                for (i in 0 until n) {
                    if (n > 1) _state.update { it.copy(capture = CaptureProgress("Mantén quieto… ${i + 1}/$n", i.toFloat() / n * 0.3f)) }
                    val image = session.captureImage(captureExecutor)
                    decodes += async(Dispatchers.Default) {
                        image.use { PhotoPipeline.decode(it, capturePlan.decodeSampleSize, keepJpeg = i == 0) }
                            .also { decoded += it }
                    }
                }
                if (n > 1) applyCaptureOptions()
                _state.update { it.copy(capture = CaptureProgress("Procesando…", 0.3f)) }
                val frames = decodes.awaitAll()
                decoded.clear() // a partir de aquí el pipeline se encarga de liberarlos
                val result = PhotoPipeline.process(getApplication(), frames, look, s.jpegQuality, capturePlan.note) { text, p ->
                    _state.update { it.copy(capture = CaptureProgress(text, 0.3f + 0.7f * p)) }
                }
                _state.update {
                    it.copy(
                        lastThumbnail = result.thumbnail,
                        lastUri = result.uri,
                        lastIsVideo = false,
                        message = "Foto guardada · ${result.width}x${result.height} · ${result.summary}",
                    )
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                // Liberar lo que se haya decodificado.
                decodes.forEach { it.cancel() }
                synchronized(decoded) { decoded.forEach { f -> f.bitmap.recycle() } }
                _state.update { it.copy(message = "Error al capturar: ${t.message ?: t.javaClass.simpleName}") }
            } finally {
                if (n > 1) applyCaptureOptions()
                _state.update { it.copy(capture = null) }
            }
        }
    }

    private fun toggleRecording() {
        if (session.isRecording) {
            session.stopRecording()
            return
        }
        val started = session.startRecording { event ->
            when (event) {
                is VideoRecordEvent.Start -> _state.update { it.copy(recording = true, recordingSeconds = 0) }
                is VideoRecordEvent.Status -> _state.update {
                    it.copy(recordingSeconds = event.recordingStats.recordedDurationNanos / 1_000_000_000L)
                }
                is VideoRecordEvent.Finalize -> {
                    _state.update { it.copy(recording = false) }
                    if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                        _state.update { it.copy(message = "Error al grabar (${event.error}): ${event.cause?.message ?: ""}") }
                    }
                    val uri = event.outputResults.outputUri
                    if (uri != Uri.EMPTY) {
                        _state.update { it.copy(lastUri = uri, lastIsVideo = true, message = if (event.hasError()) it.message else "Video guardado en Películas/LumaCam") }
                        loadVideoThumbnail(uri)
                    }
                }
                else -> Unit
            }
        }
        if (!started) _state.update { it.copy(message = "La grabación no está lista") }
    }

    private fun loadVideoThumbnail(uri: Uri) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { getApplication<Application>().contentResolver.loadThumbnail(uri, Size(240, 240), null) }.getOrNull()
            }
            if (bmp != null) _state.update { it.copy(lastThumbnail = bmp) }
        }
    }

    // ---------- Diagnóstico ----------

    private fun buildDiagnostics(result: BindResult, vendorModes: List<VendorMode>, glOk: Boolean): String = buildString {
        val s = settings
        appendLine("LumaCam · diagnóstico")
        appendLine()
        val p = plan
        if (p != null) {
            appendLine("Zoom: cámara inicial ${p.cameraId}, zoom inicial ${ZoomPlanner.fmt(p.initialZoomRatio)}x, rango ${ZoomPlanner.fmt(p.minZoomRatio)}–${ZoomPlanner.fmt(p.maxZoomRatio)}x")
            appendLine("Ultra gran angular: ${p.ultraWide}${p.ultraWideEquivalent?.let { " (≈${ZoomPlanner.fmt(it)}x)" } ?: ""}")
            p.warning?.let { appendLine("Aviso: $it") }
        }
        appendLine("Cámara en uso: ${_state.value.currentCameraId}, modo ${s.mode}")
        appendLine("OpenGL: ${if (glOk) "ES ${session.processor.glesVersion}.0 OK" else "FALLÓ (${session.processor.initError})"}; look en vista previa: ${result.effectActive}")
        appendLine("Foto: ${result.photoResolution?.let { "${it.width}x${it.height}" } ?: "-"} (${s.resolutionMode}); vista previa: ${result.previewResolution?.let { "${it.width}x${it.height}" } ?: "-"}")
        appendLine("Estabilización de video: ${stabText(result.stabilization)}")
        appendLine("Modos del fabricante (extensiones CameraX): ${vendorModes.joinToString { it.label }.ifEmpty { "ninguno" }}; activo: ${result.vendorMode.label}")
        val c = currentCaps()
        appendLine("HDR por escena (Camera2): ${c?.hdrSceneMode == true}; OIS: ${c?.hasOis == true}; rango FPS para noche: ${c?.longExposureFpsRange() ?: "-"}")
        appendLine("Memoria para fotos: ${memoryBudget() / 1_000_000} MB")
        result.warnings.forEach { appendLine("Aviso: $it") }
        appendLine()
        append(DeviceProbe.report(caps))
    }

    fun stabText(st: StabilizationState): String = when (st) {
        StabilizationState.OFF -> "desactivada"
        StabilizationState.PREVIEW_AND_VIDEO -> "activa (vista previa y video)"
        StabilizationState.VIDEO_ONLY -> "activa (sólo video)"
        StabilizationState.UNSUPPORTED -> "no disponible en este teléfono"
    }

    fun formatSeconds(s: Long): String = String.format(Locale.US, "%02d:%02d", s / 60, s % 60)

    override fun onCleared() {
        removeZoomObserver()
        session.release()
        captureExecutor.shutdown()
        super.onCleared()
    }
}
