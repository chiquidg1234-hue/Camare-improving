package com.lumacam.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Enviar la app": comparte el APK instalado (Quick Share, Bluetooth, WhatsApp…). Quien lo recibe
 * lo abre desde la notificación o desde Archivos y lo instala, sin navegador ni internet.
 */
object ApkShare {
    const val APK_MIME = "application/vnd.android.package-archive"

    suspend fun share(context: Context) {
        val file = withContext(Dispatchers.IO) { copyInstalledApk(context) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = APK_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Enviar LumaCam"))
    }

    /** Copia el APK instalado a la caché (FileProvider no puede servir el de /data/app). */
    private fun copyInstalledApk(context: Context): File {
        val src = File(context.applicationInfo.sourceDir)
        val dir = File(context.cacheDir, "compartir").apply { mkdirs() }
        val out = File(dir, "LumaCam.apk")
        if (!out.exists() || out.length() != src.length() || out.lastModified() < src.lastModified()) {
            src.copyTo(out, overwrite = true)
        }
        return out
    }
}
