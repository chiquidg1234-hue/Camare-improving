package com.lumacam.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.lumacam.BuildConfig
import com.lumacam.update.ApkShare
import com.lumacam.update.AppUpdater
import com.lumacam.update.UpdateState
import kotlinx.coroutines.launch

/** Código QR de un texto (negro sobre blanco). */
fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h) { i -> if (matrix.get(i % w, i / w)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}

/**
 * "Invitar": QR de la página de descarga (con los pasos para que Chrome no se quede trabado),
 * envío directo del APK por Quick Share / Bluetooth / WhatsApp y comprobación de actualizaciones.
 */
@Composable
fun InviteDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val url = BuildConfig.INVITE_URL
    val qr = remember(url) { runCatching { qrBitmap(url, 720) }.getOrNull() }
    val update by AppUpdater.state.collectAsStateWithLifecycle()
    var sending by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invitar a LumaCam") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (qr != null) {
                    Image(
                        qr.asImageBitmap(),
                        contentDescription = "Código QR de la página de descarga de LumaCam",
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                            .padding(6.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Que escanee el código con su Android y siga los pasos de la página (ábrela en Chrome antes de descargar).",
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (sending) return@Button
                        sending = true
                        scope.launch {
                            try {
                                ApkShare.share(context)
                            } catch (e: Exception) {
                                Log.w("InviteDialog", "No se pudo enviar el APK", e)
                            } finally {
                                sending = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (sending) "Preparando…" else "Enviar la app directamente") }
                Text(
                    "Por Quick Share, Bluetooth o WhatsApp. Quien la recibe la abre y toca Instalar: sin navegador.",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "LumaCam")
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Te invito a probar LumaCam, una cámara con looks y modo poses para Android. " +
                                    "Abre este enlace en Chrome y sigue los pasos: $url",
                            )
                        }
                        try {
                            context.startActivity(Intent.createChooser(send, "Compartir LumaCam"))
                        } catch (_: ActivityNotFoundException) {
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Compartir enlace") }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Versión instalada ${BuildConfig.VERSION_NAME}",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f),
                )
                when (val s = update) {
                    UpdateState.Checking -> Text("Buscando actualización…", fontSize = 12.sp)
                    UpdateState.UpToDate -> Text("Tienes la última versión.", fontSize = 12.sp, color = Color(0xFF4CE07A))
                    is UpdateState.Available -> TextButton(onClick = { AppUpdater.install(context, s.info) }) {
                        Text("Actualizar a ${s.info.versionName}")
                    }
                    is UpdateState.Failed -> Text(s.message, fontSize = 12.sp, color = Amber, textAlign = TextAlign.Center)
                    is UpdateState.Downloading, is UpdateState.Installing, is UpdateState.Confirm, is UpdateState.NeedsPermission ->
                        Text("Actualización en curso (mira el aviso de arriba).", fontSize = 12.sp)
                    UpdateState.Idle -> TextButton(onClick = { AppUpdater.check(context, force = true) }) {
                        Text("Buscar actualización")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
