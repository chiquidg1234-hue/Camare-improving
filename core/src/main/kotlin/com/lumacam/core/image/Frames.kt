package com.lumacam.core.image

/** Fuente de píxeles ARGB leída por filas (en Android la implementa un Bitmap). */
interface FrameSource {
    val width: Int
    val height: Int

    /** Copia las filas [y0, y0 + rows) en [dst] a partir de [dstOffset]. */
    fun readRows(y0: Int, rows: Int, dst: IntArray, dstOffset: Int = 0)
}

/** Destino de píxeles ARGB escrito por filas. */
interface FrameSink {
    fun writeRows(y0: Int, rows: Int, src: IntArray, srcOffset: Int = 0)
}

/** Imagen ARGB en memoria; útil en pruebas y para imágenes pequeñas. */
class ArgbImage(override val width: Int, override val height: Int, val pixels: IntArray = IntArray(width * height)) :
    FrameSource, FrameSink {
    init {
        require(pixels.size == width * height)
    }

    override fun readRows(y0: Int, rows: Int, dst: IntArray, dstOffset: Int) {
        System.arraycopy(pixels, y0 * width, dst, dstOffset, rows * width)
    }

    override fun writeRows(y0: Int, rows: Int, src: IntArray, srcOffset: Int) {
        System.arraycopy(src, srcOffset, pixels, y0 * width, rows * width)
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
    operator fun set(x: Int, y: Int, v: Int) {
        pixels[y * width + x] = v
    }
}

/** Imagen de luminancia en float (0..255) para alineación y estimaciones. */
class LumaImage(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height)) {
    init {
        require(data.size == width * height)
    }

    operator fun get(x: Int, y: Int): Float = data[y * width + x]

    /** Reducción 2x por promedio de bloques 2x2. */
    fun half(): LumaImage {
        val w = width / 2
        val h = height / 2
        val out = LumaImage(w, h)
        for (y in 0 until h) {
            val r0 = 2 * y * width
            val r1 = r0 + width
            for (x in 0 until w) {
                val x2 = 2 * x
                out.data[y * w + x] = (data[r0 + x2] + data[r0 + x2 + 1] + data[r1 + x2] + data[r1 + x2 + 1]) * 0.25f
            }
        }
        return out
    }

    companion object {
        /**
         * Luminancia reducida [factor] veces (promedio de bloques factor×factor) leyendo la fuente
         * por franjas para no cargar la imagen completa.
         */
        fun downsampled(src: FrameSource, factor: Int): LumaImage {
            require(factor >= 1)
            val w = src.width / factor
            val h = src.height / factor
            val out = LumaImage(w, h)
            val rowBuf = IntArray(src.width * factor)
            val acc = FloatArray(w)
            val norm = 1f / (factor * factor)
            for (y in 0 until h) {
                src.readRows(y * factor, factor, rowBuf, 0)
                java.util.Arrays.fill(acc, 0f)
                for (fy in 0 until factor) {
                    val base = fy * src.width
                    for (x in 0 until w) {
                        var s = 0f
                        val bx = base + x * factor
                        for (fx in 0 until factor) {
                            s += com.lumacam.core.color.ColorMath.lumaOf(rowBuf[bx + fx])
                        }
                        acc[x] += s
                    }
                }
                for (x in 0 until w) out.data[y * w + x] = acc[x] * norm
            }
            return out
        }
    }
}
