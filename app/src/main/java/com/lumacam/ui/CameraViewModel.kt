package com.lumacam.ui

import android.app.ActivityManager
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
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
import com.lumacam.core.dual.DualLayout
import com.lumacam.core.dual.PipCorner
import com.lumacam.core.prompter.Teleprompter
import com.lumacam.core.look.Adjustments
import com.lumacam.core.look.LookId
import com.lumacam.core.look.Looks
import com.lumacam.core.look.ResolvedLook
import com.lumacam.core.zoom.Facing
import com.lumacam.core.zoom.UltraWideKind
import com.lumacam.core.zoom.ZoomPlan
import com.lumacam.core.zoom.ZoomPlanner
import com.lumacam.core.zoom.ZoomStop
import com.lumacam.core.pose.AngleCategory
import com.lumacam.core.pose.CameraAngle
import com.lumacam.core.pose.Joint
import com.lumacam.core.pose.PoseLibrary
import com.lumacam.core.pose.PoseMatcher
import com.lumacam.core.pose.PoseTemplate
import com.lumacam.core.pose.PoseTrigger
import com.lumacam.core.pose.Pt
import com.lumacam.dual.DualController
import com.lumacam.dual.DualRenderer
import com.lumacam.pose.OrientationSensor
import com.lumacam.pose.PoseAnalyzer
import com.lumacam.photo.CapturedFrame
import com.lumacam.photo.PhotoPipeline
import com.lumacam.settings.AppSettings
import com.lumacam.settings.Script
import com.lumacam.settings.ScriptStore
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

/** Estado del modo poses. */
data class PoseUi(
    val angle: CameraAngle? = null,
    val category: AngleCategory = AngleCategory.EYE,
    val poses: List<PoseTemplate> = PoseLibrary.forCategory(AngleCategory.EYE, selfie = false),
    val index: Int = 0,
    /** Articulaciones detectadas (coordenadas de pantalla 0..1). */
    val detected: Map<Joint, Pt> = emptyMap(),
    val score: Float? = null,
    val mirrored: Boolean = false,
    val hint: String? = null,
    /** Segundos de la cuenta atrás del disparo automático, o null. */
    val countdown: Int? = null,
    val detectionActive: Boolean = false,
    val sensorAvailable: Boolean = true,
) {
    val current: PoseTemplate? get() = poses.getOrNull(index)
}

/** Secciones de la fila de modos. */
enum class Section(val label: String) {
    PHOTO("FOTO"),
    POSES("POSES"),
    VIDEO("VIDEO"),
    DUAL("DUAL"),
    PROMPTER("PRESENTAR"),
}

/** Estado del modo DUAL. */
data class DualUi(
    /** Hay lienzo DUAL creado (la vista previa sale de [DualRenderer]). */
    val active: Boolean = false,
    /** Las dos cámaras a la vez (true) o por turnos (false); null = aún no se sabe. */
    val concurrent: Boolean? = null,
    /** Cámara grande: 0 = trasera, 1 = frontal. */
    val mainSlot: Int = 0,
)

/** Estado del teleprompter (modo PRESENTAR). */
data class PrompterUi(
    val scripts: List<Script> = emptyList(),
    val selectedId: Long = 0,
    /** El texto está subiendo. */
    val running: Boolean = false,
    /** Cuenta atrás antes de grabar (3, 2, 1) o null. */
    val countdown: Int? = null,
    /** Cambia para volver el texto al principio. */
    val resetToken: Int = 0,
) {
    val script: Script? get() = scripts.firstOrNull { it.id == selectedId } ?: scripts.firstOrNull()
}

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
    val benchmark: String? = null,
    val benchmarkRunning: Boolean = false,
    val pose: PoseUi = PoseUi(),
    val dual: DualUi = DualUi(),
    val prompter: PrompterUi = PrompterUi(),
) {
    val look get() = Looks.byId(settings.lookId)
    val poseActive: Boolean get() = settings.poseMode && settings.mode == CaptureMode.PHOTO && !settings.dualMode
    val section: Section
        get() = when {
            settings.dualMode -> Section.DUAL
            settings.mode == CaptureMode.VIDEO && settings.prompterMode -> Section.PROMPTER
            settings.mode == CaptureMode.VIDEO -> Section.VIDEO
            settings.poseMode -> Section.POSES
            else -> Section.PHOTO
        }
    val dualActive: Boolean get() = settings.dualMode
    val prompterActive: Boolean get() = section == Section.PROMPTER
    /** El disparador graba video (rojo) en vez de hacer fotos. */
    val videoShutter: Boolean get() = if (settings.dualMode) settings.dualVideo else settings.mode == CaptureMode.VIDEO
    val onUltraWide: Boolean
        get() = zoomPlan?.ultraWide == UltraWideKind.SEPARATE_CAMERA && currentCameraId == zoomPlan?.ultraWideCameraId
}

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val scriptStore = ScriptStore(app)
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
    /** El vigilante desactivó el efecto porque no llegaban imágenes. */
    private var effectDisabledByWatchdog = false
    private val sound = android.media.MediaActionSound().apply {
        load(android.media.MediaActionSound.SHUTTER_CLICK)
        load(android.media.MediaActionSound.START_VIDEO_RECORDING)
        load(android.media.MediaActionSound.STOP_VIDEO_RECORDING)
    }
    private var watchdogJob: Job? = null

    // Modo poses
    private val orientation = OrientationSensor(app)
    private val poseAnalyzer = PoseAnalyzer(::onPoseDetected)
    private val poseTrigger = PoseTrigger()
    private var visible = true

    private val settings get() = _state.value.settings

    // Modo DUAL
    private var dual: DualController? = null
    val dualRenderer: DualRenderer? get() = dual?.renderer
    private var recordTicker: Job? = null
    /** Parejas de cámaras que el teléfono puede usar a la vez (para el diagnóstico). */
    private var concurrentCombos = 0

    // Modo PRESENTAR
    private var countdownJob: Job? = null
    /** Se pasó sola a la cámara frontal al entrar en PRESENTAR (al salir se vuelve). */
    private var prompterAutoFront = false

    init {
        pushLook()
        loadLastCapture()
        _state.update {
            it.copy(
                pose = it.pose.copy(sensorAvailable = orientation.available),
                prompter = it.prompter.copy(scripts = scriptStore.load(), selectedId = scriptStore.selectedId),
            )
        }
        viewModelScope.launch {
            orientation.angle.collect { a -> onAngle(a) }
        }
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
        countdownJob?.cancel()
        if (dual?.isRecording == true) stopDualRecording()
        dual?.unbind()
        bindJob?.cancel()
        watchdogJob?.cancel()
        removeZoomObserver()
        session.unbind()
        orientation.stop()
        _state.update { it.copy(cameraReady = false, recording = false) }
    }

    /** La actividad pasa a primer plano o a segundo plano (sensor sólo cuando se ve). */
    fun onVisible(v: Boolean) {
        visible = v
        updateSensor()
        if (!v) {
            // En segundo plano la cámara se para: se termina y guarda lo que se estaba grabando.
            countdownJob?.cancel()
            _state.update { it.copy(prompter = it.prompter.copy(countdown = null, running = false)) }
            if (dual?.isRecording == true) stopDualRecording()
        }
    }

    private fun updateSensor() {
        if (visible && _state.value.poseActive) orientation.start() else orientation.stop()
    }

    private fun startIfReady() {
        if (owner == null || surfaceProvider == null || !_state.value.permissionsGranted) return
        viewModelScope.launch {
            var zoom: Float? = null
            if (!probed) {
                probed = true
                caps = withContext(Dispatchers.IO) { DeviceProbe.probe(getApplication()) }
                // Al iniciar: rangos de zoom (CONTROL_ZOOM_RATIO_RANGE) y cámaras físicas al log.
                DeviceProbe.report(caps).lines().forEach { android.util.Log.i("LumaCam", it) }
                val usable = try {
                    session.usableCameraIds()
                } catch (t: Throwable) {
                    caps.map { it.id }.toSet()
                }
                val descriptors = caps.filter { it.id in usable }.map { it.descriptor }
                plan = ZoomPlanner.plan(descriptors)
                concurrentCombos = runCatching { session.provider().availableConcurrentCameraInfos.size }.getOrDefault(0)
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
        if (settings.dualMode) {
            rebindDual()
            return
        }
        releaseDual()
        val o = owner ?: return
        val sp = surfaceProvider ?: return
        val id = _state.value.currentCameraId ?: return
        // Al cambiar de modo o de ajuste se conserva el zoom actual (CameraX lo reinicia).
        val keepZoom = zoomAfter ?: _state.value.zoomRatio.takeIf { _state.value.cameraReady && observedCamera != null }
        bindJob?.cancel()
        watchdogJob?.cancel()
        _state.update { it.copy(cameraReady = false) }
        bindJob = viewModelScope.launch {
            val s = settings
            // Si OpenGL no responde, se abre la cámara sin efectos en vez de quedarse esperando.
            val glOk = kotlinx.coroutines.withTimeoutOrNull(4000) { session.processor.ready.await() } ?: false
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
                useEffect = glOk && s.gpuPreview && !effectDisabledByWatchdog,
                fastBurst = s.multiFrame,
                flashMode = s.flashMode,
                targetRotation = targetRotation,
                poseMode = s.poseMode && s.mode == CaptureMode.PHOTO,
                analyzer = if (s.poseMode && s.poseDetection && s.mode == CaptureMode.PHOTO) poseAnalyzer else null,
            )
            val front = id == frontId
            poseAnalyzer.frontCamera = front
            orientation.frontCamera = front
            try {
                removeZoomObserver()
                val result = session.bind(o, req, sp)
                observeCamera()
                keepZoom?.let { z ->
                    val zs = session.camera?.cameraInfo?.zoomState?.value
                    session.setZoomRatio(if (zs != null) z.coerceIn(zs.minZoomRatio, zs.maxZoomRatio) else z)
                }
                val bindWarnings = result.warnings.toMutableList()
                if (effectDisabledByWatchdog && s.gpuPreview) {
                    bindWarnings += "La vista previa con efectos no recibía imágenes en este teléfono y se desactivó; la foto sí lleva el look."
                }
                if (!glOk) {
                    bindWarnings += "OpenGL no arrancó (${session.processor.initError ?: "no respondió a tiempo"}); la vista previa va sin look, la foto sí lo lleva."
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
                        pose = it.pose.copy(
                            detectionActive = result.analysisActive,
                            detected = emptyMap(), score = null, hint = null, countdown = null,
                            poses = PoseLibrary.forCategory(it.pose.category, selfie = front),
                            index = 0,
                        ),
                    )
                }
                poseTrigger.reset()
                updateSensor()
                applyCaptureOptions()
                if (result.effectActive) startWatchdog()
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                _state.update { it.copy(cameraReady = false, message = "No se pudo abrir la cámara: ${t.message}") }
            }
        }
    }

    // ---------- Modo DUAL ----------

    private fun ensureDual(): DualController =
        dual ?: DualController(getApplication<Application>(), session).also {
            dual = it
            _state.update { st -> st.copy(dual = st.dual.copy(active = true)) }
        }

    private fun releaseDual() {
        val d = dual ?: return
        if (d.isRecording) stopDualRecording()
        recordTicker?.cancel()
        d.release()
        dual = null
        _state.update { it.copy(dual = DualUi()) }
    }

    private fun rebindDual() {
        val o = owner ?: return
        val p = plan ?: return
        bindJob?.cancel()
        watchdogJob?.cancel()
        _state.update { it.copy(cameraReady = false) }
        val d = ensureDual()
        bindJob = viewModelScope.launch {
            val s = settings
            val front = frontId
            if (front == null) {
                _state.update { it.copy(message = "El modo DUAL necesita una cámara frontal.") }
                return@launch
            }
            val glOk = kotlinx.coroutines.withTimeoutOrNull(4000) { d.renderer.ready.await() } ?: false
            if (!glOk) {
                _state.update { it.copy(message = "El modo DUAL necesita OpenGL y no arrancó (${d.renderer.initError ?: "no respondió"}).") }
                return@launch
            }
            d.renderer.setLook(resolvedLook(s))
            d.setLayout(s.dualLayout, s.pipCorner)
            try {
                removeZoomObserver()
                val r = d.bind(o, p.cameraId, front, s.dualVideo, s.flashMode, if (s.dualFrontMain) 1 else 0)
                _state.update {
                    it.copy(
                        cameraReady = true,
                        zoomStops = emptyList(),
                        warnings = r.warnings,
                        dual = it.dual.copy(active = true, concurrent = r.concurrent, mainSlot = d.renderer.mainSlot),
                    )
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                _state.update { it.copy(cameraReady = false, message = "No se pudo abrir el modo DUAL: ${t.message}") }
            }
        }
    }

    /** Botón de cambiar en DUAL: intercambia la cámara grande (o abre la otra, por turnos). */
    fun dualSwap() {
        val d = dual ?: return
        if (_state.value.capture != null) return
        viewModelScope.launch {
            try {
                val main = d.swap()
                updateSettings { it.copy(dualFrontMain = main == 1) }
                _state.update { it.copy(dual = it.dual.copy(mainSlot = main)) }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                _state.update { it.copy(message = "No se pudo cambiar de cámara: ${t.message}") }
            }
        }
    }

    fun setDualLayout(layout: DualLayout) {
        updateSettings { it.copy(dualLayout = layout) }
        dual?.setLayout(layout, settings.pipCorner)
    }

    /** Mueve la ventanita a la siguiente esquina. */
    fun cycleDualCorner() {
        val next = PipCorner.entries[(settings.pipCorner.ordinal + 1) % PipCorner.entries.size]
        updateSettings { it.copy(pipCorner = next) }
        dual?.setLayout(settings.dualLayout, next)
    }

    fun setDualVideo(on: Boolean) {
        if (_state.value.recording || _state.value.capture != null) return
        updateSettings(rebindNeeded = true) { it.copy(dualVideo = on) }
    }

    private fun captureDualPhoto() {
        val d = dual ?: return
        if (_state.value.capture != null) return
        val s = settings
        val look = resolvedLook(s)
        viewModelScope.launch {
            _state.update { it.copy(capture = CaptureProgress("Capturando…", 0f)) }
            try {
                val result = d.takePhoto(
                    look, s.jpegQuality, s.dualLayout, s.pipCorner,
                    onShutter = { sound.play(android.media.MediaActionSound.SHUTTER_CLICK) },
                    progress = { text, pr -> _state.update { it.copy(capture = CaptureProgress(text, pr)) } },
                )
                _state.update {
                    it.copy(
                        lastThumbnail = result.thumbnail,
                        lastUri = result.uri,
                        lastIsVideo = false,
                        message = "Foto doble guardada · ${result.width}x${result.height}",
                        dual = it.dual.copy(mainSlot = d.renderer.mainSlot),
                    )
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                _state.update { it.copy(message = "Error en la foto doble: ${t.message ?: t.javaClass.simpleName}") }
            } finally {
                _state.update { it.copy(capture = null) }
            }
        }
    }

    private fun toggleDualRecording() {
        val d = dual ?: return
        if (d.isRecording) {
            stopDualRecording()
            return
        }
        try {
            d.startRecording(audio = _state.value.audioGranted)
        } catch (t: Throwable) {
            _state.update { it.copy(message = "No se pudo empezar a grabar: ${t.message ?: t.javaClass.simpleName}") }
            return
        }
        sound.play(android.media.MediaActionSound.START_VIDEO_RECORDING)
        _state.update { it.copy(recording = true, recordingSeconds = 0) }
        val start = SystemClock.elapsedRealtime()
        recordTicker?.cancel()
        recordTicker = viewModelScope.launch {
            while (true) {
                delay(500)
                _state.update { it.copy(recordingSeconds = (SystemClock.elapsedRealtime() - start) / 1000) }
            }
        }
    }

    private fun stopDualRecording() {
        val d = dual ?: return
        recordTicker?.cancel()
        recordTicker = null
        val uri = try {
            d.stopRecording()
        } catch (t: Throwable) {
            android.util.Log.w("LumaCam", "Error al terminar la grabación DUAL", t)
            null
        }
        sound.play(android.media.MediaActionSound.STOP_VIDEO_RECORDING)
        _state.update { it.copy(recording = false) }
        if (uri != null) {
            _state.update { it.copy(lastUri = uri, lastIsVideo = true, message = "Video doble guardado en Películas/LumaCam") }
            loadVideoThumbnail(uri)
        } else {
            _state.update { it.copy(message = "No se llegó a grabar nada.") }
        }
    }

    /**
     * Si con el efecto OpenGL no se dibuja ningún frame en unos segundos (vista previa negra),
     * se vuelve a abrir la cámara sin efecto. La foto sigue llevando el look (se hace en CPU).
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        val start = session.processor.framesRendered
        watchdogJob = viewModelScope.launch {
            delay(3500)
            val st = _state.value
            val visible = owner?.lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) == true
            if (st.cameraReady && visible && session.processor.framesRendered == start) {
                effectDisabledByWatchdog = true
                rebind()
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
        val look = resolvedLook()
        session.processor.setLook(look)
        dual?.renderer?.setLook(look)
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

    /** FOTO, POSES, VIDEO, DUAL (dos cámaras) o PRESENTAR (video con teleprompter). */
    fun selectSection(section: Section) {
        val st = _state.value
        if (st.recording || st.capture != null || section == st.section) return
        countdownJob?.cancel()
        val leavingPrompter = st.section == Section.PROMPTER
        updateSettings {
            it.copy(
                mode = if (section == Section.VIDEO || section == Section.PROMPTER) CaptureMode.VIDEO else CaptureMode.PHOTO,
                poseMode = section == Section.POSES,
                dualMode = section == Section.DUAL,
                prompterMode = section == Section.PROMPTER,
            )
        }
        _state.update { it.copy(prompter = it.prompter.copy(running = false, countdown = null)) }
        // PRESENTAR usa la cámara frontal (mirar al texto es mirar a la cámara); al salir se
        // vuelve a la trasera si el cambio fue automático.
        var zoom: Float? = null
        val p = plan
        val front = frontId
        if (section == Section.PROMPTER && front != null && st.currentCameraId != front) {
            prompterAutoFront = true
            updateSettings { it.copy(front = true) }
            _state.update { it.copy(currentCameraId = front) }
            zoom = 1f
        } else if (leavingPrompter && prompterAutoFront && p != null) {
            prompterAutoFront = false
            updateSettings { it.copy(front = false) }
            _state.update { it.copy(currentCameraId = p.cameraId) }
            zoom = p.initialZoomRatio
        }
        rebind(zoom)
        updateSensor()
    }

    // ---------- Modo PRESENTAR (teleprompter) ----------

    private fun prompterShutter() {
        if (session.isRecording) {
            session.stopRecording()
            return
        }
        if (countdownJob?.isActive == true) {
            countdownJob?.cancel()
            _state.update { it.copy(prompter = it.prompter.copy(countdown = null)) }
            return
        }
        if (!settings.prompterCountdown) {
            toggleRecording()
            return
        }
        countdownJob = viewModelScope.launch {
            try {
                for (i in 3 downTo 1) {
                    _state.update { it.copy(prompter = it.prompter.copy(countdown = i)) }
                    delay(1000)
                }
            } finally {
                _state.update { it.copy(prompter = it.prompter.copy(countdown = null)) }
            }
            toggleRecording()
        }
    }

    fun prompterToggle() = _state.update { it.copy(prompter = it.prompter.copy(running = !it.prompter.running)) }

    /** El texto llegó al final. */
    fun prompterFinished() = _state.update { it.copy(prompter = it.prompter.copy(running = false)) }

    fun prompterRestart() = _state.update {
        it.copy(prompter = it.prompter.copy(running = false, resetToken = it.prompter.resetToken + 1))
    }

    fun setPrompterWpm(wpm: Int) = updateSettings { it.copy(prompterWpm = Teleprompter.clampWpm(wpm)) }
    fun setPrompterTextSize(sp: Int) = updateSettings { it.copy(prompterTextSp = Teleprompter.clampTextSize(sp)) }
    fun setPrompterMirror(on: Boolean) = updateSettings { it.copy(prompterMirror = on) }
    fun setPrompterCountdown(on: Boolean) = updateSettings { it.copy(prompterCountdown = on) }

    fun selectScript(id: Long) {
        scriptStore.selectedId = id
        _state.update { it.copy(prompter = it.prompter.copy(selectedId = id, running = false, resetToken = it.prompter.resetToken + 1)) }
    }

    /** Guarda un guion (nuevo si [id] es null) y lo deja elegido. */
    fun saveScript(id: Long?, text: String) {
        val list = _state.value.prompter.scripts
        val newId = id ?: (System.currentTimeMillis())
        val script = Script(newId, Teleprompter.titleFor(text), text)
        val updated = if (list.any { it.id == newId }) list.map { if (it.id == newId) script else it } else list + script
        scriptStore.save(updated)
        scriptStore.selectedId = newId
        _state.update {
            it.copy(prompter = it.prompter.copy(scripts = updated, selectedId = newId, running = false, resetToken = it.prompter.resetToken + 1))
        }
    }

    fun deleteScript(id: Long) {
        val remaining = _state.value.prompter.scripts.filter { it.id != id }
        scriptStore.save(remaining)
        val list = scriptStore.load()
        val selected = list.first().id
        scriptStore.selectedId = selected
        _state.update {
            it.copy(prompter = it.prompter.copy(scripts = list, selectedId = selected, running = false, resetToken = it.prompter.resetToken + 1))
        }
    }

    // ---------- Modo poses ----------

    fun setPoseDetection(on: Boolean) = updateSettings(rebindNeeded = true) { it.copy(poseDetection = on) }

    fun setPoseAutoShot(on: Boolean) {
        updateSettings { it.copy(poseAutoShot = on) }
        poseTrigger.reset()
        _state.update { it.copy(pose = it.pose.copy(countdown = null)) }
    }

    fun nextPose(step: Int) {
        poseTrigger.reset()
        _state.update {
            val n = it.pose.poses.size
            if (n == 0) it else it.copy(pose = it.pose.copy(index = ((it.pose.index + step) % n + n) % n, countdown = null, score = null, hint = null))
        }
    }

    private fun onAngle(a: CameraAngle?) {
        val changed = a != null && a.category != _state.value.pose.category
        _state.update { st ->
            val p = st.pose
            if (a == null) return@update st.copy(pose = p.copy(angle = null))
            if (a.category == p.category) return@update st.copy(pose = p.copy(angle = a))
            // Cambió el ángulo: nuevas sugerencias para ese ángulo.
            val selfie = st.currentCameraId != null && st.currentCameraId == frontId
            st.copy(
                pose = p.copy(
                    angle = a, category = a.category,
                    poses = PoseLibrary.forCategory(a.category, selfie), index = 0,
                    score = null, hint = null, countdown = null,
                ),
            )
        }
        if (changed) poseTrigger.reset()
    }

    /** Llega en el hilo principal desde ML Kit. */
    private fun onPoseDetected(points: Map<Joint, Pt>) {
        val st = _state.value
        if (!st.poseActive) return
        val template = st.pose.current ?: return
        val now = System.currentTimeMillis()
        val result = if (points.isEmpty()) null else PoseMatcher.match(points, template)
        val score = result?.score
        val ratio = result?.sizeRatio
        val hint = when {
            points.isEmpty() -> "No veo a nadie: aléjate o encuadra el cuerpo"
            ratio != null && ratio < 0.55f -> "Más cerca (o acerca la cámara)"
            ratio != null && ratio > 1.7f -> "Un poco más lejos"
            else -> result?.hint
        }
        var countdown: Int? = null
        if (st.settings.poseAutoShot && st.capture == null && st.cameraReady) {
            when (val t = poseTrigger.update(score, now)) {
                is PoseTrigger.State.Countdown -> countdown = t.secondsLeft(now)
                PoseTrigger.State.Fire -> capturePhoto()
                else -> Unit
            }
        }
        _state.update {
            it.copy(
                pose = it.pose.copy(
                    detected = points,
                    score = score,
                    mirrored = (score ?: 0f) > 0.5f && result?.mirrored == true,
                    hint = hint,
                    countdown = countdown,
                ),
            )
        }
    }

    fun setMultiFrame(on: Boolean) = updateSettings(rebindNeeded = true) { it.copy(multiFrame = on) }
    fun setFrames(n: Int) = updateSettings { it.copy(frames = n.coerceIn(0, CapturePlanner.MAX_FRAMES)) }
    fun setJpegQuality(q: Int) = updateSettings { it.copy(jpegQuality = q.coerceIn(80, 100)) }
    fun setResolutionMode(m: ResolutionMode) = updateSettings(rebindNeeded = true) { it.copy(resolutionMode = m) }
    fun setVideoQuality(q: VideoQualityOption) = updateSettings(rebindNeeded = true) { it.copy(videoQuality = q) }
    fun setStabilization(on: Boolean) = updateSettings(rebindNeeded = true) { it.copy(stabilization = on) }
    fun setVendorMode(m: VendorMode) = updateSettings(rebindNeeded = true) { it.copy(vendorMode = m) }

    fun setGrid(on: Boolean) = updateSettings { it.copy(grid = on) }

    fun setGpuPreview(on: Boolean) {
        if (on) effectDisabledByWatchdog = false
        updateSettings(rebindNeeded = true) { it.copy(gpuPreview = on) }
    }

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
        dual?.setFlashMode(next)
    }

    fun switchFacing() {
        if (settings.dualMode) {
            dualSwap()
            return
        }
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
        dual?.let {
            it.pinchZoom(scale)
            return
        }
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
        val s = settings
        when {
            s.dualMode -> if (s.dualVideo) toggleDualRecording() else captureDualPhoto()
            s.mode == CaptureMode.VIDEO && s.prompterMode -> prompterShutter()
            s.mode == CaptureMode.VIDEO -> toggleRecording()
            else -> capturePhoto()
        }
    }

    private fun memoryBudget(): Long {
        val am = getApplication<Application>().getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return min((mi.availMem * 0.5).toLong(), 1_800_000_000L).coerceAtLeast(150_000_000L)
    }

    private fun capturePhoto() {
        if (_state.value.capture != null) return
        poseAnalyzer.paused = true
        val s = settings
        val bind = _state.value.bind
        val look = resolvedLook(s)
        val res = session.photoResolution ?: Size(4000, 3000)
        val vendorActive = (bind?.vendorMode ?: VendorMode.NONE) != VendorMode.NONE
        val requested = CapturePlanner.requestedFrames(
            multiFrame = s.multiFrame,
            vendorModeActive = vendorActive,
            flashEnabled = s.flashMode != ImageCapture.FLASH_MODE_OFF,
            manualFrames = s.frames,
            lookSuggested = Looks.byId(s.lookId).suggestedFrames,
        )
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
                    if (i == 0) sound.play(android.media.MediaActionSound.SHUTTER_CLICK)
                    decodes += async(Dispatchers.Default) {
                        image.use { PhotoPipeline.decode(it, capturePlan.decodeSampleSize, keepJpeg = i == 0) }
                            .also { decoded += it }
                    }
                }
                if (n > 1) applyCaptureOptions()
                _state.update { it.copy(capture = CaptureProgress("Procesando…", 0.3f)) }
                val frames = decodes.awaitAll()
                decoded.clear() // a partir de aquí el pipeline se encarga de liberarlos
                val app = getApplication<Application>()
                // Se procesa en el ámbito del proceso: si el usuario sale de la app, la foto
                // se termina de guardar igual.
                val result = com.lumacam.photo.ProcessingScope.async {
                    PhotoPipeline.process(app, frames, look, s.jpegQuality, capturePlan.note) { text, p ->
                        _state.update { it.copy(capture = CaptureProgress(text, 0.3f + 0.7f * p)) }
                    }
                }.await()
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
                poseAnalyzer.paused = false
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
                is VideoRecordEvent.Start -> {
                    sound.play(android.media.MediaActionSound.START_VIDEO_RECORDING)
                    // En PRESENTAR el texto empieza a subir con la grabación.
                    _state.update {
                        it.copy(
                            recording = true,
                            recordingSeconds = 0,
                            prompter = if (it.prompterActive) it.prompter.copy(running = true) else it.prompter,
                        )
                    }
                }
                is VideoRecordEvent.Status -> _state.update {
                    it.copy(recordingSeconds = event.recordingStats.recordedDurationNanos / 1_000_000_000L)
                }
                is VideoRecordEvent.Finalize -> {
                    sound.play(android.media.MediaActionSound.STOP_VIDEO_RECORDING)
                    _state.update { it.copy(recording = false, prompter = it.prompter.copy(running = false)) }
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

    /** Miniatura de la última foto de LumaCam (la app puede leer las fotos que creó). */
    private fun loadLastCapture() {
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    resolver.query(
                        collection,
                        arrayOf(android.provider.MediaStore.MediaColumns._ID),
                        "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                        arrayOf("Pictures/LumaCam%"),
                        "${android.provider.MediaStore.MediaColumns.DATE_ADDED} DESC",
                    )?.use { c ->
                        if (!c.moveToFirst()) return@use null
                        val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(0))
                        uri to resolver.loadThumbnail(uri, Size(240, 240), null)
                    }
                }.getOrNull()
            }
            if (found != null && _state.value.lastUri == null) {
                _state.update { it.copy(lastUri = found.first, lastThumbnail = found.second, lastIsVideo = false) }
            }
        }
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

    fun runBenchmark() {
        if (_state.value.benchmarkRunning || _state.value.capture != null) return
        _state.update { it.copy(benchmarkRunning = true, benchmark = "Midiendo… (unos segundos)") }
        viewModelScope.launch {
            val text = try {
                com.lumacam.photo.Benchmark.run(resolvedLook())
            } catch (t: OutOfMemoryError) {
                "Sin memoria suficiente para la prueba de 12 MP"
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                "Error en la prueba: ${t.message}"
            }
            _state.update { it.copy(benchmarkRunning = false, benchmark = text) }
        }
    }

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
        val pm = getApplication<Application>().packageManager
        val concurrent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)
        appendLine(
            "Dos cámaras a la vez (modo DUAL): " +
                if (concurrent && concurrentCombos > 0) "sí ($concurrentCombos combinaciones)" else "no; la foto doble se hace en dos pasos",
        )
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
        countdownJob?.cancel()
        dual?.release()
        dual = null
        orientation.stop()
        poseAnalyzer.close()
        sound.release()
        removeZoomObserver()
        session.release()
        captureExecutor.shutdown()
        super.onCleared()
    }
}
