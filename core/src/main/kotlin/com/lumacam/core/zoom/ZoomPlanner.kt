package com.lumacam.core.zoom

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan

enum class Facing { BACK, FRONT, EXTERNAL }

/** Lo que nos interesa de CameraCharacteristics, sin depender de Android. */
data class CameraDescriptor(
    val id: String,
    val facing: Facing,
    /** LENS_INFO_AVAILABLE_FOCAL_LENGTHS (mm). */
    val focalLengthsMm: List<Float>,
    /** SENSOR_INFO_PHYSICAL_SIZE (mm); 0 si se desconoce. */
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    /** CONTROL_ZOOM_RATIO_RANGE (API 30+); null si no existe. */
    val zoomRatioMin: Float?,
    val zoomRatioMax: Float?,
    /** SCALER_AVAILABLE_MAX_DIGITAL_ZOOM. */
    val maxDigitalZoom: Float = 1f,
    val isLogicalMultiCamera: Boolean = false,
    val physicalIds: List<String> = emptyList(),
    /** Tiene la capacidad BACKWARD_COMPATIBLE (se puede usar como cámara normal). */
    val backwardCompatible: Boolean = true,
    /** Píxeles de la mayor salida JPEG/YUV. */
    val maxPixels: Long = 0,
) {
    /** Diagonal del campo de visión en radianes (0 si faltan datos). */
    val diagonalFovRad: Double
        get() {
            val f = focalLengthsMm.minOrNull() ?: return 0.0
            if (f <= 0f || sensorWidthMm <= 0f || sensorHeightMm <= 0f) return 0.0
            val diag = sqrt((sensorWidthMm * sensorWidthMm + sensorHeightMm * sensorHeightMm).toDouble())
            return 2.0 * atan(diag / (2.0 * f))
        }
}

enum class UltraWideKind {
    /** No hay ultra gran angular accesible. */
    NONE,
    /** La cámara lógica permite zoom < 1.0 (cambia de sensor sola). */
    LOGICAL_ZOOM,
    /** La ultra gran angular es otra cámara independiente. */
    SEPARATE_CAMERA,
}

data class ZoomStop(val label: String, val cameraId: String, val zoomRatio: Float)

data class ZoomPlan(
    /** Cámara con la que arrancar. */
    val cameraId: String,
    val initialZoomRatio: Float,
    /** Rango de zoom de la cámara principal. */
    val minZoomRatio: Float,
    val maxZoomRatio: Float,
    val ultraWide: UltraWideKind,
    val ultraWideCameraId: String?,
    /** Zoom equivalente de la ultra gran angular respecto a la principal (p. ej. 0.6). */
    val ultraWideEquivalent: Float?,
    val mainCameraId: String,
    val stops: List<ZoomStop>,
    /** Aviso para la interfaz y NOTAS.md; null si se pudo arrancar en 0.6x. */
    val warning: String?,
)

/**
 * Decide el zoom inicial. Regla pedida: si hay ultra gran angular real, arrancar en 0.6x; si no,
 * arrancar en 1.0x sin simular un 0.6x falso y avisar.
 */
object ZoomPlanner {
    const val TARGET_WIDE = 0.6f
    /** Una cámara cuenta como ultra gran angular si su zoom equivalente es <= este valor. */
    const val ULTRA_WIDE_MAX_EQUIVALENT = 0.8f
    private const val EPS = 0.01f

    const val WARNING_NO_ULTRAWIDE =
        "Este teléfono no ofrece a las apps una cámara ultra gran angular: el zoom mínimo real " +
            "es 1.0x. No se simula un 0.6x falso."

    fun plan(cameras: List<CameraDescriptor>): ZoomPlan? {
        val back = cameras.filter { it.facing == Facing.BACK && it.backwardCompatible }
        val main = back.firstOrNull() ?: cameras.firstOrNull { it.backwardCompatible } ?: return null
        val mainMin = main.zoomRatioMin ?: 1f
        val mainMax = main.zoomRatioMax ?: main.maxDigitalZoom.coerceAtLeast(1f)

        // Caso 1: la cámara lógica principal ya baja de 1.0x.
        if (mainMin < 1f - EPS) {
            val initial = if (mainMin <= TARGET_WIDE + EPS) TARGET_WIDE else mainMin
            val warning = if (mainMin > TARGET_WIDE + EPS) {
                "La ultra gran angular de este teléfono llega a ${fmt(mainMin)}x (no a 0.6x); se arranca en ${fmt(mainMin)}x."
            } else null
            val stops = buildList {
                add(ZoomStop("${fmt(initial)}x", main.id, initial))
                add(ZoomStop("1x", main.id, 1f))
                if (mainMax >= 2f) add(ZoomStop("2x", main.id, 2f))
            }
            return ZoomPlan(
                cameraId = main.id, initialZoomRatio = initial, minZoomRatio = mainMin, maxZoomRatio = mainMax,
                ultraWide = UltraWideKind.LOGICAL_ZOOM, ultraWideCameraId = main.id,
                ultraWideEquivalent = mainMin, mainCameraId = main.id, stops = stops, warning = warning,
            )
        }

        // Caso 2: otra cámara trasera independiente con campo de visión mucho mayor.
        val mainFov = main.diagonalFovRad
        if (mainFov > 0.0) {
            val uw = back.asSequence()
                .filter { it.id != main.id && it.id !in main.physicalIds }
                .map { it to equivalentZoom(main, it) }
                .filter { (_, eq) -> eq != null && eq <= ULTRA_WIDE_MAX_EQUIVALENT }
                .minByOrNull { (_, eq) -> abs(eq!! - TARGET_WIDE) }
            if (uw != null) {
                val (cam, eq) = uw
                val stops = buildList {
                    add(ZoomStop("${fmt(eq!!)}x", cam.id, 1f))
                    add(ZoomStop("1x", main.id, 1f))
                    if (mainMax >= 2f) add(ZoomStop("2x", main.id, 2f))
                }
                val warning = if (abs(eq!! - TARGET_WIDE) > 0.05f) {
                    "La ultra gran angular equivale a ${fmt(eq)}x (no exactamente 0.6x)."
                } else null
                return ZoomPlan(
                    cameraId = cam.id, initialZoomRatio = 1f, minZoomRatio = mainMin, maxZoomRatio = mainMax,
                    ultraWide = UltraWideKind.SEPARATE_CAMERA, ultraWideCameraId = cam.id,
                    ultraWideEquivalent = eq, mainCameraId = main.id, stops = stops, warning = warning,
                )
            }
        }

        // Caso 3: no hay ultra gran angular. Arrancar en 1.0x y avisar.
        val stops = buildList {
            add(ZoomStop("1x", main.id, 1f))
            if (mainMax >= 2f) add(ZoomStop("2x", main.id, 2f))
        }
        return ZoomPlan(
            cameraId = main.id, initialZoomRatio = 1f.coerceIn(mainMin, mainMax), minZoomRatio = mainMin,
            maxZoomRatio = mainMax, ultraWide = UltraWideKind.NONE, ultraWideCameraId = null,
            ultraWideEquivalent = null, mainCameraId = main.id, stops = stops, warning = WARNING_NO_ULTRAWIDE,
        )
    }

    /** Zoom equivalente de [other] respecto a [main] por campo de visión diagonal. */
    fun equivalentZoom(main: CameraDescriptor, other: CameraDescriptor): Float? {
        val a = main.diagonalFovRad
        val b = other.diagonalFovRad
        if (a <= 0.0 || b <= 0.0) return null
        return (tan(a / 2) / tan(b / 2)).toFloat()
    }

    fun fmt(v: Float): String {
        val r = Math.round(v * 10f) / 10f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
    }
}
