package com.lumacam.core.look

import com.lumacam.core.color.ColorMath
import com.lumacam.core.image.ArgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class ChromaDenoiserTest {
    private val w = 200
    private val h = 160

    /** Fondo gris + cuadrado rojo nítido, con ruido sólo de color (luminancia intacta). */
    private fun scene(chromaNoise: Float, seed: Long = 1): Pair<ArgbImage, ArgbImage> {
        val rnd = java.util.Random(seed)
        val clean = ArgbImage(w, h)
        val noisy = ArgbImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val inSquare = x in 60 until 140 && y in 40 until 120
            val (r, g, b) = if (inSquare) Triple(200, 50, 50) else Triple(120, 120, 120)
            clean[x, y] = ColorMath.pack(r, g, b)
            // Ruido en Cb/Cr: desplazar R y B en sentidos opuestos y compensar G para la luma.
            val n1 = (rnd.nextGaussian() * chromaNoise).toFloat()
            val n2 = (rnd.nextGaussian() * chromaNoise).toFloat()
            val nr = r + n1
            val nb = b + n2
            val ng = g - (0.299f * n1 + 0.114f * n2) / 0.587f
            noisy[x, y] = ColorMath.pack(nr.toInt(), ng.toInt(), nb.toInt())
        }
        return clean to noisy
    }

    private fun chromaStd(img: ArgbImage, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val vals = ArrayList<Double>()
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = img[x, y]
            vals += (ColorMath.red(p) - ColorMath.blue(p)).toDouble()
        }
        val m = vals.average()
        return sqrt(vals.sumOf { (it - m) * (it - m) } / vals.size)
    }

    @Test
    fun reducesChromaNoiseInFlatAreas() {
        val (_, noisy) = scene(12f)
        val cd = ChromaDenoiser(1f, ChromaDenoiser.radiusFor(w, h).coerceAtLeast(4))
        val out = ArgbImage(w, h, cd.processImage(noisy.pixels, w, h))
        val before = chromaStd(noisy, 5, 5, 50, 35)
        val after = chromaStd(out, 5, 5, 50, 35)
        assertTrue("antes $before después $after", after < before / 2.5)
    }

    @Test
    fun keepsColorEdgesAlignedWithLuminanceEdges() {
        val (clean, noisy) = scene(6f)
        val cd = ChromaDenoiser(1f, 4)
        val out = ArgbImage(w, h, cd.processImage(noisy.pixels, w, h))
        // Dos píxeles dentro del borde del cuadrado el rojo debe seguir siendo rojo.
        val inside = out[62, 80]
        val outside = out[57, 80]
        assertTrue(ColorMath.red(inside) - ColorMath.blue(inside) > 100)
        assertTrue(abs(ColorMath.red(outside) - ColorMath.blue(outside)) < 25)
        // El centro del cuadrado queda cerca del color limpio.
        val c = out[100, 80]
        assertEquals(ColorMath.red(clean[100, 80]).toDouble(), ColorMath.red(c).toDouble(), 8.0)
    }

    @Test
    fun luminanceIsPreserved() {
        val (_, noisy) = scene(10f)
        val out = ChromaDenoiser(1f, 4).processImage(noisy.pixels, w, h)
        var maxDiff = 0f
        for (i in out.indices) {
            val a = 0.299f * ColorMath.red(noisy.pixels[i]) + 0.587f * ColorMath.green(noisy.pixels[i]) + 0.114f * ColorMath.blue(noisy.pixels[i])
            val b = 0.299f * ColorMath.red(out[i]) + 0.587f * ColorMath.green(out[i]) + 0.114f * ColorMath.blue(out[i])
            maxDiff = maxOf(maxDiff, abs(a - b))
        }
        assertTrue("diferencia de luma $maxDiff", maxDiff <= 2.5f)
    }

    @Test
    fun zeroStrengthIsNearIdentity() {
        val (_, noisy) = scene(10f)
        val out = ChromaDenoiser(0f, 4).processImage(noisy.pixels, w, h)
        for (i in out.indices) {
            assertTrue(abs(ColorMath.red(out[i]) - ColorMath.red(noisy.pixels[i])) <= 1)
            assertTrue(abs(ColorMath.blue(out[i]) - ColorMath.blue(noisy.pixels[i])) <= 1)
        }
    }

    @Test
    fun stripProcessingMatchesFullImage() {
        val (_, noisy) = scene(10f)
        val cd = ChromaDenoiser(0.8f, 3)
        val full = cd.processImage(noisy.pixels, w, h)
        val m = cd.requiredMargin
        val out = IntArray(w * h)
        var y = 0
        while (y < h) {
            val rows = minOf(37, h - y)
            val s0 = maxOf(0, y - m)
            val s1 = minOf(h, y + rows + m)
            val src = IntArray((s1 - s0) * w)
            noisy.readRows(s0, s1 - s0, src, 0)
            val dst = IntArray(rows * w)
            cd.processRows(src, s0, s1 - s0, w, y, rows, dst)
            System.arraycopy(dst, 0, out, y * w, dst.size)
            y += rows
        }
        var maxDiff = 0
        for (i in out.indices) {
            maxDiff = maxOf(maxDiff, abs(ColorMath.red(out[i]) - ColorMath.red(full[i])), abs(ColorMath.blue(out[i]) - ColorMath.blue(full[i])))
        }
        assertTrue("diferencia por franjas $maxDiff", maxDiff <= 1)
    }

    @Test
    fun strengthAdaptsToNoise() {
        assertEquals(0.3f * 0.85f, ChromaDenoiser.adaptiveStrength(0.85f, 0.5f), 1e-4f)
        assertEquals(0.85f, ChromaDenoiser.adaptiveStrength(0.85f, 6f), 1e-4f)
    }

    @Test
    fun nightLookHasStrongestNoiseReduction() {
        val night = ResolvedLook.resolve(Looks.NIGHT, 1f).chromaDenoise
        for (p in Looks.ALL) assertTrue(ResolvedLook.resolve(p, 1f).chromaDenoise <= night)
        assertEquals(0f, ResolvedLook.resolve(Looks.NIGHT, 0f).chromaDenoise, 0f)
    }
}
