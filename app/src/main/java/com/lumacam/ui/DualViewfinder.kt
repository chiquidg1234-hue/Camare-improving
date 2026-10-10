package com.lumacam.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lumacam.core.dual.DualGeometry
import com.lumacam.core.dual.DualLayout
import com.lumacam.core.dual.PipCorner
import com.lumacam.dual.DualRenderer
import kotlin.math.abs

/**
 * Visor del modo DUAL: muestra el lienzo de [DualRenderer]. Tocar la otra cámara (ventanita o
 * mitad de abajo) las intercambia; pellizcar hace zoom en la cámara grande.
 */
@Composable
fun DualViewfinder(
    renderer: DualRenderer,
    layout: DualLayout,
    corner: PipCorner,
    single: Boolean,
    onSwap: () -> Unit,
    onPinch: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        key(renderer) {
            AndroidView(
                factory = { ctx -> TextureView(ctx).apply { surfaceTextureListener = ScreenListener(renderer) } },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(layout, corner, single) {
                    detectTapGestures { o ->
                        val r = DualGeometry.regions(layout, size.width, size.height, corner, single)
                        if (DualGeometry.hitsSecond(r, o.x / size.width, o.y / size.height)) onSwap()
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (abs(zoom - 1f) > 0.001f) onPinch(zoom)
                    }
                },
        )
    }
}

/** Fila de opciones del modo DUAL (encima de los looks). */
@Composable
fun DualControls(state: UiState, vm: CameraViewModel) {
    val s = state.settings
    val busy = state.recording || state.capture != null
    Row(
        Modifier.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Segmented(listOf("Foto" to !s.dualVideo, "Video" to s.dualVideo), enabled = !busy) { i -> vm.setDualVideo(i == 1) }
        Box(Modifier.padding(horizontal = 6.dp))
        // Por turnos en video se ve una sola cámara; en foto la distribución sí cuenta.
        if (state.dual.concurrent != false || !s.dualVideo) {
            Segmented(DualLayout.entries.map { it.label to (it == s.dualLayout) }) { i -> vm.setDualLayout(DualLayout.entries[i]) }
            if (s.dualLayout == DualLayout.PIP) {
                Box(Modifier.padding(horizontal = 3.dp))
                ToggleChip("Esquina", false, vm::cycleDualCorner)
            }
        } else {
            Text(
                "Una cámara a la vez",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Segmented(options: List<Pair<String, Boolean>>, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x33FFFFFF)),
    ) {
        options.forEachIndexed { i, (label, selected) ->
            Text(
                label,
                color = if (selected) Color.Black else Color.White.copy(alpha = if (enabled) 1f else 0.4f),
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Amber else Color.Transparent)
                    .clickable(enabled = enabled && !selected) { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** Entrega la superficie del TextureView al lienzo DUAL. */
private class ScreenListener(private val renderer: DualRenderer) : TextureView.SurfaceTextureListener {
    private var surface: Surface? = null

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val s = Surface(st)
        surface = s
        renderer.setScreenSurface(s)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        renderer.invalidate()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        renderer.setScreenSurface(null)
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
}
