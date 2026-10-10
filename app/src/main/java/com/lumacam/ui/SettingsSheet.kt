package com.lumacam.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumacam.BuildConfig
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumacam.camera.CaptureMode
import com.lumacam.camera.ResolutionMode
import com.lumacam.camera.VendorMode
import com.lumacam.camera.VideoQualityOption
import com.lumacam.core.look.Adjustments
import com.lumacam.update.AppUpdater
import com.lumacam.update.UpdateState
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(state: UiState, vm: CameraViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val s = state.settings
    val a = s.adjustments
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            // ---- Actualizaciones ----
            UpdateSection()

            // ---- Exposición ----
            SectionTitle("Exposición")
            val e = state.exposure
            if (e.supported && e.max > e.min) {
                val ev = e.index * e.step
                LabeledSlider(
                    label = "Compensación ${String.format(Locale.US, "%+.1f", ev)} EV",
                    value = e.index.toFloat(),
                    range = e.min.toFloat()..e.max.toFloat(),
                    steps = (e.max - e.min - 1).coerceAtLeast(0),
                ) { vm.setExposureIndex(it.roundToInt()) }
            } else {
                Hint("Este teléfono no permite compensar la exposición.")
            }
            SwitchRow("Bloquear exposición (AE-L)", state.aeLock) { vm.toggleAeLock() }
            SwitchRow("Bloquear balance de blancos (AWB-L)", state.awbLock) { vm.toggleAwbLock() }
            Hint("Toca la imagen para enfocar y medir la luz en ese punto. Mantén pulsado para ver la imagen original sin look.")
            SwitchRow("Cuadrícula (regla de tercios)", s.grid, vm::setGrid)
            SwitchRow("Look en la vista previa y el video (GPU)", s.gpuPreview, vm::setGpuPreview)
            if (!s.gpuPreview) Hint("Apagado: la vista previa y el video van sin look; la foto sí lo lleva.")

            // ---- Imagen ----
            SectionTitle("Ajustes de imagen (se suman al look)")
            AdjSlider("Temperatura", a.temperature, -1f..1f) { vm.setAdjustments(a.copy(temperature = it)) }
            AdjSlider("Tinte", a.tint, -1f..1f) { vm.setAdjustments(a.copy(tint = it)) }
            AdjSlider("Contraste", a.contrast, -1f..1f) { vm.setAdjustments(a.copy(contrast = it)) }
            AdjSlider("Sombras", a.shadows, -1f..1f) { vm.setAdjustments(a.copy(shadows = it)) }
            AdjSlider("Luces", a.highlights, -1f..1f) { vm.setAdjustments(a.copy(highlights = it)) }
            AdjSlider("Saturación", a.saturation, -1f..1f) { vm.setAdjustments(a.copy(saturation = it)) }
            AdjSlider("Vibrancia", a.vibrance, -1f..1f) { vm.setAdjustments(a.copy(vibrance = it)) }
            AdjSlider("Contraste local", a.localContrast, 0f..1f) { vm.setAdjustments(a.copy(localContrast = it)) }
            AdjSlider("Nitidez", a.sharpness, 0f..1f) { vm.setAdjustments(a.copy(sharpness = it)) }
            AdjSlider("Menos ruido de color (sólo foto)", a.noiseReduction, 0f..1f) { vm.setAdjustments(a.copy(noiseReduction = it)) }
            if (a != Adjustments.NEUTRAL) {
                TextButton(onClick = vm::resetAdjustments) { Text("Restablecer ajustes") }
            }

            // ---- Foto ----
            SectionTitle("Foto")
            SwitchRow("Multi-frame (promediar varias fotos para bajar ruido)", s.multiFrame, vm::setMultiFrame)
            if (s.multiFrame) {
                ChoiceRow(
                    label = "Frames",
                    options = listOf(0 to "Auto", 2 to "2", 4 to "4", 6 to "6", 8 to "8"),
                    selected = s.frames,
                    onSelect = vm::setFrames,
                )
                Hint("Auto: 4 frames, 8 en Nocturno. Mantén el teléfono quieto mientras captura.")
            }
            ChoiceRow(
                label = "Resolución",
                options = listOf(ResolutionMode.STANDARD to "Estándar", ResolutionMode.MAXIMUM to "Máxima"),
                selected = s.resolutionMode,
                onSelect = vm::setResolutionMode,
            )
            state.bind?.photoResolution?.let {
                Hint("Resolución actual: ${it.width}x${it.height} (${String.format(Locale.US, "%.1f", it.width * it.height / 1e6)} MP). \"Máxima\" incluye modos lentos (p. ej. 50 MP sin agrupar píxeles) si el teléfono los ofrece; ahí no se usa multi-frame.")
            }
            LabeledSlider(
                label = "Calidad JPEG ${s.jpegQuality}",
                value = s.jpegQuality.toFloat(),
                range = 85f..100f,
                steps = 14,
            ) { vm.setJpegQuality(it.roundToInt()) }
            SwitchRow("Exposición larga en look Nocturno", s.longExposureNight, vm::setLongExposureNight)

            if (state.vendorModes.isNotEmpty()) {
                ChoiceRow(
                    label = "Modo del fabricante",
                    options = (listOf(VendorMode.NONE) + state.vendorModes).map { it to it.label },
                    selected = s.vendorMode,
                    onSelect = vm::setVendorMode,
                )
                Hint("Procesado propio del teléfono (extensiones de CameraX). Con él activo se usa 1 frame y puede que la vista previa no muestre el look.")
            }
            if (state.sceneHdrAvailable) {
                SwitchRow("HDR (modo de escena de Camera2)", s.sceneHdr, vm::setSceneHdr)
            }
            if (state.vendorModes.isEmpty() && !state.sceneHdrAvailable) {
                Hint("Este teléfono no ofrece HDR a otras apps. Usa multi-frame + el look Nocturno o baja \"Luces\" para recuperar cielos.")
            }

            // ---- Video ----
            SectionTitle("Video")
            ChoiceRow(
                label = "Calidad",
                options = VideoQualityOption.entries.map { it to it.label },
                selected = s.videoQuality,
                onSelect = vm::setVideoQuality,
            )
            SwitchRow("Estabilización", s.stabilization, vm::setStabilization)
            if (s.mode == CaptureMode.VIDEO) {
                state.bind?.let { Hint("Estado: ${vm.stabText(it.stabilization)}") }
            }

            // ---- PRESENTAR ----
            SectionTitle("Modo PRESENTAR (teleprompter)")
            SwitchRow("Cuenta atrás de 3 s antes de grabar", s.prompterCountdown, vm::setPrompterCountdown)
            SwitchRow("Texto en espejo (para teleprompter de cristal)", s.prompterMirror, vm::setPrompterMirror)
            Hint("El texto empieza a subir al empezar a grabar. Tócalo para pausar; arrástralo para adelantar o volver.")

            // ---- DUAL ----
            SectionTitle("Modo DUAL (dos cámaras)")
            Hint(
                when (state.dual.concurrent) {
                    true -> "Tu teléfono usa las dos cámaras a la vez."
                    false -> "Tu teléfono no deja usar las dos cámaras a la vez: la foto doble se hace en dos pasos seguidos y el video graba una cámara (cámbiala mientras grabas)."
                    null -> "Entra en DUAL para comprobar si tu teléfono puede usar las dos cámaras a la vez."
                },
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Versión instalada y botón para buscar e instalar la última desde GitHub. */
@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    val update by AppUpdater.state.collectAsStateWithLifecycle()
    SectionTitle("Actualizaciones")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Versión instalada ${BuildConfig.VERSION_NAME}", fontSize = 14.sp)
            val status = when (val u = update) {
                UpdateState.Idle -> "Toca para comprobar si hay una versión nueva."
                UpdateState.Checking -> "Buscando…"
                UpdateState.UpToDate -> "Tienes la última versión."
                is UpdateState.Available -> "Hay una versión nueva: ${u.info.versionName}"
                is UpdateState.NeedsPermission -> "Falta permitir que LumaCam instale apps (aviso arriba del visor)."
                is UpdateState.Downloading -> "Descargando ${u.info.versionName}… ${u.progress?.let { "${(it * 100).roundToInt()}%" } ?: ""}"
                is UpdateState.Confirm -> "Toca «Actualizar» en el aviso de Android."
                is UpdateState.Installing -> "Instalando ${u.info.versionName}…"
                is UpdateState.Failed -> u.message
            }
            Text(status, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
        when (val u = update) {
            is UpdateState.Available -> Button(onClick = { AppUpdater.install(context, u.info) }) { Text("Actualizar") }
            is UpdateState.Failed -> {
                val info = u.info
                if (info != null) {
                    Button(onClick = { AppUpdater.install(context, info) }) { Text("Reintentar") }
                } else {
                    TextButton(onClick = { AppUpdater.check(context, force = true) }) { Text("Buscar") }
                }
            }
            UpdateState.Idle, UpdateState.UpToDate -> TextButton(onClick = { AppUpdater.check(context, force = true) }) { Text("Buscar") }
            else -> Unit
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp)
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun AdjSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val shown = (value * 100).roundToInt()
    LabeledSlider("$label ${if (shown > 0 && range.start < 0f) "+$shown" else "$shown"}", value, range) { v ->
        // Pequeño "imán" en 0 para volver fácil a neutro.
        onChange(if (kotlin.math.abs(v) < 0.03f) 0f else v)
    }
}

@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, text) in options) {
                ToggleChip(text, value == selected) { onSelect(value) }
            }
        }
    }
}

@Composable
fun DiagnosticsDialog(text: String, benchmarkRunning: Boolean, onBenchmark: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnóstico de la cámara") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                TextButton(onClick = onBenchmark, enabled = !benchmarkRunning) {
                    Text(if (benchmarkRunning) "Midiendo rendimiento…" else "Medir rendimiento del procesado")
                }
                Text(text.ifEmpty { "Abriendo la cámara…" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Diagnóstico LumaCam", text))
                Toast.makeText(context, "Copiado", Toast.LENGTH_SHORT).show()
            }) { Text("Copiar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
