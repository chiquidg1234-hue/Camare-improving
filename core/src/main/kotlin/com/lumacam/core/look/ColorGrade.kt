package com.lumacam.core.look

import com.lumacam.core.color.ColorMath
import com.lumacam.core.color.ColorMath.clamp01
import com.lumacam.core.color.ColorMath.luma
import com.lumacam.core.color.ColorMath.smoothstep

data class Rgb(val r: Float, val g: Float, val b: Float)

/**
 * Transformación de color "creativa" de un look. Se evalúa una sola vez para generar una
 * LUT 3D (ver [Lut3D]); la vista previa y la foto usan esa misma LUT.
 * Trabaja en espacio gamma con valores en [0,1].
 */
data class ColorGrade(
    /** Color hacia el que se tiñen las sombras (split toning) y su intensidad (0..1). */
    val shadowTint: Rgb = GRAY,
    val shadowTintAmount: Float = 0f,
    /** Color hacia el que se tiñen las luces y su intensidad (0..1). */
    val highlightTint: Rgb = GRAY,
    val highlightTintAmount: Float = 0f,
    /** Empuja tonos cálidos hacia naranja y fríos hacia verde azulado (0..1). */
    val tealOrange: Float = 0f,
    /** Quita color en sombras profundas (oculta ruido de color de noche) (0..1). */
    val shadowDesaturation: Float = 0f,
    /** Quita color en luces muy altas, como la película (0..1). */
    val highlightDesaturation: Float = 0f,
) {
    val isIdentity: Boolean
        get() = shadowTintAmount == 0f && highlightTintAmount == 0f && tealOrange == 0f &&
            shadowDesaturation == 0f && highlightDesaturation == 0f

    /** Aplica el grade a (r,g,b) y escribe el resultado en [out] (tamaño >= 3). */
    fun apply(r0: Float, g0: Float, b0: Float, out: FloatArray) {
        var r = r0
        var g = g0
        var b = b0
        val l0 = luma(r, g, b)

        // 1) Split toning: desplaza el color de sombras y luces sin tocar los medios.
        if (shadowTintAmount > 0f || highlightTintAmount > 0f) {
            val ws = (1f - l0) * (1f - l0) * shadowTintAmount
            val wh = l0 * l0 * highlightTintAmount
            r += (shadowTint.r - 0.5f) * ws + (highlightTint.r - 0.5f) * wh
            g += (shadowTint.g - 0.5f) * ws + (highlightTint.g - 0.5f) * wh
            b += (shadowTint.b - 0.5f) * ws + (highlightTint.b - 0.5f) * wh
        }

        // 2) Teal & orange: separa cálidos (piel) de fríos (cielo, sombras).
        if (tealOrange > 0f) {
            val warmth = (r - b).coerceIn(-1f, 1f)
            val chroma = ColorMath.maxOf3(r, g, b) - ColorMath.minOf3(r, g, b)
            val k = tealOrange * (0.35f + 0.65f * chroma)
            if (warmth >= 0f) {
                r += 0.10f * k * warmth
                g += 0.02f * k * warmth
                b -= 0.10f * k * warmth
            } else {
                val c = -warmth
                r -= 0.10f * k * c
                g += 0.04f * k * c
                b += 0.06f * k * c
            }
            // Sombras neutras hacia verde azulado, muy suave.
            val shadowW = (1f - smoothstep(0.0f, 0.45f, l0)) * tealOrange * 0.05f
            r -= shadowW
            g += shadowW * 0.3f
            b += shadowW * 0.6f
        }

        // 3) Mantener la luminancia: el tono lo decide la curva, no el grade.
        val l1 = luma(r, g, b)
        if (l1 > 1e-4f) {
            val k = 1f + 0.8f * (l0 / l1 - 1f)
            r *= k; g *= k; b *= k
        }

        // 4) Desaturación selectiva por luminancia.
        var chromaScale = 1f
        if (shadowDesaturation > 0f) {
            chromaScale *= 1f - shadowDesaturation * (1f - smoothstep(0.0f, 0.35f, l0))
        }
        if (highlightDesaturation > 0f) {
            chromaScale *= 1f - highlightDesaturation * smoothstep(0.70f, 1.0f, l0)
        }
        if (chromaScale != 1f) {
            val l = luma(r, g, b)
            r = l + (r - l) * chromaScale
            g = l + (g - l) * chromaScale
            b = l + (b - l) * chromaScale
        }

        out[0] = clamp01(r)
        out[1] = clamp01(g)
        out[2] = clamp01(b)
    }

    companion object {
        val GRAY = Rgb(0.5f, 0.5f, 0.5f)
        val IDENTITY = ColorGrade()
    }
}
