package com.lumacam.core.look

import com.lumacam.core.color.ColorMath
import kotlin.math.exp

/**
 * Balance de blancos creativo (encima del AWB de la cámara). Devuelve ganancias por canal en
 * luz lineal, normalizadas para que un gris conserve su luminancia.
 */
object WhiteBalance {
    fun gains(temperature: Float, tint: Float): FloatArray {
        val t = temperature.coerceIn(-1f, 1f)
        val m = tint.coerceIn(-1f, 1f)
        val r = exp(0.25f * t + 0.08f * m)
        val g = exp(-0.16f * m)
        val b = exp(-0.30f * t + 0.08f * m)
        val l = ColorMath.LUMA_R * r + ColorMath.LUMA_G * g + ColorMath.LUMA_B * b
        return floatArrayOf(r / l, g / l, b / l)
    }

    fun isNeutral(gains: FloatArray): Boolean =
        gains.all { kotlin.math.abs(it - 1f) < 1e-4f }
}
