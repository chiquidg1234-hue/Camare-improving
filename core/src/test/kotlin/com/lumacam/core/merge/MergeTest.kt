package com.lumacam.core.merge

import com.lumacam.core.TestImages
import com.lumacam.core.color.ColorMath
import com.lumacam.core.image.ArgbImage
import com.lumacam.core.image.LumaImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MergeTest {
    private val w = 480
    private val h = 360
    private val m = 40
    private val big = TestImages.scene(w + 2 * m, h + 2 * m, seed = 11)
    private val clean = TestImages.crop(big, m, m, w, h)

    /** Frame cuyo contenido está desplazado: tgt(x+dx, y+dy) = ref(x, y). */
    private fun frame(dx: Int, dy: Int) = TestImages.crop(big, m - dx, m - dy, w, h)

    @Test
    fun noiseEstimateIsClose() {
        for (sigma in listOf(4f, 8f, 16f)) {
            val noisy = TestImages.addNoise(clean, sigma, seed = sigma.toInt())
            val est = ImageStats.noiseSigma(noisy)
            assertTrue("σ=$sigma estimado=$est", abs(est - sigma) < sigma * 0.35f + 1.5f)
        }
    }

    @Test
    fun alignerFindsKnownShifts() {
        val aligner = Aligner()
        val factor = 2
        val ref = TestImages.addNoise(clean, 6f, 1)
        val refPyr = aligner.pyramid(LumaImage.downsampled(ref, factor), factor)
        for ((dx, dy) in listOf(0 to 0, 7 to -3, -12 to 9, 15 to 15, -1 to 2)) {
            val tgt = TestImages.addNoise(frame(dx, dy), 6f, 100 + dx * 31 + dy)
            val coarse = aligner.alignPyramids(refPyr, aligner.pyramid(LumaImage.downsampled(tgt, factor), factor), w)
            val fine = aligner.refineFullRes(ref, tgt, coarse, factor / 2 + 1)
            assertEquals("dx para ($dx,$dy)", dx, fine.dx)
            assertEquals("dy para ($dx,$dy)", dy, fine.dy)
        }
    }

    @Test
    fun mergingReducesNoise() {
        val sigma = 10f
        val shifts = listOf(0 to 0, 5 to -2, -6 to 4, 3 to 7, -2 to -5, 8 to 1)
        val frames = shifts.mapIndexed { i, (dx, dy) -> TestImages.addNoise(frame(dx, dy), sigma, 1000 + i) }
        val out = ArgbImage(w, h)
        val result = BurstMerger().process(frames, out)
        val refErr = TestImages.rmse(frames[result.refIndex], refCleanFor(result.refIndex, shifts), border = 20)
        val outErr = TestImages.rmse(out, refCleanFor(result.refIndex, shifts), border = 20)
        assertTrue("ruido antes $refErr después $outErr", outErr < refErr / 1.8)
        assertEquals(frames.size, result.usedIndices.size)
    }

    private fun refCleanFor(refIndex: Int, shifts: List<Pair<Int, Int>>): ArgbImage {
        val (dx, dy) = shifts[refIndex]
        return frame(dx, dy)
    }

    @Test
    fun movingObjectDoesNotCreateGhosts() {
        val sigma = 5f
        val frames = (0 until 5).map { TestImages.addNoise(clean, sigma, 2000 + it) }.toMutableList()
        // En el frame 3 aparece un cuadrado blanco que no está en los demás.
        val moved = ArgbImage(w, h, frames[3].pixels.copyOf())
        for (y in 100 until 160) for (x in 200 until 260) moved[x, y] = ColorMath.pack(255, 255, 255)
        frames[3] = moved
        val out = ArgbImage(w, h)
        FrameMerger().merge(frames, List(frames.size) { Shift.ZERO }, 0, ImageStats.noiseSigma(frames[0]), out)
        var maxDiff = 0
        for (y in 105 until 155) for (x in 205 until 255) {
            maxDiff = maxOf(maxDiff, abs(ColorMath.red(out[x, y]) - ColorMath.red(clean[x, y])))
        }
        assertTrue("fantasma: diferencia máxima $maxDiff", maxDiff < 20)
    }

    @Test
    fun blurryFramesAreDiscarded() {
        val sharp = TestImages.addNoise(clean, 2f, 1)
        val l = FloatArray(1)
        // Frame muy borroso: media de bloques 9x9.
        val blurry = ArgbImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (yy in maxOf(0, y - 4)..minOf(h - 1, y + 4)) for (xx in maxOf(0, x - 4)..minOf(w - 1, x + 4)) {
                val p = clean[xx, yy]; r += ColorMath.red(p); g += ColorMath.green(p); b += ColorMath.blue(p); n++
            }
            blurry[x, y] = ColorMath.pack(r / n, g / n, b / n)
        }
        l[0] = 0f
        val res = BurstMerger().process(listOf(blurry, sharp, TestImages.addNoise(clean, 2f, 2)), ArgbImage(w, h))
        assertTrue(0 !in res.usedIndices)
        assertTrue(res.refIndex != 0)
    }

    @Test
    fun singleFrameMergeIsIdentity() {
        val out = ArgbImage(w, h)
        FrameMerger().merge(listOf(clean), listOf(Shift.ZERO), 0, 3f, out)
        assertTrue(out.pixels.contentEquals(clean.pixels))
    }
}
