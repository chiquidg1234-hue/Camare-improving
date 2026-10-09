package com.lumacam.core.look

/**
 * Ajustes manuales de imagen. Todos los valores son relativos y neutros en 0:
 * - temperature: -1 (frío/azul) .. +1 (cálido/ámbar)
 * - tint: -1 (verde) .. +1 (magenta)
 * - contrast, shadows, highlights, saturation, vibrance: -1 .. +1
 * - localContrast (claridad) y sharpness (nitidez): 0 .. 1
 */
data class Adjustments(
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val contrast: Float = 0f,
    val shadows: Float = 0f,
    val highlights: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val localContrast: Float = 0f,
    val sharpness: Float = 0f,
) {
    operator fun plus(o: Adjustments) = Adjustments(
        temperature = temperature + o.temperature,
        tint = tint + o.tint,
        contrast = contrast + o.contrast,
        shadows = shadows + o.shadows,
        highlights = highlights + o.highlights,
        saturation = saturation + o.saturation,
        vibrance = vibrance + o.vibrance,
        localContrast = localContrast + o.localContrast,
        sharpness = sharpness + o.sharpness,
    )

    fun scaled(k: Float) = Adjustments(
        temperature = temperature * k,
        tint = tint * k,
        contrast = contrast * k,
        shadows = shadows * k,
        highlights = highlights * k,
        saturation = saturation * k,
        vibrance = vibrance * k,
        localContrast = localContrast * k,
        sharpness = sharpness * k,
    )

    fun clamped() = Adjustments(
        temperature = temperature.coerceIn(-1f, 1f),
        tint = tint.coerceIn(-1f, 1f),
        contrast = contrast.coerceIn(-1f, 1f),
        shadows = shadows.coerceIn(-1f, 1f),
        highlights = highlights.coerceIn(-1f, 1f),
        saturation = saturation.coerceIn(-1f, 1f),
        vibrance = vibrance.coerceIn(-1f, 1f),
        localContrast = localContrast.coerceIn(0f, 1f),
        sharpness = sharpness.coerceIn(0f, 1f),
    )

    val isNeutral: Boolean get() = this == NEUTRAL

    companion object {
        val NEUTRAL = Adjustments()
    }
}
