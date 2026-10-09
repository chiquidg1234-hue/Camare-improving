package com.lumacam.core.merge

import com.lumacam.core.color.ColorMath
import com.lumacam.core.image.FrameSource
import com.lumacam.core.image.LumaImage
import kotlin.math.abs
import kotlin.math.max

object ImageStats {
    /**
     * Estima la desviación típica del ruido de luminancia (unidades 0..255) a resolución
     * completa, con la mediana de la respuesta al laplaciano 3x3 de Immerkær (robusta a bordes).
     * Lee sólo unas bandas de filas.
     */
    fun noiseSigma(src: FrameSource, bands: Int = 8, bandRows: Int = 16): Float {
        val w = src.width
        val h = src.height
        if (w < 8 || h < bandRows + 4) return 0f
        val buf = IntArray(bandRows * w)
        val l = FloatArray(bandRows * w)
        val responses = ArrayList<Float>()
        val step = max(1, (w - 2) / 1024)
        for (b in 0 until bands) {
            val y0 = ((h - bandRows) * (b + 0.5f) / bands).toInt().coerceIn(0, h - bandRows)
            src.readRows(y0, bandRows, buf, 0)
            for (i in l.indices) l[i] = ColorMath.lumaOf(buf[i])
            for (y in 1 until bandRows - 1) {
                var x = 1
                while (x < w - 1) {
                    val c = y * w + x
                    val v = l[c - w - 1] - 2 * l[c - w] + l[c - w + 1] -
                        2 * l[c - 1] + 4 * l[c] - 2 * l[c + 1] +
                        l[c + w - 1] - 2 * l[c + w] + l[c + w + 1]
                    responses += abs(v)
                    x += step
                }
            }
        }
        if (responses.isEmpty()) return 0f
        responses.sort()
        val median = responses[responses.size / 2]
        // La respuesta tiene desviación 6σ; mediana(|N(0,s)|) = 0.6745 s.
        return median / 0.6745f / 6f
    }

    /** Nitidez: varianza del laplaciano sobre la luminancia reducida (más = más nítido). */
    fun sharpness(luma: LumaImage): Double {
        val w = luma.width
        val h = luma.height
        if (w < 3 || h < 3) return 0.0
        var sum = 0.0
        var sum2 = 0.0
        var n = 0
        val d = luma.data
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val c = y * w + x
            val v = (4 * d[c] - d[c - 1] - d[c + 1] - d[c - w] - d[c + w]).toDouble()
            sum += v; sum2 += v * v; n++
        }
        val mean = sum / n
        return sum2 / n - mean * mean
    }
}
