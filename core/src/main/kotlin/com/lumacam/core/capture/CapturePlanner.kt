package com.lumacam.core.capture

/**
 * Decide cuántos frames capturar y a qué escala decodificarlos según la resolución y la memoria
 * disponible, para no agotar la RAM en un teléfono de gama de entrada.
 */
data class CapturePlan(
    val frames: Int,
    /** inSampleSize para BitmapFactory (1 = resolución completa). */
    val decodeSampleSize: Int,
    /** Motivo legible si se recortó lo que pidió el usuario; null si no. */
    val note: String?,
) {
    val multiFrame: Boolean get() = frames > 1
}

object CapturePlanner {
    const val MAX_FRAMES = 8

    /**
     * Frames que pide el usuario antes de mirar la memoria:
     * multi-frame apagado, flash o modo del fabricante → 1; si no, el ajuste manual o el que
     * sugiere el look.
     */
    fun requestedFrames(
        multiFrame: Boolean,
        vendorModeActive: Boolean,
        flashEnabled: Boolean,
        manualFrames: Int,
        lookSuggested: Int,
    ): Int = when {
        !multiFrame -> 1
        vendorModeActive -> 1 // el procesado del fabricante ya combina varias fotos
        flashEnabled -> 1 // con flash la ráfaga no tiene sentido
        manualFrames > 0 -> manualFrames.coerceAtMost(MAX_FRAMES)
        else -> lookSuggested.coerceIn(1, MAX_FRAMES)
    }
    /** Por encima de esto (p. ej. modo 50 MP sin binning) no se hace multi-frame. */
    const val MAX_MULTI_FRAME_PIXELS = 25_000_000L

    /**
     * @param memoryBudgetBytes memoria que se puede usar para bitmaps (nativa, Android 8+).
     */
    fun plan(width: Int, height: Int, requestedFrames: Int, memoryBudgetBytes: Long): CapturePlan {
        require(width > 0 && height > 0)
        val requested = requestedFrames.coerceIn(1, MAX_FRAMES)

        // Una foto necesita: frame decodificado + salida del look (+ margen).
        var sample = 1
        while (sample < 8 && bytes(width, height, sample) * 3 > memoryBudgetBytes) sample *= 2
        val notes = ArrayList<String>()
        if (sample > 1) {
            notes += "Memoria insuficiente para ${mp(width, height, 1)} MP; se guarda a ${mp(width, height, sample)} MP"
        }

        val pixels = (width / sample).toLong() * (height / sample)
        var frames = requested
        if (frames > 1 && pixels > MAX_MULTI_FRAME_PIXELS) {
            frames = 1
            notes += "Multi-frame desactivado a ${mp(width, height, sample)} MP (demasiado pesado); se usa 1 frame"
        }
        if (frames > 1) {
            // N frames + resultado fusionado + salida del look.
            val perFrame = bytes(width, height, sample)
            val maxFrames = (memoryBudgetBytes / perFrame - 2).toInt().coerceAtLeast(1)
            if (maxFrames < frames) {
                frames = maxFrames
                notes += "Memoria: se usan $frames frames en vez de $requested"
            }
        }
        return CapturePlan(frames, sample, notes.takeIf { it.isNotEmpty() }?.joinToString(". "))
    }

    private fun bytes(w: Int, h: Int, sample: Int): Long = (w / sample).toLong() * (h / sample) * 4L

    private fun mp(w: Int, h: Int, sample: Int): String {
        val v = (w / sample).toLong() * (h / sample) / 1_000_000.0
        return if (v >= 10) "%.0f".format(v) else "%.1f".format(v)
    }
}
