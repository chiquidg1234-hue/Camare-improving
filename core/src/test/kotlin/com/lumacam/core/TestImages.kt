package com.lumacam.core

import com.lumacam.core.color.ColorMath
import com.lumacam.core.image.ArgbImage
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object TestImages {
    /** Escena sintética con degradados, formas de color y textura fina (determinista). */
    fun scene(w: Int, h: Int, seed: Int = 1): ArgbImage {
        val rnd = Random(seed)
        val img = ArgbImage(w, h)
        val shapes = List(40) {
            Shape(
                cx = rnd.nextInt(w), cy = rnd.nextInt(h), r = 8 + rnd.nextInt(60),
                color = intArrayOf(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)),
                square = rnd.nextBoolean(),
            )
        }
        for (y in 0 until h) for (x in 0 until w) {
            var r = 40f + 150f * x / w
            var g = 60f + 120f * y / h
            var b = 120f + 60f * sin(x * 0.01f + y * 0.02f)
            for (s in shapes) {
                val dx = x - s.cx
                val dy = y - s.cy
                val inside = if (s.square) kotlin.math.abs(dx) < s.r && kotlin.math.abs(dy) < s.r else dx * dx + dy * dy < s.r * s.r
                if (inside) {
                    r = s.color[0].toFloat(); g = s.color[1].toFloat(); b = s.color[2].toFloat()
                }
            }
            val tex = 12f * sin(x * 0.37f) * sin(y * 0.29f)
            img[x, y] = ColorMath.pack((r + tex).toInt(), (g + tex).toInt(), (b + tex).toInt())
        }
        return img
    }

    private class Shape(val cx: Int, val cy: Int, val r: Int, val color: IntArray, val square: Boolean)

    /** Recorte de [src] con esquina en (x0, y0). */
    fun crop(src: ArgbImage, x0: Int, y0: Int, w: Int, h: Int): ArgbImage {
        val out = ArgbImage(w, h)
        for (y in 0 until h) for (x in 0 until w) out[x, y] = src[x0 + x, y0 + y]
        return out
    }

    fun addNoise(src: ArgbImage, sigma: Float, seed: Int): ArgbImage {
        val rnd = java.util.Random(seed.toLong())
        val out = ArgbImage(src.width, src.height)
        for (i in src.pixels.indices) {
            val p = src.pixels[i]
            out.pixels[i] = ColorMath.pack(
                (ColorMath.red(p) + rnd.nextGaussian() * sigma).toInt(),
                (ColorMath.green(p) + rnd.nextGaussian() * sigma).toInt(),
                (ColorMath.blue(p) + rnd.nextGaussian() * sigma).toInt(),
            )
        }
        return out
    }

    fun rmse(a: ArgbImage, b: ArgbImage, border: Int = 0): Double {
        var s = 0.0
        var n = 0
        for (y in border until a.height - border) for (x in border until a.width - border) {
            val p = a[x, y]
            val q = b[x, y]
            val dr = ColorMath.red(p) - ColorMath.red(q)
            val dg = ColorMath.green(p) - ColorMath.green(q)
            val db = ColorMath.blue(p) - ColorMath.blue(q)
            s += (dr * dr + dg * dg + db * db) / 3.0
            n++
        }
        return sqrt(s / n)
    }

    fun gray(v: Int, w: Int = 4, h: Int = 4) = ArgbImage(w, h, IntArray(w * h) { ColorMath.pack(v, v, v) })
}
