package com.lumacam.core.merge

import com.lumacam.core.color.ColorMath
import com.lumacam.core.image.FrameSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Movimiento afín entre la referencia y un frame: el píxel (x, y) de la referencia está en
 * (x + dx(x,y), y + dy(x,y)) del frame, con
 *   dx = ax + bx·x + cx·y,   dy = ay + by·x + cy·y.
 * Cubre traslación + pequeña rotación/escala (temblor de la mano sin estabilizador óptico).
 */
data class Motion(
    val ax: Float, val bx: Float = 0f, val cx: Float = 0f,
    val ay: Float, val by: Float = 0f, val cy: Float = 0f,
) {
    fun dx(x: Float, y: Float): Float = ax + bx * x + cx * y
    fun dy(x: Float, y: Float): Float = ay + by * x + cy * y

    val isTranslation: Boolean get() = bx == 0f && cx == 0f && by == 0f && cy == 0f

    companion object {
        val IDENTITY = Motion(0f, ay = 0f)
        fun of(s: Shift) = Motion(s.dx.toFloat(), ay = s.dy.toFloat())
    }
}

/**
 * Estima un [Motion] midiendo el desplazamiento local en una rejilla de bloques (a resolución
 * completa, alrededor del desplazamiento global ya conocido) y ajustando un modelo afín por
 * mínimos cuadrados con rechazo de valores atípicos. Si hay pocos bloques con textura o el
 * modelo sale raro, se queda con la traslación global.
 */
class MotionEstimator(
    private val gridX: Int = 8,
    private val gridY: Int = 6,
    private val tile: Int = 96,
    /** Búsqueda local (px) alrededor del desplazamiento global. */
    private val radius: Int = 10,
    /** Rotación/escala máximas aceptadas (pendiente del campo; 0.014 ≈ 0.8°). */
    private val maxSlope: Float = 0.014f,
) {
    data class TileMeasure(val x: Float, val y: Float, val dx: Float, val dy: Float)

    fun measureTiles(ref: FrameSource, tgt: FrameSource, global: Shift): List<TileMeasure> {
        val w = ref.width
        val h = ref.height
        val r = radius
        val margin = max(abs(global.dx), abs(global.dy)) + r + 2
        if (w < tile + 2 * margin + 8 || h < tile + 2 * margin + 8) return emptyList()
        val out = ArrayList<TileMeasure>()
        val refRows = IntArray(tile * w)
        val tgtRows = IntArray((tile + 2 * r) * w)
        val refL = FloatArray(tile * w)
        val tgtL = FloatArray((tile + 2 * r) * w)
        val usableW = w - 2 * margin - tile
        val usableH = h - 2 * margin - tile
        for (gy in 0 until gridY) {
            val y0 = margin + (usableH * (gy + 0.5f) / gridY).toInt()
            val ty0 = y0 + global.dy - r
            if (ty0 < 0 || ty0 + tile + 2 * r > h) continue
            ref.readRows(y0, tile, refRows, 0)
            tgt.readRows(ty0, tile + 2 * r, tgtRows, 0)
            for (i in refL.indices) refL[i] = ColorMath.lumaOf(refRows[i])
            for (i in tgtL.indices) tgtL[i] = ColorMath.lumaOf(tgtRows[i])
            for (gx in 0 until gridX) {
                val x0 = margin + (usableW * (gx + 0.5f) / gridX).toInt()
                if (!hasTexture(refL, w, x0, tile)) continue
                val m = searchTile(refL, tgtL, w, x0, global.dx, r) ?: continue
                out += TileMeasure(
                    x = x0 + tile / 2f, y = y0 + tile / 2f,
                    dx = global.dx + m.first, dy = global.dy + m.second,
                )
            }
        }
        return out
    }

    fun estimate(ref: FrameSource, tgt: FrameSource, global: Shift): Motion {
        val fallback = Motion.of(global)
        val tiles = measureTiles(ref, tgt, global)
        if (tiles.size < 8) return fallback
        var use = tiles
        var model: Motion? = null
        repeat(3) {
            val m = fit(use) ?: return fallback
            model = m
            val kept = use.filter { t -> abs(m.dx(t.x, t.y) - t.dx) <= 1.5f && abs(m.dy(t.x, t.y) - t.dy) <= 1.5f }
            if (kept.size < 8) return fallback
            if (kept.size == use.size) return@repeat
            use = kept
        }
        val m = model ?: return fallback
        if (abs(m.bx) > maxSlope || abs(m.cx) > maxSlope || abs(m.by) > maxSlope || abs(m.cy) > maxSlope) {
            return fallback
        }
        // En el centro el modelo debe coincidir con la traslación global (medida con precisión).
        val cxImg = ref.width / 2f
        val cyImg = ref.height / 2f
        if (abs(m.dx(cxImg, cyImg) - global.dx) > 2.5f || abs(m.dy(cxImg, cyImg) - global.dy) > 2.5f) return fallback
        return m
    }

    /** Mínimos cuadrados para dx y dy como funciones lineales de (x, y). */
    internal fun fit(t: List<TileMeasure>): Motion? {
        // Centrar coordenadas para que el sistema esté bien condicionado.
        val mx = t.sumOf { it.x.toDouble() } / t.size
        val my = t.sumOf { it.y.toDouble() } / t.size
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        var sxdx = 0.0; var sydx = 0.0; var sdx = 0.0
        var sxdy = 0.0; var sydy = 0.0; var sdy = 0.0
        for (p in t) {
            val x = p.x - mx
            val y = p.y - my
            sxx += x * x; sxy += x * y; syy += y * y
            sxdx += x * p.dx; sydx += y * p.dx; sdx += p.dx
            sxdy += x * p.dy; sydy += y * p.dy; sdy += p.dy
        }
        val det = sxx * syy - sxy * sxy
        if (abs(det) < 1e-6) return null
        val bx = (sxdx * syy - sydx * sxy) / det
        val cx = (sydx * sxx - sxdx * sxy) / det
        val by = (sxdy * syy - sydy * sxy) / det
        val cy = (sydy * sxx - sxdy * sxy) / det
        val n = t.size
        val ax = sdx / n - bx * mx - cx * my
        val ay = sdy / n - by * mx - cy * my
        return Motion(ax.toFloat(), bx.toFloat(), cx.toFloat(), ay.toFloat(), by.toFloat(), cy.toFloat())
    }

    private fun hasTexture(l: FloatArray, w: Int, x0: Int, size: Int): Boolean {
        var s = 0.0
        var s2 = 0.0
        var n = 0
        for (y in 0 until size step 4) for (x in x0 until x0 + size step 4) {
            val v = l[y * w + x].toDouble()
            s += v; s2 += v * v; n++
        }
        val mean = s / n
        return s2 / n - mean * mean > 25.0 // desviación > 5 niveles
    }

    /** Busca el desplazamiento (relativo al global) del bloque; null si es ambiguo. */
    private fun searchTile(refL: FloatArray, tgtL: FloatArray, w: Int, x0: Int, gdx: Int, r: Int): Pair<Float, Float>? {
        fun sad(ox: Int, oy: Int, step: Int): Double {
            var s = 0.0
            for (y in 0 until tile step step) {
                val rr = y * w
                val tr = (y + r + oy) * w + gdx + ox
                for (x in x0 until x0 + tile step step) s += abs(refL[rr + x] - tgtL[tr + x])
            }
            return s
        }
        // Grueso: pasos de 2 px con submuestreo 4.
        var best = Double.MAX_VALUE
        var bx = 0
        var by = 0
        var oy = -r
        while (oy <= r) {
            var ox = -r
            while (ox <= r) {
                val s = sad(ox, oy, 4)
                if (s < best) { best = s; bx = ox; by = oy }
                ox += 2
            }
            oy += 2
        }
        // Fino: ±1 px con submuestreo 2.
        val costs = Array(3) { DoubleArray(3) }
        var fbx = bx
        var fby = by
        var fbest = Double.MAX_VALUE
        for (dy in -1..1) for (dx in -1..1) {
            val ox = (bx + dx).coerceIn(-r, r)
            val oyy = (by + dy).coerceIn(-r, r)
            val s = sad(ox, oyy, 2)
            costs[dy + 1][dx + 1] = s
            if (s < fbest) { fbest = s; fbx = ox; fby = oyy }
        }
        if (fbx == -r || fbx == r || fby == -r || fby == r) return null // en el borde: poco fiable
        // Refinado subpíxel con parábola si el mínimo quedó en el centro de la ventana fina.
        var sx = 0f
        var sy = 0f
        if (fbx == bx && fby == by) {
            sx = parabola(costs[1][0], costs[1][1], costs[1][2])
            sy = parabola(costs[0][1], costs[1][1], costs[2][1])
        }
        return (fbx + sx) to (fby + sy)
    }

    private fun parabola(a: Double, b: Double, c: Double): Float {
        val d = a - 2 * b + c
        if (d <= 1e-9) return 0f
        return (0.5 * (a - c) / d).toFloat().coerceIn(-0.5f, 0.5f)
    }

    companion object {
        /** Filas extra (arriba y abajo) necesarias para leer un frame con este movimiento. */
        fun rowSpan(m: Motion, w: Int, y: Int): Pair<Int, Int> {
            val d0 = m.dy(0f, y.toFloat())
            val d1 = m.dy((w - 1).toFloat(), y.toFloat())
            return min(d0, d1).roundToInt() to max(d0, d1).roundToInt()
        }
    }
}
