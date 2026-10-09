package com.lumacam.core.look

enum class LookId(val displayName: String) {
    NATURAL("Natural"),
    WARM("Cálido"),
    CINE("Cine"),
    VIVID("Vívido"),
    NIGHT("Nocturno"),
}

/**
 * Un "look": ajustes base (que escalan con la intensidad), un grade de color que se hornea en
 * una LUT 3D y algunos parámetros de captura sugeridos.
 */
data class LookPreset(
    val id: LookId,
    val base: Adjustments,
    val grade: ColorGrade = ColorGrade.IDENTITY,
    /** Levanta el negro (aspecto "lavado" de cine), 0..0.15. */
    val fade: Float = 0f,
    /** Comprime las luces altas para que no se quemen, 0..0.15. */
    val shoulder: Float = 0f,
    /** Oscurecimiento de esquinas, 0..1. */
    val vignette: Float = 0f,
    /** Frames que se promedian por foto cuando el multi-frame está en automático. */
    val suggestedFrames: Int = 4,
    /** Permite exposiciones más largas (rango de FPS bajo) en modo foto. */
    val longExposure: Boolean = false,
    /** Reducción de ruido de color en la foto final (0..1), se adapta al ruido medido. */
    val noiseReduction: Float = 0.35f,
) {
    val displayName: String get() = id.displayName
}

object Looks {
    val NATURAL = LookPreset(
        id = LookId.NATURAL,
        base = Adjustments(
            contrast = 0.08f, shadows = 0.12f, highlights = -0.12f,
            vibrance = 0.15f, localContrast = 0.25f, sharpness = 0.25f,
        ),
        grade = ColorGrade(highlightDesaturation = 0.12f),
        shoulder = 0.02f,
        suggestedFrames = 4,
    )

    val WARM = LookPreset(
        id = LookId.WARM,
        base = Adjustments(
            temperature = 0.30f, tint = 0.05f, contrast = 0.08f, shadows = 0.08f,
            highlights = -0.10f, saturation = 0.05f, vibrance = 0.15f,
            localContrast = 0.20f, sharpness = 0.20f,
        ),
        grade = ColorGrade(
            shadowTint = Rgb(0.62f, 0.48f, 0.38f), shadowTintAmount = 0.10f,
            highlightTint = Rgb(1.0f, 0.82f, 0.55f), highlightTintAmount = 0.14f,
            highlightDesaturation = 0.10f,
        ),
        fade = 0.02f,
        shoulder = 0.04f,
        vignette = 0.12f,
        suggestedFrames = 4,
    )

    val CINE = LookPreset(
        id = LookId.CINE,
        base = Adjustments(
            contrast = 0.18f, highlights = -0.22f, saturation = -0.15f,
            vibrance = 0.05f, localContrast = 0.20f, sharpness = 0.15f,
        ),
        grade = ColorGrade(
            shadowTint = Rgb(0.30f, 0.50f, 0.56f), shadowTintAmount = 0.12f,
            highlightTint = Rgb(1.0f, 0.74f, 0.50f), highlightTintAmount = 0.10f,
            tealOrange = 0.70f,
            highlightDesaturation = 0.30f,
        ),
        fade = 0.05f,
        shoulder = 0.08f,
        vignette = 0.30f,
        suggestedFrames = 4,
    )

    val VIVID = LookPreset(
        id = LookId.VIVID,
        base = Adjustments(
            contrast = 0.22f, shadows = 0.05f, highlights = -0.10f,
            saturation = 0.14f, vibrance = 0.45f, localContrast = 0.40f, sharpness = 0.30f,
        ),
        grade = ColorGrade(highlightDesaturation = 0.10f),
        shoulder = 0.02f,
        vignette = 0.05f,
        suggestedFrames = 4,
        noiseReduction = 0.25f,
    )

    val NIGHT = LookPreset(
        id = LookId.NIGHT,
        base = Adjustments(
            temperature = -0.12f, contrast = -0.05f, shadows = 0.40f, highlights = -0.35f,
            saturation = -0.10f, vibrance = 0.05f, localContrast = 0.15f, sharpness = 0.05f,
        ),
        grade = ColorGrade(
            shadowTint = Rgb(0.36f, 0.43f, 0.60f), shadowTintAmount = 0.06f,
            shadowDesaturation = 0.60f,
            highlightDesaturation = 0.15f,
        ),
        shoulder = 0.06f,
        suggestedFrames = 8,
        longExposure = true,
        noiseReduction = 0.85f,
    )

    val ALL: List<LookPreset> = listOf(NATURAL, WARM, CINE, VIVID, NIGHT)

    fun byId(id: LookId): LookPreset = ALL.first { it.id == id }

    fun byName(name: String?): LookPreset =
        ALL.firstOrNull { it.id.name == name } ?: NATURAL
}
