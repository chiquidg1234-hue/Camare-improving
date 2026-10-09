package com.lumacam.core.look

import com.lumacam.core.color.ColorMath
import com.lumacam.core.color.ColorMath.clamp01
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Implementación en CPU del mismo pipeline que el shader `look.frag`, usada para la foto final.
 *
 * Orden (idéntico en GPU):
 *  1. contraste local + nitidez (sobre la luminancia de la entrada)
 *  2. balance de blancos en luz lineal
 *  3. curva de tonos por canal
 *  4. saturación / vibrancia
 *  5. LUT 3D del look mezclada por intensidad
 *  6. viñeta
 *
 * Procesa por franjas de filas para no necesitar la imagen completa en memoria de Java.
 * Cada franja necesita [requiredMargin] filas extra arriba y abajo (salvo en los bordes de la
 * imagen) para que el resultado sea idéntico al de procesar la imagen entera de una vez.
 */
class LookProcessor(private val look: ResolvedLook) {

    fun blurRadius(imageWidth: Int, imageHeight: Int): Int {
        val sigma = look.sigmaFrac * min(imageWidth, imageHeight)
        return max(1, (sigma - 0.5f).roundToInt())
    }

    fun requiredMargin(imageWidth: Int, imageHeight: Int): Int =
        if (look.localContrast > 0f) 3 * blurRadius(imageWidth, imageHeight) + 1 else 1

    /**
     * @param src filas [srcY0, srcY0 + srcRows) de la imagen (ARGB, ancho [width]).
     * @param outY0 primera fila (absoluta) a producir; debe estar dentro de src.
     * @param dst destino de tamaño >= outRows * width.
     */
    fun processRows(
        src: IntArray,
        srcY0: Int,
        srcRows: Int,
        width: Int,
        imageHeight: Int,
        outY0: Int,
        outRows: Int,
        dst: IntArray,
    ) {
        require(outY0 >= srcY0 && outY0 + outRows <= srcY0 + srcRows) { "Filas fuera de la franja" }
        val n = srcRows * width
        val luma = FloatArray(n)
        for (i in 0 until n) luma[i] = ColorMath.lumaOf(src[i]) / 255f

        // El desenfoque se hace en enteros (punto fijo) para que el resultado sea idéntico
        // procesando por franjas o la imagen entera (las sumas en float dependen del orden).
        val lumaFixed: IntArray?
        val blur: IntArray?
        if (look.localContrast > 0f) {
            lumaFixed = IntArray(n) { (ColorMath.lumaOf(src[it]) * FIXED_SCALE + 0.5f).toInt() }
            blur = blurSeparable(lumaFixed, width, srcRows, blurRadius(width, imageHeight))
        } else {
            lumaFixed = null
            blur = null
        }

        val gains = look.gains
        val wb = !look.wbNeutral
        val lutMix = look.lutMix
        val sat = look.saturation
        val vib = look.vibrance
        val lc = look.localContrast * LC_GAIN
        val sh = look.sharpness * SH_GAIN
        val vig = look.vignette
        val curve = look.curve
        val lut = look.lut
        val lutOut = FloatArray(3)
        val invW = 1f / width
        val invH = 1f / imageHeight

        for (oy in 0 until outRows) {
            val y = outY0 + oy
            val sy = y - srcY0
            val syUp = max(sy - 1, 0)
            val syDown = min(sy + 1, srcRows - 1)
            val v = (y + 0.5f) * invH
            for (x in 0 until width) {
                val idx = sy * width + x
                val p = src[idx]
                var r = ColorMath.red(p) / 255f
                var g = ColorMath.green(p) / 255f
                var b = ColorMath.blue(p) / 255f
                val yl = luma[idx]

                var delta = 0f
                if (blur != null && lumaFixed != null) {
                    val detail = (lumaFixed[idx] - blur[idx]) * INV_FIXED_MAX
                    val w = 4f * yl * (1f - yl)
                    delta += (lc * detail * w).coerceIn(-0.25f, 0.25f)
                }
                if (sh > 0f) {
                    val xl = max(x - 1, 0)
                    val xr = min(x + 1, width - 1)
                    val avg = (luma[sy * width + xl] + luma[sy * width + xr] +
                        luma[syUp * width + x] + luma[syDown * width + x]) * 0.25f
                    val d = yl - avg
                    val ad = abs(d) - SHARPEN_THRESHOLD
                    if (ad > 0f) delta += (sh * (if (d > 0f) ad else -ad)).coerceIn(-0.2f, 0.2f)
                }
                if (delta != 0f) {
                    r = clamp01(r + delta); g = clamp01(g + delta); b = clamp01(b + delta)
                }

                if (wb) {
                    r = clamp01(ColorMath.toGamma(ColorMath.toLinear(r) * gains[0]))
                    g = clamp01(ColorMath.toGamma(ColorMath.toLinear(g) * gains[1]))
                    b = clamp01(ColorMath.toGamma(ColorMath.toLinear(b) * gains[2]))
                }

                r = curve.eval(r); g = curve.eval(g); b = curve.eval(b)

                if (sat != 1f || vib != 0f) {
                    val l = ColorMath.luma(r, g, b)
                    val s = ColorMath.maxOf3(r, g, b) - ColorMath.minOf3(r, g, b)
                    val k = sat * (1f + vib * (1f - s))
                    r = clamp01(l + (r - l) * k)
                    g = clamp01(l + (g - l) * k)
                    b = clamp01(l + (b - l) * k)
                }

                if (lutMix > 0f) {
                    lut.sample(r, g, b, lutOut)
                    r += (lutOut[0] - r) * lutMix
                    g += (lutOut[1] - g) * lutMix
                    b += (lutOut[2] - b) * lutMix
                }

                if (vig > 0f) {
                    val u = (x + 0.5f) * invW
                    val f = vignetteFactor(u, v, vig)
                    r *= f; g *= f; b *= f
                }

                dst[oy * width + x] = ColorMath.pack(
                    ColorMath.toByte(r), ColorMath.toByte(g), ColorMath.toByte(b),
                )
            }
        }
    }

    /** Conveniencia: procesa una imagen completa en memoria. */
    fun processImage(src: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        processRows(src, 0, height, width, height, 0, height, out)
        return out
    }

    companion object {
        const val LC_GAIN = 2.0f
        const val SH_GAIN = 2.0f
        const val SHARPEN_THRESHOLD = 1.0f / 255f

        fun vignetteFactor(u: Float, v: Float, amount: Float): Float {
            val du = u - 0.5f
            val dv = v - 0.5f
            val d = sqrt(du * du + dv * dv) * 1.4142135f
            return 1f - amount * 0.6f * ColorMath.smoothstep(0.3f, 1.0f, d)
        }

        /** Luminancia en punto fijo: 0..255 multiplicado por esta escala. */
        const val FIXED_SCALE = 16f
        const val INV_FIXED_MAX = 1f / (255f * FIXED_SCALE)

        /** Desenfoque ≈ gaussiano: 3 cajas horizontales + 3 verticales, bordes replicados. */
        fun blurSeparable(src: IntArray, w: Int, h: Int, r: Int): IntArray {
            val a = src.copyOf()
            val b = IntArray(src.size)
            boxBlurH(a, b, w, h, r); boxBlurH(b, a, w, h, r); boxBlurH(a, b, w, h, r)
            boxBlurV(b, a, w, h, r); boxBlurV(a, b, w, h, r); boxBlurV(b, a, w, h, r)
            return a
        }

        fun boxBlurH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
            val div = 2 * r + 1
            val half = div / 2
            for (y in 0 until h) {
                val base = y * w
                var sum = src[base] * (r + 1)
                for (i in 1..r) sum += src[base + min(i, w - 1)]
                for (x in 0 until w) {
                    dst[base + x] = (sum + half) / div
                    sum += src[base + min(x + r + 1, w - 1)] - src[base + max(x - r, 0)]
                }
            }
        }

        fun boxBlurV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
            val div = 2 * r + 1
            val half = div / 2
            for (x in 0 until w) {
                var sum = src[x] * (r + 1)
                for (i in 1..r) sum += src[min(i, h - 1) * w + x]
                for (y in 0 until h) {
                    dst[y * w + x] = (sum + half) / div
                    sum += src[min(y + r + 1, h - 1) * w + x] - src[max(y - r, 0) * w + x]
                }
            }
        }
    }
}
