package com.lumacam.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.lumacam.BuildConfig

/** Código QR de un texto (negro sobre blanco). */
fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h) { i -> if (matrix.get(i % w, i / w)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}

/** "Invitar": QR para que otra persona descargue e instale LumaCam, y botón para compartir el enlace. */
@Composable
fun InviteDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val url = BuildConfig.DOWNLOAD_URL
    val qr = remember(url) { runCatching { qrBitmap(url, 720) }.getOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invitar a LumaCam") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (qr != null) {
                    Image(
                        qr.asImageBitmap(),
                        contentDescription = "Código QR para descargar LumaCam",
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                            .padding(6.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Que la otra persona escanee el código con la cámara de su Android, descargue LumaCam.apk y la abra. " +
                        "Si Android lo pide, que permita \"Instalar apps desconocidas\".",
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(url, fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "LumaCam")
                    putExtra(Intent.EXTRA_TEXT, "Te invito a probar LumaCam, una cámara con looks y modo poses para Android: $url")
                }
                context.startActivity(Intent.createChooser(send, "Compartir LumaCam"))
            }) { Text("Compartir enlace") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
