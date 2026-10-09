package com.lumacam.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumacam.update.AppUpdater
import com.lumacam.update.UpdateState
import kotlin.math.roundToInt

/** Aviso de actualización dentro del visor (sólo cuando hay algo que hacer). */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by AppUpdater.state.collectAsStateWithLifecycle()
    val s = state
    val text: String
    var action: String? = null
    var onAction: () -> Unit = {}
    var progress: Float? = null
    var showProgress = false
    var closable = true
    when (s) {
        is UpdateState.Available -> {
            text = "Nueva versión ${s.info.versionName}"
            action = "Actualizar"
            onAction = { AppUpdater.install(context, s.info) }
        }
        is UpdateState.NeedsPermission -> {
            text = "Para actualizar, permite que LumaCam instale apps (sólo una vez)."
            action = "Permitir"
            onAction = {
                try {
                    context.startActivity(AppUpdater.permissionIntent(context))
                } catch (_: ActivityNotFoundException) {
                }
            }
        }
        is UpdateState.Downloading -> {
            val p = s.progress
            text = if (p != null) "Descargando ${s.info.versionName}… ${(p * 100).roundToInt()}%" else "Descargando ${s.info.versionName}…"
            progress = p
            showProgress = true
            closable = false
        }
        is UpdateState.Installing -> {
            text = "Instalando ${s.info.versionName}…"
            showProgress = true
            closable = false
        }
        is UpdateState.Confirm -> {
            text = "Toca «Actualizar» en el aviso de Android."
            action = "Abrir"
            onAction = { AppUpdater.openConfirmation(context) }
        }
        is UpdateState.Failed -> {
            val info = s.info ?: return
            text = s.message
            action = "Reintentar"
            onAction = { AppUpdater.install(context, info) }
        }
        else -> return
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xE6102A1C))
            .padding(start = 10.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF4CE07A), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            action?.let { TextButton(onClick = onAction) { Text(it, color = Color(0xFF4CE07A), fontSize = 13.sp) } }
            if (closable) {
                IconButton(onClick = AppUpdater::dismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar aviso", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (showProgress) {
            val p = progress
            if (p != null) {
                LinearProgressIndicator(progress = { p }, color = Color(0xFF4CE07A), modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
            } else {
                LinearProgressIndicator(color = Color(0xFF4CE07A), modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
