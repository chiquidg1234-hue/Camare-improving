package com.lumacam.core.dual

import kotlin.math.max
import kotlin.math.roundToInt

/** Cómo se reparten las dos cámaras en el cuadro del modo DUAL. */
enum class DualLayout(val label: String) {
    /** Una cámara a pantalla completa y la otra en una ventanita (como BeReal). */
    PIP("Ventana"),
    /** Mitad de arriba y mitad de abajo. */
    SPLIT("Mitades"),
}

enum class PipCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** Rectángulo normalizado (0..1) con origen arriba a la izquierda. */
data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun contains(px: Float, py: Float): Boolean = px >= x && px <= x + w && py >= y && py <= y + h

    /** En píxeles enteros para un lienzo de [canvasW]x[canvasH]: izquierda, arriba, ancho, alto. */
    fun toPixels(canvasW: Int, canvasH: Int): IntArray {
        val l = (x * canvasW).roundToInt()
        val t = (y * canvasH).roundToInt()
        val r = ((x + w) * canvasW).roundToInt()
        val b = ((y + h) * canvasH).roundToInt()
        return intArrayOf(l, t, max(1, r - l), max(1, b - t))
    }

    companion object {
        val FULL = Box(0f, 0f, 1f, 1f)
    }
}

/**
 * Dónde va cada cámara. [main] es la cámara principal; [second] la otra (null = sólo una).
 * [radius] y [border] van en fracción del ancho del lienzo.
 */
data class DualRegions(val main: Box, val second: Box?, val radius: Float, val border: Float)

/**
 * Geometría del modo DUAL, compartida por la vista previa en OpenGL y la foto final en CPU para
 * que lo guardado sea exactamente lo que se vio.
 */
object DualGeometry {
    /** Ancho de la ventanita, en fracción del ancho del lienzo. */
    const val PIP_WIDTH = 0.31f
    /** Proporción de la ventanita (ancho / alto), vertical 3:4. */
    const val PIP_ASPECT = 3f / 4f
    const val PIP_MARGIN = 0.035f
    const val PIP_RADIUS = 0.035f
    const val PIP_BORDER = 0.006f
    /** Grosor de la línea entre mitades, en fracción del alto. */
    const val SPLIT_GAP = 0.004f

    fun regions(layout: DualLayout, canvasW: Int, canvasH: Int, corner: PipCorner, single: Boolean = false): DualRegions {
        if (single) return DualRegions(Box.FULL, null, 0f, 0f)
        return when (layout) {
            DualLayout.PIP -> {
                val w = PIP_WIDTH
                val h = PIP_WIDTH * canvasW / PIP_ASPECT / canvasH
                val mx = PIP_MARGIN
                val my = PIP_MARGIN * canvasW / canvasH
                val x = if (corner == PipCorner.TOP_LEFT || corner == PipCorner.BOTTOM_LEFT) mx else 1f - mx - w
                val y = if (corner == PipCorner.TOP_LEFT || corner == PipCorner.TOP_RIGHT) my else 1f - my - h
                DualRegions(Box.FULL, Box(x, y, w, h), PIP_RADIUS, PIP_BORDER)
            }
            DualLayout.SPLIT -> {
                val g = SPLIT_GAP / 2f
                DualRegions(Box(0f, 0f, 1f, 0.5f - g), Box(0f, 0.5f + g, 1f, 0.5f - g), 0f, 0f)
            }
        }
    }

    /**
     * Ventana visible de una imagen vertical ("derecha") de proporción [imageAspect] (ancho/alto)
     * al llenar una región de proporción [regionAspect], recortando lo que sobra por igual a
     * cada lado. Devuelve (u0, v0, u1, v1) en 0..1, origen arriba a la izquierda.
     */
    fun cropWindow(regionAspect: Float, imageAspect: Float): FloatArray =
        if (imageAspect > regionAspect) {
            val f = regionAspect / imageAspect
            floatArrayOf(0.5f - f / 2f, 0f, 0.5f + f / 2f, 1f)
        } else {
            val f = imageAspect / regionAspect
            floatArrayOf(0f, 0.5f - f / 2f, 1f, 0.5f + f / 2f)
        }

    /**
     * Coordenadas de textura (s, t) de las cuatro esquinas del rectángulo que se dibuja, en el
     * orden de GL_TRIANGLE_STRIP: abajo-izquierda, abajo-derecha, arriba-izquierda, arriba-derecha.
     *
     * La imagen de la cámara llega en el espacio del buffer; para verse derecha hay que girarla
     * [rotation] grados en sentido horario y, si [mirror], reflejarla en horizontal (después de
     * girar). (s, t) es el espacio estándar de una textura (t hacia arriba), sobre el que luego se
     * aplica la matriz del SurfaceTexture.
     */
    fun texCoords(regionAspect: Float, imageAspect: Float, rotation: Int, mirror: Boolean): FloatArray {
        val c = cropWindow(regionAspect, imageAspect)
        val corners = floatArrayOf(
            c[0], c[3], // abajo-izquierda (v hacia abajo: abajo = v1)
            c[2], c[3], // abajo-derecha
            c[0], c[1], // arriba-izquierda
            c[2], c[1], // arriba-derecha
        )
        val out = FloatArray(8)
        for (i in 0 until 4) {
            var ux = corners[i * 2]
            val uy = corners[i * 2 + 1]
            if (mirror) ux = 1f - ux
            val (bx, by) = unrotate(rotation, ux, uy)
            out[i * 2] = bx
            out[i * 2 + 1] = 1f - by
        }
        return out
    }

    /** Punto de la imagen derecha → punto del buffer, si el buffer hay que girarlo [rotation]° (horario). */
    fun unrotate(rotation: Int, ux: Float, uy: Float): Pair<Float, Float> = when ((rotation % 360 + 360) % 360) {
        90 -> uy to 1f - ux
        180 -> 1f - ux to 1f - uy
        270 -> 1f - uy to ux
        else -> ux to uy
    }

    /** Proporción (ancho/alto) de la imagen ya derecha a partir del tamaño del buffer. */
    fun uprightAspect(bufferW: Int, bufferH: Int, rotation: Int): Float {
        if (bufferW <= 0 || bufferH <= 0) return PIP_ASPECT
        val turned = (rotation % 180 + 180) % 180 == 90
        return if (turned) bufferH.toFloat() / bufferW else bufferW.toFloat() / bufferH
    }

    /** ¿Tocar en (x, y) normalizado cae sobre la ventanita? */
    fun hitsSecond(regions: DualRegions, x: Float, y: Float): Boolean = regions.second?.contains(x, y) == true
}
