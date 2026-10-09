package com.lumacam.core.look

import com.lumacam.core.color.ColorMath

/**
 * LUT 3D de [size]³ entradas RGB de 8 bits.
 *
 * En la GPU se sube como un "atlas" 2D de (size*size) x size texeles: la rebanada de azul `b`
 * ocupa las columnas [b*size, (b+1)*size), con rojo en x y verde en y. El shader interpola
 * bilinealmente dentro de dos rebanadas y mezcla entre ellas, lo que equivale exactamente a la
 * interpolación trilineal de [sample].
 */
class Lut3D(val size: Int, val data: ByteArray) {
    init {
        require(size >= 2)
        require(data.size == size * size * size * 3)
    }

    val atlasWidth: Int get() = size * size
    val atlasHeight: Int get() = size

    private fun index(r: Int, g: Int, b: Int) = ((b * size + g) * size + r) * 3

    fun entry(r: Int, g: Int, b: Int, channel: Int): Int =
        data[index(r, g, b) + channel].toInt() and 0xFF

    /** Interpolación trilineal; entrada y salida en [0,1]. */
    fun sample(r: Float, g: Float, b: Float, out: FloatArray) {
        val n1 = size - 1
        val fr = ColorMath.clamp01(r) * n1
        val fg = ColorMath.clamp01(g) * n1
        val fb = ColorMath.clamp01(b) * n1
        val r0 = fr.toInt().coerceAtMost(n1 - 1)
        val g0 = fg.toInt().coerceAtMost(n1 - 1)
        val b0 = fb.toInt().coerceAtMost(n1 - 1)
        val tr = fr - r0
        val tg = fg - g0
        val tb = fb - b0
        for (c in 0 until 3) {
            val c000 = entry(r0, g0, b0, c)
            val c100 = entry(r0 + 1, g0, b0, c)
            val c010 = entry(r0, g0 + 1, b0, c)
            val c110 = entry(r0 + 1, g0 + 1, b0, c)
            val c001 = entry(r0, g0, b0 + 1, c)
            val c101 = entry(r0 + 1, g0, b0 + 1, c)
            val c011 = entry(r0, g0 + 1, b0 + 1, c)
            val c111 = entry(r0 + 1, g0 + 1, b0 + 1, c)
            val x00 = c000 + (c100 - c000) * tr
            val x10 = c010 + (c110 - c010) * tr
            val x01 = c001 + (c101 - c001) * tr
            val x11 = c011 + (c111 - c011) * tr
            val y0 = x00 + (x10 - x00) * tg
            val y1 = x01 + (x11 - x01) * tg
            out[c] = (y0 + (y1 - y0) * tb) / 255f
        }
    }

    /** Atlas RGBA8 (ancho size*size, alto size) listo para glTexImage2D. */
    fun toAtlasRgba(): ByteArray {
        val w = atlasWidth
        val out = ByteArray(w * size * 4)
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val src = index(r, g, b)
            val x = b * size + r
            val dst = (g * w + x) * 4
            out[dst] = data[src]
            out[dst + 1] = data[src + 1]
            out[dst + 2] = data[src + 2]
            out[dst + 3] = 0xFF.toByte()
        }
        return out
    }

    companion object {
        const val DEFAULT_SIZE = 33

        fun identity(size: Int = DEFAULT_SIZE): Lut3D = generate(size) { r, g, b, out ->
            out[0] = r; out[1] = g; out[2] = b
        }

        fun fromGrade(grade: ColorGrade, size: Int = DEFAULT_SIZE): Lut3D =
            generate(size) { r, g, b, out -> grade.apply(r, g, b, out) }

        fun generate(size: Int, fn: (Float, Float, Float, FloatArray) -> Unit): Lut3D {
            val data = ByteArray(size * size * size * 3)
            val tmp = FloatArray(3)
            val n1 = (size - 1).toFloat()
            var i = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                fn(r / n1, g / n1, b / n1, tmp)
                data[i++] = ColorMath.toByte(tmp[0]).toByte()
                data[i++] = ColorMath.toByte(tmp[1]).toByte()
                data[i++] = ColorMath.toByte(tmp[2]).toByte()
            }
            return Lut3D(size, data)
        }
    }
}
