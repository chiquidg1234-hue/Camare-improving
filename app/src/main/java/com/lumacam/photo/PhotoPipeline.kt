package com.lumacam.photo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import com.lumacam.core.image.FrameSink
import com.lumacam.core.image.FrameSource
import com.lumacam.core.look.ChromaDenoiser
import com.lumacam.core.look.LookProcessor
import com.lumacam.core.look.ResolvedLook
import com.lumacam.core.merge.BurstMerger
import com.lumacam.core.merge.ImageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * Ámbito del proceso para terminar de procesar y guardar fotos aunque se cierre la pantalla
 * (el ViewModel se destruye al salir con "atrás").
 */
val ProcessingScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)

/** Un frame decodificado listo para fusionar. */
class CapturedFrame(val bitmap: Bitmap, val rotationDegrees: Int, val jpegBytes: ByteArray?)

data class PhotoResult(
    val uri: Uri,
    val thumbnail: Bitmap?,
    val width: Int,
    val height: Int,
    val framesUsed: Int,
    val summary: String,
)

class BitmapFrameSource(private val bitmap: Bitmap) : FrameSource {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
    override fun readRows(y0: Int, rows: Int, dst: IntArray, dstOffset: Int) {
        bitmap.getPixels(dst, dstOffset, bitmap.width, 0, y0, bitmap.width, rows)
    }
}

class BitmapFrameSink(private val bitmap: Bitmap) : FrameSink {
    override fun writeRows(y0: Int, rows: Int, src: IntArray, srcOffset: Int) {
        synchronized(bitmap) {
            bitmap.setPixels(src, srcOffset, bitmap.width, 0, y0, bitmap.width, rows)
        }
    }
}

/**
 * Foto final: fusión multi-frame (opcional) → look en CPU (mismo pipeline que la vista previa)
 * → JPEG de alta calidad con EXIF → galería (Imágenes/LumaCam).
 */
object PhotoPipeline {

    /** Decodifica el JPEG de CameraX. Devuelve un bitmap mutable con el recorte aplicado. */
    fun decode(image: ImageProxy, sampleSize: Int, keepJpeg: Boolean): CapturedFrame {
        val rotation = image.imageInfo.rotationDegrees
        val crop = Rect(image.cropRect)
        val fullW = image.width
        val fullH = image.height
        var bytes: ByteArray? = null
        var bmp: Bitmap = if (image.format == ImageFormat.JPEG) {
            val buf = image.planes[0].buffer
            buf.rewind()
            val data = ByteArray(buf.remaining())
            buf.get(data)
            bytes = data
            val opts = BitmapFactory.Options().apply {
                inMutable = true
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(data, 0, data.size, opts)
                ?: throw IllegalStateException("No se pudo decodificar el JPEG")
        } else {
            image.toBitmap().copy(Bitmap.Config.ARGB_8888, true)
        }
        // Aplicar el recorte si CameraX no entregó la imagen completa.
        if (crop.width() > 0 && crop.height() > 0 && (crop.width() != fullW || crop.height() != fullH)) {
            val sx = bmp.width.toFloat() / fullW
            val sy = bmp.height.toFloat() / fullH
            val l = (crop.left * sx).toInt().coerceIn(0, bmp.width - 1)
            val t = (crop.top * sy).toInt().coerceIn(0, bmp.height - 1)
            val w = (crop.width() * sx).toInt().coerceIn(1, bmp.width - l)
            val h = (crop.height() * sy).toInt().coerceIn(1, bmp.height - t)
            val cropped = Bitmap.createBitmap(bmp, l, t, w, h).copy(Bitmap.Config.ARGB_8888, true)
            bmp.recycle()
            bmp = cropped
        }
        return CapturedFrame(bmp, rotation, if (keepJpeg) bytes else null)
    }

    suspend fun process(
        context: Context,
        framesIn: List<CapturedFrame>,
        look: ResolvedLook,
        jpegQuality: Int,
        note: String?,
        progress: (String, Float) -> Unit,
    ): PhotoResult = withContext(Dispatchers.Default) {
        require(framesIn.isNotEmpty())
        val first = framesIn[0]
        // Sólo se pueden fusionar frames del mismo tamaño.
        val frames = framesIn.filter { it.bitmap.width == first.bitmap.width && it.bitmap.height == first.bitmap.height }
        framesIn.filter { it !in frames }.forEach { it.bitmap.recycle() }

        val w = first.bitmap.width
        val h = first.bitmap.height
        var mergeInfo = ""
        val merged: Bitmap
        var framesUsed = 1
        if (frames.size > 1) {
            progress("Fusionando ${frames.size} frames…", 0f)
            val target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val result = BurstMerger().process(
                frames.map { BitmapFrameSource(it.bitmap) },
                BitmapFrameSink(target),
            ) { p -> progress("Fusionando ${frames.size} frames…", p * 0.6f) }
            framesUsed = result.usedIndices.size
            mergeInfo = "${result.usedIndices.size}/${frames.size} frames, ruido σ≈${"%.1f".format(Locale.US, result.noiseSigma)}"
            frames.forEach { it.bitmap.recycle() }
            merged = target
        } else {
            merged = first.bitmap
        }

        // Ruido medido en la imagen ya fusionada: con poco ruido se suaviza poco el color.
        val sigma = if (look.chromaDenoise > 0f) ImageStats.noiseSigma(BitmapFrameSource(merged)) else 0f
        val cdStrength = ChromaDenoiser.adaptiveStrength(look.chromaDenoise, sigma)
        val out = if (look.isNeutral) merged else {
            progress("Aplicando look…", 0.6f)
            val o = applyLook(merged, look, cdStrength) { p -> progress("Aplicando look…", 0.6f + 0.3f * p) }
            merged.recycle()
            o
        }

        progress("Guardando…", 0.92f)
        val rotation = first.rotationDegrees
        val summaryParts = listOfNotNull(
            look.describe(),
            mergeInfo.ifEmpty { null },
            if (cdStrength > 0.02f) "ruido de color −${(cdStrength * 100).toInt()}%" else null,
            note,
        )
        val summary = summaryParts.joinToString(" · ")
        val uri = save(context, out, jpegQuality, rotation, first.jpegBytes, summary)
        val thumb = thumbnail(out, rotation)
        out.recycle()
        PhotoResult(uri, thumb, w, h, framesUsed, summary)
    }

    /** Aplica el look por franjas en paralelo (memoria de Java acotada). */
    suspend fun applyLook(
        src: Bitmap,
        look: ResolvedLook,
        chromaStrength: Float = 0f,
        progress: (Float) -> Unit = {},
    ): Bitmap = coroutineScope {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val proc = LookProcessor(look)
        val cd = if (chromaStrength > 0.02f) ChromaDenoiser(chromaStrength, ChromaDenoiser.radiusFor(w, h)) else null
        val lookMargin = proc.requiredMargin(w, h)
        val margin = lookMargin + (cd?.requiredMargin ?: 0)
        val (strip, parallel) = stripPlan(w, margin, if (cd != null) 20 else 6)
        val strips = (h + strip - 1) / strip
        val sem = Semaphore(parallel)
        val done = AtomicInteger()
        (0 until strips).map { i ->
            async(Dispatchers.Default) {
                sem.withPermit {
                    val y0 = i * strip
                    val rows = min(strip, h - y0)
                    val s0 = max(0, y0 - margin)
                    val s1 = min(h, y0 + rows + margin)
                    var buf = IntArray((s1 - s0) * w)
                    src.getPixels(buf, 0, w, 0, s0, w, s1 - s0)
                    var bufY0 = s0
                    var bufRows = s1 - s0
                    if (cd != null) {
                        // Primero el ruido de color (necesita su propio margen), luego el look.
                        val a0 = max(0, y0 - lookMargin)
                        val a1 = min(h, y0 + rows + lookMargin)
                        val tmp = IntArray((a1 - a0) * w)
                        cd.processRows(buf, s0, s1 - s0, w, a0, a1 - a0, tmp)
                        buf = tmp
                        bufY0 = a0
                        bufRows = a1 - a0
                    }
                    val dst = IntArray(rows * w)
                    proc.processRows(buf, bufY0, bufRows, w, h, y0, rows, dst)
                    synchronized(out) { out.setPixels(dst, 0, w, 0, y0, w, rows) }
                    progress(done.incrementAndGet().toFloat() / strips)
                }
            }
        }.awaitAll()
        out
    }

    /**
     * Alto de franja y franjas simultáneas para no pasarse de la memoria de Java: cada franja
     * usa ~5 arrays de 4 bytes por píxel (entrada, luminancia, desenfoque…), y el margen del
     * desenfoque crece con la resolución (p. ej. 50 MP).
     */
    internal fun stripPlan(width: Int, margin: Int, arraysPerPixel: Int = 6): Pair<Int, Int> {
        val rt = Runtime.getRuntime()
        val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        val budget = (free * 0.5).toLong().coerceAtLeast(32L * 1024 * 1024)
        val bytesPerRow = width.toLong() * 4 * arraysPerPixel
        var parallel = min(4, max(1, rt.availableProcessors()))
        var strip = STRIP_ROWS
        fun cost() = (strip + 2L * margin) * bytesPerRow * parallel
        while (cost() > budget && parallel > 1) parallel--
        while (cost() > budget && strip > 16) strip /= 2
        return strip to parallel
    }

    private fun save(
        context: Context,
        bitmap: Bitmap,
        quality: Int,
        rotationDegrees: Int,
        sourceJpeg: ByteArray?,
        summary: String,
    ): Uri {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val tmp = File(context.cacheDir, "LumaCam_$stamp.jpg")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), it) }
        writeExif(tmp, rotationDegrees, sourceJpeg, summary, bitmap.width, bitmap.height)

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "LumaCam_$stamp.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LumaCam")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("No se pudo crear la foto en la galería")
        try {
            resolver.openOutputStream(uri)?.use { os -> tmp.inputStream().use { it.copyTo(os) } }
                ?: throw IllegalStateException("No se pudo escribir la foto")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        } finally {
            tmp.delete()
        }
        return uri
    }

    private val EXIF_TAGS = listOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_BRIGHTNESS_VALUE,
    )

    private fun writeExif(file: File, rotationDegrees: Int, sourceJpeg: ByteArray?, summary: String, w: Int, h: Int) {
        try {
            val exif = ExifInterface(file.absolutePath)
            if (sourceJpeg != null) {
                val src = ExifInterface(ByteArrayInputStream(sourceJpeg))
                for (tag in EXIF_TAGS) src.getAttribute(tag)?.let { exif.setAttribute(tag, it) }
            }
            if (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == null) {
                val now = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
                exif.setAttribute(ExifInterface.TAG_DATETIME, now)
            }
            if (exif.getAttribute(ExifInterface.TAG_MAKE) == null) exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            if (exif.getAttribute(ExifInterface.TAG_MODEL) == null) exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientationTag(rotationDegrees).toString())
            exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, w.toString())
            exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, h.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, w.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, h.toString())
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "LumaCam")
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, summary)
            exif.saveAttributes()
        } catch (_: Throwable) {
            // Sin EXIF la foto sigue siendo válida.
        }
    }

    fun orientationTag(rotationDegrees: Int): Int = when ((rotationDegrees % 360 + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private fun thumbnail(src: Bitmap, rotationDegrees: Int): Bitmap? = try {
        val scale = 240f / max(src.width, src.height)
        val m = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotationDegrees.toFloat())
        }
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    } catch (_: Throwable) {
        null
    }

    private const val STRIP_ROWS = 128
}
