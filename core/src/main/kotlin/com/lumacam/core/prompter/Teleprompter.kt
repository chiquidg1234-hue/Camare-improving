package com.lumacam.core.prompter

import kotlin.math.max
import kotlin.math.roundToInt

/** Cuentas del teleprompter (modo PRESENTAR): palabras, duración y velocidad de desplazamiento. */
object Teleprompter {
    const val MIN_WPM = 60
    const val MAX_WPM = 260
    const val DEFAULT_WPM = 130
    const val MIN_TEXT_SP = 18
    const val MAX_TEXT_SP = 56
    const val DEFAULT_TEXT_SP = 30

    private val WORD = Regex("""[\p{L}\p{N}][\p{L}\p{N}'’\-]*""")

    fun wordCount(text: String): Int = WORD.findAll(text).count()

    /** Segundos que se tarda en leer [words] palabras a [wpm] palabras por minuto. */
    fun durationSeconds(words: Int, wpm: Int): Float =
        if (words <= 0) 0f else words * 60f / wpm.coerceIn(MIN_WPM, MAX_WPM)

    /**
     * Píxeles por segundo para recorrer [scrollRangePx] (todo el texto) en el tiempo que se tarda
     * en leerlo. Con muy pocas palabras se usa un mínimo de 1 s para no saltar de golpe.
     */
    fun scrollSpeed(scrollRangePx: Float, words: Int, wpm: Int): Float {
        if (scrollRangePx <= 0f) return 0f
        return scrollRangePx / max(1f, durationSeconds(words, wpm))
    }

    fun formatDuration(seconds: Float): String {
        val s = seconds.roundToInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    fun clampWpm(wpm: Int): Int = wpm.coerceIn(MIN_WPM, MAX_WPM)

    fun clampTextSize(sp: Int): Int = sp.coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)

    /** Título corto para un guion: la primera línea con texto, recortada. */
    fun titleFor(text: String, maxLen: Int = 32): String {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return "Guion sin título"
        return if (first.length <= maxLen) first else first.take(maxLen - 1).trimEnd() + "…"
    }

    val SAMPLE = """
        Hola, este es tu teleprompter.

        Escribe o pega aquí tu discurso con el botón del lápiz. El texto sube solo mientras grabas, a la velocidad que elijas en palabras por minuto.

        Mira hacia la parte de arriba de la pantalla, cerca de la cámara frontal: así parece que miras a quien te ve.

        Toca el texto para pausarlo, arrástralo para adelantar o volver, y usa los botones de tamaño y velocidad hasta que te sientas cómodo.

        ¡Mucha suerte con tu presentación!
    """.trimIndent()
}
