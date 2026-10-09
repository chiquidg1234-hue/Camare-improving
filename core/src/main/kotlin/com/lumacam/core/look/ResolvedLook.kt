package com.lumacam.core.look

import java.util.concurrent.ConcurrentHashMap

/**
 * Todos los parámetros que necesita el pipeline (GPU en vista previa/video y CPU en foto),
 * ya combinados: look × intensidad + ajustes manuales del usuario.
 */
class ResolvedLook(
    val presetId: LookId,
    val intensity: Float,
    val effective: Adjustments,
    /** Ganancias R,G,B en luz lineal (balance de blancos creativo). */
    val gains: FloatArray,
    val curve: ToneCurve,
    /** Factor de saturación global (0..2, 1 = neutro). */
    val saturation: Float,
    val vibrance: Float,
    val localContrast: Float,
    val sharpness: Float,
    /** Sigma del desenfoque del contraste local como fracción del lado corto de la imagen. */
    val sigmaFrac: Float,
    val lut: Lut3D,
    /** Mezcla entre la imagen y la LUT del look (0..1). */
    val lutMix: Float,
    val vignette: Float,
    /** Reducción de ruido de color de la foto (0..1, antes de adaptarla al ruido medido). */
    val chromaDenoise: Float = 0f,
) {
    val wbNeutral: Boolean get() = WhiteBalance.isNeutral(gains)

    val isNeutral: Boolean
        get() = wbNeutral && curve.isIdentity && saturation == 1f && vibrance == 0f &&
            localContrast == 0f && sharpness == 0f && lutMix == 0f && vignette == 0f && chromaDenoise == 0f

    fun describe(): String =
        "${presetId.displayName} ${(intensity * 100).toInt()}%"

    companion object {
        const val DEFAULT_SIGMA_FRAC = 0.006f

        private val lutCache = ConcurrentHashMap<ColorGrade, Lut3D>()
        private val identityLut: Lut3D by lazy { Lut3D.identity() }

        fun lutFor(grade: ColorGrade): Lut3D =
            if (grade.isIdentity) identityLut else lutCache.getOrPut(grade) { Lut3D.fromGrade(grade) }

        fun resolve(preset: LookPreset, intensity: Float, user: Adjustments = Adjustments.NEUTRAL): ResolvedLook {
            val k = intensity.coerceIn(0f, 1f)
            val eff = (preset.base.copy(noiseReduction = preset.noiseReduction).scaled(k) + user).clamped()
            val curve = ToneCurve.build(
                contrast = eff.contrast,
                shadows = eff.shadows,
                highlights = eff.highlights,
                fade = preset.fade * k,
                shoulder = preset.shoulder * k,
            )
            val gradeActive = !preset.grade.isIdentity && k > 0f
            return ResolvedLook(
                presetId = preset.id,
                intensity = k,
                effective = eff,
                gains = WhiteBalance.gains(eff.temperature, eff.tint),
                curve = curve,
                saturation = 1f + eff.saturation,
                vibrance = eff.vibrance,
                localContrast = eff.localContrast,
                sharpness = eff.sharpness,
                sigmaFrac = DEFAULT_SIGMA_FRAC,
                lut = lutFor(preset.grade),
                lutMix = if (gradeActive) k else 0f,
                vignette = (preset.vignette * k).coerceIn(0f, 1f),
                chromaDenoise = eff.noiseReduction,
            )
        }

        /** Sin ningún efecto: la salida es igual a la entrada. */
        val NEUTRAL: ResolvedLook by lazy {
            ResolvedLook(
                presetId = LookId.NATURAL,
                intensity = 0f,
                effective = Adjustments.NEUTRAL,
                gains = floatArrayOf(1f, 1f, 1f),
                curve = ToneCurve.IDENTITY,
                saturation = 1f,
                vibrance = 0f,
                localContrast = 0f,
                sharpness = 0f,
                sigmaFrac = DEFAULT_SIGMA_FRAC,
                lut = identityLut,
                lutMix = 0f,
                vignette = 0f,
            )
        }
    }
}
