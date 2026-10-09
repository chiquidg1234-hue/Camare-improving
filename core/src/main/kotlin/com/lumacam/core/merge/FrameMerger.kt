package com.lumacam.core.merge

import com.lumacam.core.color.ColorMath
import com.lumacam.core.image.FrameSink
import com.lumacam.core.image.FrameSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fusión robusta de varios frames alineados para bajar el ruido.
 *
 * Para cada píxel se promedian los frames ponderados por su parecido con la referencia: si un
 * frame difiere mucho más de lo que explica el ruido (algo se movió), su peso baja a 0 y no se
 * generan "fantasmas". La diferencia se mide sobre la media 3x3 de la diferencia de luminancia,
 * mucho menos ruidosa que la diferencia de un solo píxel.
 */
class FrameMerger(private val config: Config = Config()) {

    data class Config(
        val stripRows: Int = 64,
        /** Por debajo de lowK·σd el frame pesa 1; por encima de highK·σd pesa 0. */
        val lowK: Float = 2.0f,
        val highK: Float = 5.0f,
        /** Umbral mínimo (0..255) para no rechazar por el redondeo/artefactos JPEG. */
        val minLow: Float = 2.5f,
    )

    data class Stats(val frames: Int, val meanWeight: Float, val rejectedFraction: Float)

    /**
     * @param frames frames de igual tamaño.
     * @param shifts desplazamiento de cada frame respecto a la referencia (el de la referencia es 0).
     * @param noiseSigma ruido estimado de la referencia (0..255).
     */
    fun merge(
        frames: List<FrameSource>,
        shifts: List<Shift>,
        refIndex: Int,
        noiseSigma: Float,
        sink: FrameSink,
        progress: (Float) -> Unit = {},
    ): Stats {
        require(frames.isNotEmpty() && frames.size == shifts.size)
        val ref = frames[refIndex]
        val w = ref.width
        val h = ref.height
        frames.forEach { require(it.width == w && it.height == h) { "Frames de distinto tamaño" } }

        // σ de la media 3x3 de la diferencia de dos frames con ruido independiente σ.
        val sigmaD = noiseSigma * sqrt(2f) / 3f
        val low = max(config.minLow, config.lowK * sigmaD)
        val high = max(low + 4f, config.highK * sigmaD)
        val invRange = 1f / (high - low)

        val stripMax = config.stripRows
        val padded = stripMax + 2
        val refBuf = IntArray(padded * w)
        val tgtBuf = IntArray(padded * w)
        val refLuma = FloatArray(padded * w)
        val diff = FloatArray(padded * w)
        val diffH = FloatArray(padded * w)
        val valid = BooleanArray(padded * w)
        val accR = FloatArray(stripMax * w)
        val accG = FloatArray(stripMax * w)
        val accB = FloatArray(stripMax * w)
        val accW = FloatArray(stripMax * w)
        val out = IntArray(stripMax * w)
        val readTmp = IntArray(padded * w)

        var weightSum = 0.0
        var weightCount = 0L
        var rejected = 0L

        var y0 = 0
        while (y0 < h) {
            val rows = min(stripMax, h - y0)
            // Filas con 1 de margen para la media 3x3: [y0-1, y0+rows+1) recortado a la imagen.
            val py0 = max(0, y0 - 1)
            val py1 = min(h, y0 + rows + 1)
            val prow = py1 - py0
            val off = y0 - py0 // fila de y0 dentro del buffer con margen

            ref.readRows(py0, prow, refBuf, 0)
            for (i in 0 until prow * w) refLuma[i] = ColorMath.lumaOf(refBuf[i])

            // La referencia siempre pesa 1.
            for (r in 0 until rows) {
                val src = (r + off) * w
                val dst = r * w
                for (x in 0 until w) {
                    val p = refBuf[src + x]
                    accR[dst + x] = ColorMath.red(p).toFloat()
                    accG[dst + x] = ColorMath.green(p).toFloat()
                    accB[dst + x] = ColorMath.blue(p).toFloat()
                    accW[dst + x] = 1f
                }
            }

            for (k in frames.indices) {
                if (k == refIndex) continue
                val s = shifts[k]
                readShifted(frames[k], s, py0, prow, w, h, tgtBuf, valid, readTmp)
                for (i in 0 until prow * w) {
                    diff[i] = if (valid[i]) ColorMath.lumaOf(tgtBuf[i]) - refLuma[i] else 0f
                }
                box3(diff, diffH, w, prow)
                for (r in 0 until rows) {
                    val br = (r + off) * w
                    val dst = r * w
                    for (x in 0 until w) {
                        val bi = br + x
                        if (!valid[bi]) {
                            rejected++
                            weightCount++
                            continue
                        }
                        val d = abs(diff[bi])
                        val wgt = ((high - d) * invRange).coerceIn(0f, 1f)
                        weightSum += wgt
                        weightCount++
                        if (wgt <= 0f) {
                            rejected++
                            continue
                        }
                        val p = tgtBuf[bi]
                        accR[dst + x] += ColorMath.red(p) * wgt
                        accG[dst + x] += ColorMath.green(p) * wgt
                        accB[dst + x] += ColorMath.blue(p) * wgt
                        accW[dst + x] += wgt
                    }
                }
            }

            for (i in 0 until rows * w) {
                val inv = 1f / accW[i]
                out[i] = ColorMath.pack(
                    (accR[i] * inv + 0.5f).toInt(),
                    (accG[i] * inv + 0.5f).toInt(),
                    (accB[i] * inv + 0.5f).toInt(),
                )
            }
            sink.writeRows(y0, rows, out, 0)
            y0 += rows
            progress(y0.toFloat() / h)
        }

        val others = frames.size - 1
        return Stats(
            frames = frames.size,
            meanWeight = if (weightCount == 0L) 1f else (weightSum / weightCount).toFloat(),
            rejectedFraction = if (others == 0 || weightCount == 0L) 0f else rejected.toFloat() / weightCount,
        )
    }

    /**
     * Lee las filas [py0, py0+prow) de la referencia expresadas en el frame desplazado.
     * Marca como inválidos los píxeles que caen fuera del frame.
     */
    private fun readShifted(
        src: FrameSource, s: Shift, py0: Int, prow: Int, w: Int, h: Int,
        dst: IntArray, valid: BooleanArray, tmp: IntArray,
    ) {
        val ty0 = py0 + s.dy
        val first = max(ty0, 0)
        val last = min(ty0 + prow, h) // exclusivo
        java.util.Arrays.fill(valid, 0, prow * w, false)
        if (last <= first) return
        val n = last - first
        val tmpOffset = (first - ty0) * w
        // Leer al principio del buffer temporal y luego recolocar con el desplazamiento horizontal.
        src.readRows(first, n, tmp, 0)
        for (r in 0 until n) {
            val dRow = tmpOffset + r * w
            val sRow = r * w
            for (x in 0 until w) {
                val sx = x + s.dx
                if (sx in 0 until w) {
                    dst[dRow + x] = tmp[sRow + sx]
                    valid[dRow + x] = true
                }
            }
        }
    }

    /** Media 3x3 in-place (usa [tmp]); bordes replicados. */
    private fun box3(a: FloatArray, tmp: FloatArray, w: Int, h: Int) {
        for (y in 0 until h) {
            val b = y * w
            for (x in 0 until w) {
                val l = a[b + max(x - 1, 0)]
                val r = a[b + min(x + 1, w - 1)]
                tmp[b + x] = (l + a[b + x] + r) * (1f / 3f)
            }
        }
        for (y in 0 until h) {
            val up = max(y - 1, 0) * w
            val dn = min(y + 1, h - 1) * w
            val b = y * w
            for (x in 0 until w) a[b + x] = (tmp[up + x] + tmp[b + x] + tmp[dn + x]) * (1f / 3f)
        }
    }
}
