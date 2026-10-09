package com.lumacam.core.pose

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Tipos de ángulo de cámara respecto al horizonte, con lo que aporta cada uno. */
enum class AngleCategory(val label: String, val effect: String) {
    TOP_DOWN(
        "Cenital",
        "Cámara justo encima: ideal acostado en el suelo, en la cama o con objetos sobre la mesa.",
    ),
    HIGH(
        "Picado (desde arriba)",
        "Cámara por encima de los ojos: estiliza la cara y la papada; el más favorecedor para selfies y retratos.",
    ),
    EYE(
        "A la altura de los ojos",
        "El más natural: retratos honestos, medio cuerpo y fotos de grupo.",
    ),
    LOW(
        "Contrapicado (desde abajo)",
        "Cámara por debajo: te hace ver más alto, con piernas más largas y más imponente.",
    ),
    BOTTOM_UP(
        "Desde el suelo",
        "Cámara mirando al cielo: efecto épico, perfecto para saltos y edificios.",
    ),
}

data class CameraAngle(
    /** Inclinación del eje de la cámara sobre el horizonte (−90 = mira al suelo, +90 = al cielo). */
    val elevationDeg: Float,
    /** Giro del teléfono respecto a la vertical más cercana (0 = recto); null si está plano. */
    val rollDeg: Float?,
    val category: AngleCategory,
) {
    /** Recto (horizonte nivelado) con margen de 1.5°. */
    val isLevel: Boolean get() = rollDeg != null && abs(rollDeg) <= LEVEL_TOLERANCE

    companion object {
        const val LEVEL_TOLERANCE = 1.5f
    }
}

/**
 * Calcula el ángulo de la cámara a partir del vector de gravedad del teléfono
 * (Sensor.TYPE_GRAVITY o acelerómetro filtrado: apunta "hacia arriba", en m/s²).
 *
 * La cámara trasera mira por −Z del teléfono y la frontal por +Z.
 */
object CameraAngles {
    /** Límites entre categorías (grados de elevación). */
    private val bounds = floatArrayOf(-60f, -12f, 12f, 60f)

    fun elevation(gx: Float, gy: Float, gz: Float, frontCamera: Boolean): Float {
        val n = sqrt(gx * gx + gy * gy + gz * gz)
        if (n < 1e-3f) return 0f
        val axisDotUp = (if (frontCamera) gz else -gz) / n
        return Math.toDegrees(asin(axisDotUp.coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    /** Giro respecto a la vertical u horizontal más cercana; null si el teléfono está casi plano. */
    fun roll(gx: Float, gy: Float, gz: Float): Float? {
        val n = sqrt(gx * gx + gy * gy + gz * gz)
        if (n < 1e-3f) return null
        if (sqrt(gx * gx + gy * gy) < 0.35f * n) return null
        val r = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
        val nearest = (r / 90f).roundToInt() * 90f
        return r - nearest
    }

    fun categoryOf(elevationDeg: Float): AngleCategory = when {
        elevationDeg <= bounds[0] -> AngleCategory.TOP_DOWN
        elevationDeg <= bounds[1] -> AngleCategory.HIGH
        elevationDeg < bounds[2] -> AngleCategory.EYE
        elevationDeg < bounds[3] -> AngleCategory.LOW
        else -> AngleCategory.BOTTOM_UP
    }

    /**
     * Igual que [categoryOf] pero sin parpadeo: para cambiar de categoría hay que pasar el
     * límite por más de [hysteresis] grados.
     */
    fun categoryWithHysteresis(elevationDeg: Float, previous: AngleCategory?, hysteresis: Float = 4f): AngleCategory {
        val raw = categoryOf(elevationDeg)
        if (previous == null || raw == previous) return raw
        // Comprobar si con el margen seguimos dentro de la categoría anterior.
        val idx = previous.ordinal
        val low = if (idx == 0) Float.NEGATIVE_INFINITY else bounds[idx - 1] - hysteresis
        val high = if (idx == AngleCategory.entries.size - 1) Float.POSITIVE_INFINITY else bounds[idx] + hysteresis
        return if (elevationDeg > low && elevationDeg < high) previous else raw
    }
}

/**
 * Suaviza el vector de gravedad (filtro paso bajo) y produce un [CameraAngle] estable.
 */
class AngleTracker(private val alpha: Float = 0.15f) {
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var initialized = false
    private var category: AngleCategory? = null

    fun update(x: Float, y: Float, z: Float, frontCamera: Boolean): CameraAngle {
        if (!initialized) {
            gx = x; gy = y; gz = z
            initialized = true
        } else {
            gx += alpha * (x - gx)
            gy += alpha * (y - gy)
            gz += alpha * (z - gz)
        }
        val elev = CameraAngles.elevation(gx, gy, gz, frontCamera)
        val cat = CameraAngles.categoryWithHysteresis(elev, category)
        category = cat
        return CameraAngle(elev, CameraAngles.roll(gx, gy, gz), cat)
    }

    fun reset() {
        initialized = false
        category = null
    }
}
