package com.lumacam.dual

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Graba el lienzo del modo DUAL (lo que dibuja [DualRenderer]) con audio del micrófono, en
 * Películas/LumaCam. MediaRecorder se encarga de codificar y de juntar audio y video.
 */
class DualRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var fd: ParcelFileDescriptor? = null
    private var uri: Uri? = null

    var width = 0
        private set
    var height = 0
        private set

    /**
     * Prepara y arranca la grabación; devuelve la superficie donde hay que dibujar. Prueba
     * 1080x1920 y, si el codificador no puede, 720x1280. [audio] sólo si hay permiso de micrófono.
     */
    @SuppressLint("MissingPermission")
    fun start(audio: Boolean): Surface {
        check(recorder == null) { "Ya se está grabando" }
        val resolver = context.contentResolver
        val name = "LumaCam_DUAL_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/LumaCam")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val target = resolver.insert(collection, values) ?: throw IllegalStateException("No se pudo crear el video en la galería")
        try {
            val pfd = resolver.openFileDescriptor(target, "w") ?: throw IllegalStateException("No se pudo abrir el video")
            var lastError: Throwable? = null
            for ((w, h) in SIZES) {
                val mr = newRecorder()
                try {
                    if (audio) mr.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                    mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    mr.setOutputFile(pfd.fileDescriptor)
                    mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    mr.setVideoSize(w, h)
                    mr.setVideoFrameRate(30)
                    mr.setVideoEncodingBitRate(w * h * 5)
                    if (audio) {
                        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        mr.setAudioSamplingRate(48_000)
                        mr.setAudioEncodingBitRate(128_000)
                        mr.setAudioChannels(1)
                    }
                    mr.setOrientationHint(0)
                    mr.prepare()
                    val surface = mr.surface
                    mr.start()
                    recorder = mr
                    fd = pfd
                    uri = target
                    width = w
                    height = h
                    return surface
                } catch (t: Throwable) {
                    Log.w(TAG, "No se pudo grabar a ${w}x$h", t)
                    lastError = t
                    mr.release()
                }
            }
            pfd.close()
            throw lastError ?: IllegalStateException("No se pudo iniciar la grabación")
        } catch (t: Throwable) {
            resolver.delete(target, null, null)
            throw t
        }
    }

    /** Termina la grabación. Devuelve el video guardado, o null si no llegó a grabarse nada. */
    fun stop(): Uri? {
        val mr = recorder ?: return null
        recorder = null
        var ok = true
        try {
            mr.stop()
        } catch (t: RuntimeException) {
            // Pasa si se para antes de recibir ningún cuadro.
            Log.w(TAG, "La grabación no tenía datos", t)
            ok = false
        }
        mr.release()
        runCatching { fd?.close() }
        fd = null
        val target = uri ?: return null
        uri = null
        val resolver = context.contentResolver
        if (!ok) {
            resolver.delete(target, null, null)
            return null
        }
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(target, values, null, null)
        return target
    }

    val isRecording: Boolean get() = recorder != null

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()

    companion object {
        private const val TAG = "DualRecorder"
        private val SIZES = listOf(1080 to 1920, 720 to 1280)
    }
}
