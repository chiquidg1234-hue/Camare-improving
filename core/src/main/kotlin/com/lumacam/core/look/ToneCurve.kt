package com.lumacam.core.look

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Curva de tonos de 256 entradas con precisión de 16 bits.
 *
 * Se construye con una spline cúbica monótona (Fritsch–Carlson) que pasa por 5 puntos de
 * control derivados de contraste, sombras, luces, fade y shoulder. La monotonía garantiza que
 * la curva nunca invierte tonos (no hay solarización), ni siquiera con ajustes extremos.
 */
class ToneCurve private constructor(
    /** Valores 0..65535 para x = i/255. */
    val values16: IntArray,
) {
    init {
        require(values16.size == SIZE)
    }

    /** Evalúa la curva en x∈[0,1] con interpolación lineal entre entradas (igual que la GPU). */
    fun eval(x: Float): Float {
        val pos = x.coerceIn(0f, 1f) * (SIZE - 1)
        val i0 = pos.toInt().coerceAtMost(SIZE - 2)
        val t = pos - i0
        val v = values16[i0] + (values16[i0 + 1] - values16[i0]) * t
        return v / 65535f
    }

    /**
     * Textura 256x1 RGBA8 para la GPU: R = byte alto, G = byte bajo. Con filtrado lineal la
     * interpolación de los dos bytes por separado sigue siendo lineal, así que el shader
     * reconstruye el valor de 16 bits exactamente como [eval].
     */
    fun toRgbaTexture(): ByteArray {
        val out = ByteArray(SIZE * 4)
        for (i in 0 until SIZE) {
            val v = values16[i]
            out[i * 4] = (v ushr 8).toByte()
            out[i * 4 + 1] = (v and 0xFF).toByte()
            out[i * 4 + 2] = 0
            out[i * 4 + 3] = 0xFF.toByte()
        }
        return out
    }

    val isIdentity: Boolean
        get() = (0 until SIZE).all { abs(values16[it] - it * 257) <= 1 }

    companion object {
        const val SIZE = 256

        val IDENTITY = ToneCurve(IntArray(SIZE) { it * 257 })

        /** Puntos de control (x, y) que usa [build]; expuesto para pruebas. */
        fun controlPoints(
            contrast: Float,
            shadows: Float,
            highlights: Float,
            fade: Float,
            shoulder: Float,
        ): Pair<FloatArray, FloatArray> {
            val c = contrast.coerceIn(-1f, 1f)
            val s = shadows.coerceIn(-1f, 1f)
            val h = highlights.coerceIn(-1f, 1f)
            val xs = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
            val ys = floatArrayOf(
                fade.coerceIn(0f, 0.3f),
                0.25f + 0.12f * s - 0.06f * c,
                0.5f,
                0.75f + 0.12f * h + 0.06f * c,
                1f - shoulder.coerceIn(0f, 0.3f),
            )
            // Forzar estrictamente creciente (margen mínimo) y dentro de [0,1].
            for (i in 1 until ys.size) {
                ys[i] = ys[i].coerceAtLeast(ys[i - 1] + 0.02f)
            }
            for (i in ys.indices) ys[i] = ys[i].coerceIn(0f, 1f)
            for (i in ys.size - 2 downTo 0) {
                ys[i] = min(ys[i], ys[i + 1] - 0.005f).coerceAtLeast(0f)
            }
            return xs to ys
        }

        fun build(
            contrast: Float = 0f,
            shadows: Float = 0f,
            highlights: Float = 0f,
            fade: Float = 0f,
            shoulder: Float = 0f,
        ): ToneCurve {
            val (xs, ys) = controlPoints(contrast, shadows, highlights, fade, shoulder)
            val spline = MonotoneCubic(xs, ys)
            val values = IntArray(SIZE) { i ->
                val y = spline.eval(i / (SIZE - 1f)).coerceIn(0f, 1f)
                (y * 65535f + 0.5f).toInt().coerceIn(0, 65535)
            }
            // Pequeñas pérdidas por redondeo nunca deben romper la monotonía.
            for (i in 1 until SIZE) if (values[i] < values[i - 1]) values[i] = values[i - 1]
            return ToneCurve(values)
        }
    }
}

/** Interpolación de Hermite cúbica monótona (Fritsch–Carlson). */
internal class MonotoneCubic(private val xs: FloatArray, private val ys: FloatArray) {
    private val m = FloatArray(xs.size)

    init {
        require(xs.size == ys.size && xs.size >= 2)
        val n = xs.size
        val delta = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        m[0] = delta[0]
        m[n - 1] = delta[n - 2]
        for (i in 1 until n - 1) {
            m[i] = if (delta[i - 1] * delta[i] <= 0f) 0f else (delta[i - 1] + delta[i]) / 2f
        }
        for (i in 0 until n - 1) {
            if (delta[i] == 0f) {
                m[i] = 0f; m[i + 1] = 0f
                continue
            }
            val a = m[i] / delta[i]
            val b = m[i + 1] / delta[i]
            val s = a * a + b * b
            if (s > 9f) {
                val tau = 3f / sqrt(s)
                m[i] = tau * a * delta[i]
                m[i + 1] = tau * b * delta[i]
            }
        }
    }

    fun eval(x: Float): Float {
        val n = xs.size
        if (x <= xs[0]) return ys[0]
        if (x >= xs[n - 1]) return ys[n - 1]
        var i = 0
        while (i < n - 2 && x > xs[i + 1]) i++
        val h = xs[i + 1] - xs[i]
        val t = (x - xs[i]) / h
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2 * t3 - 3 * t2 + 1
        val h10 = t3 - 2 * t2 + t
        val h01 = -2 * t3 + 3 * t2
        val h11 = t3 - t2
        return h00 * ys[i] + h10 * h * m[i] + h01 * ys[i + 1] + h11 * h * m[i + 1]
    }
}
