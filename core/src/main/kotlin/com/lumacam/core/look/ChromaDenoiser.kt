package com.lumacam.core.look

import com.lumacam.core.color.ColorMath
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reducción de ruido de color (crominancia) para la foto final.
 *
 * Usa un filtro guiado (He et al.) con la luminancia como guía: en zonas planas promedia el
 * color (quita las manchas de color típicas de las fotos con poca luz) y donde hay bordes de
 * luminancia conserva los bordes de color. La luminancia (el detalle) no se toca.
 *
 * Igual que [LookProcessor], trabaja por franjas con [requiredMargin] filas de margen.
 */
class ChromaDenoiser(
    /** 0..1: mezcla entre el color original y el filtrado. */
    private val strength: Float,
    /** Radio del filtro en píxeles. */
    private val radius: Int,
    /** Varianza de luminancia por debajo de la cual una zona se considera plana. */
    private val eps: Float = 0.0012f,
) {
    val requiredMargin: Int get() = 2 * radius + 1

    fun processRows(
        src: IntArray,
        srcY0: Int,
        srcRows: Int,
        width: Int,
        outY0: Int,
        outRows: Int,
        dst: IntArray,
    ) {
        require(outY0 >= srcY0 && outY0 + outRows <= srcY0 + srcRows)
        val n = srcRows * width
        val y = FloatArray(n)
        val cb = FloatArray(n)
        val cr = FloatArray(n)
        for (i in 0 until n) {
            val p = src[i]
            val r = ColorMath.red(p) / 255f
            val g = ColorMath.green(p) / 255f
            val b = ColorMath.blue(p) / 255f
            y[i] = 0.299f * r + 0.587f * g + 0.114f * b
            cb[i] = -0.168736f * r - 0.331264f * g + 0.5f * b
            cr[i] = 0.5f * r - 0.418688f * g - 0.081312f * b
        }
        val qcb = guided(y, cb, width, srcRows)
        val qcr = guided(y, cr, width, srcRows)
        val s = strength.coerceIn(0f, 1f)
        val off = (outY0 - srcY0) * width
        for (i in 0 until outRows * width) {
            val k = off + i
            val l = y[k]
            val u = cb[k] + (qcb[k] - cb[k]) * s
            val v = cr[k] + (qcr[k] - cr[k]) * s
            dst[i] = ColorMath.pack(
                ColorMath.toByte(l + 1.402f * v),
                ColorMath.toByte(l - 0.344136f * u - 0.714136f * v),
                ColorMath.toByte(l + 1.772f * u),
            )
        }
    }

    fun processImage(src: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        processRows(src, 0, height, width, 0, height, out)
        return out
    }

    /** Filtro guiado: q = mean(a)·I + mean(b), con a = cov(I,p)/(var(I)+eps). */
    private fun guided(guide: FloatArray, p: FloatArray, w: Int, h: Int): FloatArray {
        val n = guide.size
        val ip = FloatArray(n) { guide[it] * p[it] }
        val ii = FloatArray(n) { guide[it] * guide[it] }
        val meanI = box(guide, w, h)
        val meanP = box(p, w, h)
        val meanIp = box(ip, w, h)
        val meanII = box(ii, w, h)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val varI = meanII[i] - meanI[i] * meanI[i]
            val cov = meanIp[i] - meanI[i] * meanP[i]
            val ai = cov / (varI + eps)
            a[i] = ai
            b[i] = meanP[i] - ai * meanI[i]
        }
        val ma = box(a, w, h)
        val mb = box(b, w, h)
        for (i in 0 until n) ma[i] = ma[i] * guide[i] + mb[i]
        return ma
    }

    /** Media en una ventana (2r+1)² con bordes replicados (sumas acumuladas en double). */
    private fun box(src: FloatArray, w: Int, h: Int): FloatArray {
        val r = radius
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        val norm = 1.0 / (2 * r + 1)
        for (yy in 0 until h) {
            val base = yy * w
            var sum = src[base].toDouble() * (r + 1)
            for (i in 1..r) sum += src[base + min(i, w - 1)]
            for (x in 0 until w) {
                tmp[base + x] = (sum * norm).toFloat()
                sum += src[base + min(x + r + 1, w - 1)] - src[base + max(x - r, 0)]
            }
        }
        for (x in 0 until w) {
            var sum = tmp[x].toDouble() * (r + 1)
            for (i in 1..r) sum += tmp[min(i, h - 1) * w + x]
            for (yy in 0 until h) {
                out[yy * w + x] = (sum * norm).toFloat()
                sum += tmp[min(yy + r + 1, h - 1) * w + x] - tmp[max(yy - r, 0) * w + x]
            }
        }
        return out
    }

    companion object {
        /** Radio proporcional al tamaño (≈ 8 px a 12 MP). */
        fun radiusFor(width: Int, height: Int): Int = max(2, (min(width, height) * 0.0025f).roundToInt())

        /**
         * Fuerza efectiva según el ruido medido (σ de luminancia, 0..255): con poco ruido (día)
         * se suaviza poco para no apagar colores finos.
         */
        fun adaptiveStrength(base: Float, noiseSigma: Float): Float =
            (base * (noiseSigma / 3f).coerceIn(0.3f, 1f)).coerceIn(0f, 1f)
    }
}
