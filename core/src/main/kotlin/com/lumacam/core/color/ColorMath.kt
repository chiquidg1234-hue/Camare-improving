package com.lumacam.core.color

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Funciones de color compartidas por el procesado en CPU (foto) y documentadas para que el
 * shader de la vista previa (assets/shaders/look.frag) haga exactamente la misma cuenta.
 */
object ColorMath {
    // Pesos de luminancia Rec.709, los mismos que usa el shader.
    const val LUMA_R = 0.2126f
    const val LUMA_G = 0.7152f
    const val LUMA_B = 0.0722f

    /** Gamma aproximada usada para pasar a luz lineal (el shader usa pow(c, 2.2)). */
    const val GAMMA = 2.2f
    private const val INV_GAMMA = 1f / GAMMA

    fun luma(r: Float, g: Float, b: Float): Float = LUMA_R * r + LUMA_G * g + LUMA_B * b

    fun clamp01(x: Float): Float = if (x < 0f) 0f else if (x > 1f) 1f else x

    fun toLinear(c: Float): Float = max(c, 0f).pow(GAMMA)

    fun toGamma(c: Float): Float = max(c, 0f).pow(INV_GAMMA)

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = clamp01((x - edge0) / (edge1 - edge0))
        return t * t * (3f - 2f * t)
    }

    fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun red(argb: Int): Int = (argb ushr 16) and 0xFF
    fun green(argb: Int): Int = (argb ushr 8) and 0xFF
    fun blue(argb: Int): Int = argb and 0xFF

    fun pack(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Convierte un canal en [0,1] a byte con redondeo, como hace la GPU al escribir RGBA8. */
    fun toByte(c: Float): Int = (clamp01(c) * 255f + 0.5f).toInt()

    fun lumaOf(argb: Int): Float =
        (LUMA_R * red(argb) + LUMA_G * green(argb) + LUMA_B * blue(argb))

    fun maxOf3(a: Float, b: Float, c: Float): Float = max(a, max(b, c))
    fun minOf3(a: Float, b: Float, c: Float): Float = min(a, min(b, c))
}
