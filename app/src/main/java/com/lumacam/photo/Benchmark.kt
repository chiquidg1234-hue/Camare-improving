package com.lumacam.photo

import android.graphics.Bitmap
import com.lumacam.core.color.ColorMath
import com.lumacam.core.look.ResolvedLook
import com.lumacam.core.merge.BurstMerger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.sin
import kotlin.random.Random

/**
 * Mide en el teléfono el tiempo del procesado de la foto con imágenes sintéticas de 12 MP:
 * fusión de 4 frames, look en CPU y codificación JPEG. Sirve para ajustar la app al hardware.
 */
object Benchmark {
    suspend fun run(look: ResolvedLook, width: Int = 4000, height: Int = 3000, frames: Int = 4): String =
        withContext(Dispatchers.Default) {
            val bitmaps = ArrayList<Bitmap>()
            var out: Bitmap? = null
            var looked: Bitmap? = null
            try {
                val tGen = System.nanoTime()
                for (k in 0 until frames) bitmaps += synthetic(width, height, dx = k * 3, dy = -k * 2, seed = k)
                val gen = secs(tGen)

                val t0 = System.nanoTime()
                val merged = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                out = merged
                val res = BurstMerger().process(bitmaps.map { BitmapFrameSource(it) }, BitmapFrameSink(merged))
                val merge = secs(t0)
                bitmaps.forEach { it.recycle() }
                bitmaps.clear()

                val t1 = System.nanoTime()
                val l = PhotoPipeline.applyLook(merged, look, look.chromaDenoise)
                looked = l
                val lookTime = secs(t1)

                val t2 = System.nanoTime()
                val bos = ByteArrayOutputStream()
                l.compress(Bitmap.CompressFormat.JPEG, 95, bos)
                val jpeg = secs(t2)

                String.format(
                    Locale.US,
                    "Rendimiento (%d×%.1f MP): fusión %.2f s (%d frames usados), look %.2f s, JPEG %.2f s (%.1f MB). Generación %.2f s. Núcleos: %d",
                    frames, width * height / 1e6, merge, res.usedIndices.size, lookTime, jpeg,
                    bos.size() / 1e6, gen, Runtime.getRuntime().availableProcessors(),
                )
            } finally {
                bitmaps.forEach { it.recycle() }
                out?.recycle()
                looked?.recycle()
            }
        }

    private fun secs(t0: Long) = (System.nanoTime() - t0) / 1e9

    /** Escena sintética con textura y ruido, desplazada (dx, dy). */
    private fun synthetic(w: Int, h: Int, dx: Int, dy: Int, seed: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rnd = Random(seed)
        val row = IntArray(w)
        for (y in 0 until h) {
            val sy = y + dy
            for (x in 0 until w) {
                val sx = x + dx
                val tex = 40f * sin(sx * 0.05f) * sin(sy * 0.031f)
                val n = rnd.nextInt(-8, 9)
                val r = (60 + 120 * sx / w + tex + n).toInt()
                val g = (80 + 100 * sy / h + tex + n).toInt()
                val b = (110 + tex + n).toInt()
                row[x] = ColorMath.pack(r, g, b)
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
        return bmp
    }
}
