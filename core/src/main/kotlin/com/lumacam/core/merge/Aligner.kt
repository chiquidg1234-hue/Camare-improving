package com.lumacam.core.merge

import com.lumacam.core.color.ColorMath
import com.lumacam.core.image.FrameSource
import com.lumacam.core.image.LumaImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Desplazamiento entero: el píxel (x, y) de la referencia corresponde a (x+dx, y+dy) del frame. */
data class Shift(val dx: Int, val dy: Int, val error: Float = 0f) {
    companion object {
        val ZERO = Shift(0, 0, 0f)
    }
}

/**
 * Alineación global por traslación (temblor de la mano entre frames), en pirámide:
 * búsqueda exhaustiva en el nivel más grueso, refinamiento ±1 en cada nivel y un ajuste final
 * a resolución completa sobre bandas de filas.
 */
class Aligner(
    /** Desplazamiento máximo buscado, como fracción del ancho. */
    private val maxShiftFrac: Float = 0.04f,
    /** Número de niveles de pirámide por encima de la base. */
    private val extraLevels: Int = 3,
) {
    class Pyramid(val levels: List<LumaImage>, val baseFactor: Int)

    fun pyramid(base: LumaImage, baseFactor: Int): Pyramid {
        val levels = ArrayList<LumaImage>()
        levels += base
        var cur = base
        repeat(extraLevels) {
            if (cur.width >= 32 && cur.height >= 32) {
                cur = cur.half()
                levels += cur
            }
        }
        return Pyramid(levels, baseFactor)
    }

    /** Alineación en la pirámide; devuelve el desplazamiento en píxeles de resolución completa. */
    fun alignPyramids(ref: Pyramid, tgt: Pyramid, fullWidth: Int): Shift {
        require(ref.levels.size == tgt.levels.size && ref.baseFactor == tgt.baseFactor)
        val top = ref.levels.size - 1
        val topFactor = ref.baseFactor shl top
        val radius = max(2, ceil(maxShiftFrac * fullWidth / topFactor).toInt() + 1)
        var best = search(ref.levels[top], tgt.levels[top], 0, 0, radius)
        for (level in top - 1 downTo 0) {
            best = search(ref.levels[level], tgt.levels[level], best.dx * 2, best.dy * 2, 1)
        }
        return Shift(best.dx * ref.baseFactor, best.dy * ref.baseFactor, best.error)
    }

    /**
     * Ajuste fino a resolución completa usando bandas horizontales repartidas por la imagen.
     * [radius] suele ser baseFactor/2 + 1.
     */
    fun refineFullRes(ref: FrameSource, tgt: FrameSource, initial: Shift, radius: Int, bands: Int = 6, bandRows: Int = 24): Shift {
        val w = ref.width
        val h = ref.height
        val margin = max(abs(initial.dx), abs(initial.dy)) + radius + 4
        if (h < bandRows + 2 * margin + 8 || w < 2 * margin + 16) return initial
        val refBand = IntArray(bandRows * w)
        val tgtRows = bandRows + 2 * radius
        val tgtBand = IntArray(tgtRows * w)
        val refLuma = FloatArray(bandRows * w)
        val tgtLuma = FloatArray(tgtRows * w)
        val sums = DoubleArray((2 * radius + 1) * (2 * radius + 1))
        val counts = LongArray(sums.size)
        val usable = h - 2 * margin - bandRows
        for (bIdx in 0 until bands) {
            val y0 = margin + (usable * (bIdx + 0.5f) / bands).toInt()
            val ty0 = y0 + initial.dy - radius
            if (ty0 < 0 || ty0 + tgtRows > h) continue
            ref.readRows(y0, bandRows, refBand, 0)
            tgt.readRows(ty0, tgtRows, tgtBand, 0)
            for (i in refLuma.indices) refLuma[i] = ColorMath.lumaOf(refBand[i])
            for (i in tgtLuma.indices) tgtLuma[i] = ColorMath.lumaOf(tgtBand[i])
            var k = 0
            for (ddy in -radius..radius) for (ddx in -radius..radius) {
                val dx = initial.dx + ddx
                var s = 0.0
                var c = 0L
                for (y in 0 until bandRows step 2) {
                    val ty = y + radius + ddy
                    val rRow = y * w
                    val tRow = ty * w
                    for (x in margin until w - margin step 2) {
                        s += abs(refLuma[rRow + x] - tgtLuma[tRow + x + dx])
                        c++
                    }
                }
                sums[k] += s
                counts[k] += c
                k++
            }
        }
        var bestK = -1
        var bestErr = Double.MAX_VALUE
        for (k in sums.indices) {
            if (counts[k] == 0L) continue
            val e = sums[k] / counts[k]
            if (e < bestErr) {
                bestErr = e; bestK = k
            }
        }
        if (bestK < 0) return initial
        val side = 2 * radius + 1
        val ddy = bestK / side - radius
        val ddx = bestK % side - radius
        return Shift(initial.dx + ddx, initial.dy + ddy, bestErr.toFloat())
    }

    /** Error medio absoluto entre ref(x,y) y tgt(x+dx, y+dy) en la zona de solape. */
    internal fun error(ref: LumaImage, tgt: LumaImage, dx: Int, dy: Int, border: Int): Float {
        val x0 = max(border, border - dx)
        val x1 = min(ref.width - border, tgt.width - border - dx)
        val y0 = max(border, border - dy)
        val y1 = min(ref.height - border, tgt.height - border - dy)
        if (x1 - x0 < 8 || y1 - y0 < 8) return Float.MAX_VALUE
        val step = if ((x1 - x0) * (y1 - y0) > 400_000) 2 else 1
        var s = 0.0
        var c = 0
        var y = y0
        while (y < y1) {
            val rRow = y * ref.width
            val tRow = (y + dy) * tgt.width
            var x = x0
            while (x < x1) {
                s += abs(ref.data[rRow + x] - tgt.data[tRow + x + dx])
                c++
                x += step
            }
            y += step
        }
        return (s / c).toFloat()
    }

    private fun search(ref: LumaImage, tgt: LumaImage, cx: Int, cy: Int, radius: Int): Shift {
        val border = max(2, min(ref.width, ref.height) / 16)
        var best = Shift(cx, cy, Float.MAX_VALUE)
        for (dy in cy - radius..cy + radius) for (dx in cx - radius..cx + radius) {
            val e = error(ref, tgt, dx, dy, border)
            // Desempate hacia el desplazamiento más pequeño (más estable con zonas planas).
            if (e < best.error - 1e-6f ||
                (abs(e - best.error) <= 1e-6f && abs(dx) + abs(dy) < abs(best.dx) + abs(best.dy))
            ) {
                best = Shift(dx, dy, e)
            }
        }
        return best
    }
}
