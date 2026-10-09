package com.lumacam.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumacam.camera.CaptureMode
import com.lumacam.core.look.Looks
import com.lumacam.core.zoom.ZoomPlanner
import kotlin.math.abs

@Composable
fun CameraScreen(vm: CameraViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val cam = result[Manifest.permission.CAMERA] ?: granted(context, Manifest.permission.CAMERA)
        val mic = result[Manifest.permission.RECORD_AUDIO] ?: granted(context, Manifest.permission.RECORD_AUDIO)
        vm.onPermissions(cam, mic)
    }
    LaunchedEffect(Unit) {
        val cam = granted(context, Manifest.permission.CAMERA)
        val mic = granted(context, Manifest.permission.RECORD_AUDIO)
        if (cam && mic) vm.onPermissions(true, true)
        else launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!state.permissionsGranted) {
            PermissionPrompt {
                launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            }
        } else {
            CameraContent(vm, state)
        }
    }
}

private fun granted(context: android.content.Context, perm: String) =
    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("LumaCam necesita la cámara (y el micrófono para el audio de los videos).", textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text("Conceder permisos") }
    }
}

@Composable
private fun CameraContent(vm: CameraViewModel, state: UiState) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbar = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showInvite by rememberSaveable { mutableStateOf(false) }
    val dismissed = remember { mutableStateListOf<String>() }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    DisposableEffect(lifecycleOwner, previewView) {
        vm.attach(lifecycleOwner, previewView.surfaceProvider)
        onDispose { vm.detach() }
    }

    // Pantalla encendida mientras se graba.
    val view = LocalView.current
    DisposableEffect(state.recording) {
        view.keepScreenOn = state.recording
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.consumeMessage()
    }

    val settings = state.settings
    val aspect = if (settings.mode == CaptureMode.PHOTO) 3f / 4f else 9f / 16f

    Column(Modifier.fillMaxSize()) {
        TopBar(
            state = state,
            onFlash = vm::cycleFlash,
            onAeLock = vm::toggleAeLock,
            onAwbLock = vm::toggleAwbLock,
            onInfo = { showDiagnostics = true },
            onSettings = { showSettings = true },
            onInvite = { showInvite = true },
        )
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect),
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            if (settings.grid && !state.poseActive) GridOverlay()
            ViewfinderGestures(vm, previewView)
            FocusRing(state.focus)
            if (state.poseActive) {
                PoseOverlay(
                    pose = state.pose,
                    autoShot = settings.poseAutoShot,
                    detectionWanted = settings.poseDetection,
                    onPrev = { vm.nextPose(-1) },
                    onNext = { vm.nextPose(1) },
                    onToggleDetection = { vm.setPoseDetection(!settings.poseDetection) },
                    onToggleAutoShot = { vm.setPoseAutoShot(!settings.poseAutoShot) },
                )
            }
            if (state.bypass) {
                Badge("ORIGINAL", Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            }
            if (state.recording) {
                Badge(
                    "● " + vm.formatSeconds(state.recordingSeconds),
                    Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    background = Color(0xCCD32F2F),
                )
            }
            Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                UpdateBanner(Modifier.padding(bottom = 6.dp))
                state.warnings.filter { it !in dismissed }.forEach { w ->
                    WarningBanner(w) { dismissed += w }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Text(
                if (state.bind?.effectActive == true) "${state.previewFps} fps" else "sin look en vista previa",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
            )
            state.capture?.let { CaptureOverlay(it, Modifier.align(Alignment.Center)) }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.Bottom,
    ) {
        BottomControls(
            state = state,
            vm = vm,
            onOpenLast = {
                val uri = state.lastUri ?: return@BottomControls
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, if (state.lastIsVideo) "video/*" else "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                }
            },
        )
    }

    SnackbarHost(snackbar, Modifier.fillMaxSize().padding(bottom = 220.dp), snackbar = { data ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Text(
                data.visuals.message,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xE0202428))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    })

    if (showSettings) SettingsSheet(state, vm) { showSettings = false }
    if (showInvite) InviteDialog { showInvite = false }
    if (showDiagnostics) {
        val text = listOfNotNull(state.benchmark, state.diagnostics).joinToString("\n\n")
        DiagnosticsDialog(text, state.benchmarkRunning, onBenchmark = vm::runBenchmark) { showDiagnostics = false }
    }
}

@Composable
private fun ViewfinderGestures(vm: CameraViewModel, previewView: PreviewView) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (abs(zoom - 1f) > 0.001f) vm.pinchZoom(zoom)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { o ->
                        val point = previewView.meteringPointFactory.createPoint(o.x, o.y)
                        vm.focus(point, o.x, o.y)
                    },
                    onLongPress = { vm.setBypass(true) },
                    onPress = {
                        tryAwaitRelease()
                        vm.setBypass(false)
                    },
                )
            },
    )
}

@Composable
private fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val c = Color.White.copy(alpha = 0.22f)
        val stroke = 1.dp.toPx()
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(c, Offset(x, 0f), Offset(x, size.height), stroke)
            drawLine(c, Offset(0f, y), Offset(size.width, y), stroke)
        }
    }
}

@Composable
private fun FocusRing(focus: FocusIndicator?) {
    if (focus == null) return
    val alpha = remember(focus.id) { Animatable(1f) }
    LaunchedEffect(focus.id) {
        alpha.snapTo(1f)
        alpha.animateTo(0f, tween(durationMillis = 1400))
    }
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(
            color = Amber.copy(alpha = alpha.value),
            radius = 36.dp.toPx(),
            center = Offset(focus.x, focus.y),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier, background: Color = Scrim) {
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun WarningBanner(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xE6332A10))
            .padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = Amber, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Cerrar aviso", tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun CaptureOverlay(p: CaptureProgress, modifier: Modifier) {
    Column(
        modifier
            .width(240.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xD0101215))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(p.text, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(progress = { p.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun TopBar(
    state: UiState,
    onFlash: () -> Unit,
    onAeLock: () -> Unit,
    onAwbLock: () -> Unit,
    onInfo: () -> Unit,
    onSettings: () -> Unit,
    onInvite: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.hasFlash && state.settings.mode == CaptureMode.PHOTO) {
            val label = when (state.settings.flashMode) {
                ImageCapture.FLASH_MODE_AUTO -> "Flash A"
                ImageCapture.FLASH_MODE_ON -> "Flash ON"
                else -> "Flash OFF"
            }
            ToggleChip(label, state.settings.flashMode != ImageCapture.FLASH_MODE_OFF, onFlash)
        }
        ToggleChip("AE-L", state.aeLock, onAeLock)
        ToggleChip("AWB-L", state.awbLock, onAwbLock)
        val hdrOn = state.bind?.vendorMode?.let { it.name != "NONE" } == true ||
            (state.settings.sceneHdr && state.sceneHdrAvailable)
        if (hdrOn) ToggleChip(state.bind?.vendorMode?.takeIf { it.name != "NONE" }?.label ?: "HDR", true) { onSettings() }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onInvite) { Icon(Icons.Default.Share, contentDescription = "Invitar (QR para instalar)", tint = Color.White) }
        IconButton(onClick = onInfo) { Icon(Icons.Default.Info, contentDescription = "Diagnóstico", tint = Color.White) }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Ajustes", tint = Color.White) }
    }
}

@Composable
fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Color.Black else Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Amber else Color(0x33FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun BottomControls(state: UiState, vm: CameraViewModel, onOpenLast: () -> Unit) {
    val s = state.settings
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xB3000000))
            .padding(top = 8.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Zoom
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (stop in state.zoomStops) {
                val selected = stop.cameraId == state.currentCameraId && abs(state.zoomRatio - stop.zoomRatio) < 0.05f
                ZoomButton(stop.label, selected) { vm.selectZoomStop(stop) }
            }
            val atStop = state.zoomStops.any { it.cameraId == state.currentCameraId && abs(state.zoomRatio - it.zoomRatio) < 0.05f }
            if (!atStop && !state.onUltraWide) {
                Text("${ZoomPlanner.fmt(state.zoomRatio)}x", color = Amber, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(8.dp))

        // Looks
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 12.dp)) {
            items(Looks.ALL, key = { it.id.name }) { look ->
                ToggleChip(look.displayName, look.id == s.lookId) { vm.selectLook(look.id) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Intensidad ${(s.intensity * 100).toInt()}%", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(110.dp))
            androidx.compose.material3.Slider(
                value = s.intensity,
                onValueChange = vm::setIntensity,
                modifier = Modifier.weight(1f),
            )
        }

        // Disparador
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x33FFFFFF))
                    .clickable(enabled = state.lastUri != null, onClick = onOpenLast),
                contentAlignment = Alignment.Center,
            ) {
                state.lastThumbnail?.let {
                    Image(it.asImageBitmap(), contentDescription = "Última captura", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            val haptics = LocalHapticFeedback.current
            ShutterButton(
                mode = s.mode,
                recording = state.recording,
                busy = state.capture != null,
                enabled = state.cameraReady,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.onShutter()
                },
            )
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                if (state.hasFront) {
                    IconButton(onClick = vm::switchFacing, enabled = !state.recording && state.capture == null) {
                        Icon(Icons.Default.Refresh, contentDescription = "Cambiar cámara", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            ModeLabel("FOTO", s.mode == CaptureMode.PHOTO && !s.poseMode) { vm.selectMode(CaptureMode.PHOTO, pose = false) }
            ModeLabel("POSES", s.mode == CaptureMode.PHOTO && s.poseMode) { vm.selectMode(CaptureMode.PHOTO, pose = true) }
            ModeLabel("VIDEO", s.mode == CaptureMode.VIDEO) { vm.selectMode(CaptureMode.VIDEO, pose = false) }
        }
    }
}

@Composable
private fun ZoomButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (selected) Color(0x55FFA94D) else Color(0x33FFFFFF))
            .border(1.dp, if (selected) Amber else Color.Transparent, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Amber else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ModeLabel(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Amber else Color.White.copy(alpha = 0.7f),
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        fontSize = 14.sp,
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp),
    )
}

@Composable
private fun ShutterButton(mode: CaptureMode, recording: Boolean, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val inner = when {
        mode == CaptureMode.VIDEO && recording -> Color(0xFFD32F2F)
        mode == CaptureMode.VIDEO -> Color(0xFFE53935)
        busy -> Color.Gray
        else -> Color.White
    }
    Box(
        Modifier
            .size(78.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .clip(if (recording) RoundedCornerShape(10.dp) else CircleShape)
            .background(if (enabled) inner else Color.DarkGray)
            .clickable(enabled = enabled && !busy, onClick = onClick),
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = Amber,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}
