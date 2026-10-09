package com.lumacam.core.merge

import com.lumacam.core.TestImages
import com.lumacam.core.color.ColorMath
import com.lumacam.core.image.ArgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

class MotionTest {
    private val w = 640
    private val h = 480
    private val m = 60
    private val big = TestImages.scene(w + 2 * m, h + 2 * m, seed = 21)

    /** Referencia: recorte centrado de la escena grande. */
    private val ref = TestImages.crop(big, m, m, w, h)

    /**
     * Frame girado θ alrededor del centro y desplazado (tx, ty): el punto p de la referencia
     * aparece en c + R(θ)(p − c) + t. Se genera con muestreo bilineal inverso.
     */
    private fun transformed(thetaDeg: Double, tx: Double, ty: Double): ArgbImage {
        val th = Math.toRadians(thetaDeg)
        val c = cos(th)
        val s = sin(th)
        val cx = w / 2.0
        val cy = h / 2.0
        val out = ArgbImage(w, h)
        for (v in 0 until h) for (u in 0 until w) {
            // p = c + R(−θ)(q − t − c)
            val qx = u - tx - cx
            val qy = v - ty - cy
            val px = cx + c * qx + s * qy + m
            val py = cy - s * qx + c * qy + m
            out[u, v] = bilinear(px, py)
        }
        return out
    }

    private fun bilinear(x: Double, y: Double): Int {
        val x0 = floor(x).toInt().coerceIn(0, big.width - 2)
        val y0 = floor(y).toInt().coerceIn(0, big.height - 2)
        val fx = (x - x0).coerceIn(0.0, 1.0)
        val fy = (y - y0).coerceIn(0.0, 1.0)
        fun ch(p: Int, sh: Int) = (p ushr sh) and 0xFF
        val p00 = big[x0, y0]; val p10 = big[x0 + 1, y0]; val p01 = big[x0, y0 + 1]; val p11 = big[x0 + 1, y0 + 1]
        val r = IntArray(3)
        for ((i, sh) in listOf(16, 8, 0).withIndex()) {
            val a = ch(p00, sh) + (ch(p10, sh) - ch(p00, sh)) * fx
            val b = ch(p01, sh) + (ch(p11, sh) - ch(p01, sh)) * fx
            r[i] = (a + (b - a) * fy + 0.5).toInt()
        }
        return ColorMath.pack(r[0], r[1], r[2])
    }

    private fun trueDx(thetaDeg: Double, tx: Double, x: Double, y: Double): Double {
        val th = Math.toRadians(thetaDeg)
        return (cos(th) - 1) * (x - w / 2.0) - sin(th) * (y - h / 2.0) + tx
    }

    private fun trueDy(thetaDeg: Double, ty: Double, x: Double, y: Double): Double {
        val th = Math.toRadians(thetaDeg)
        return sin(th) * (x - w / 2.0) + (cos(th) - 1) * (y - h / 2.0) + ty
    }

    @Test
    fun fitRecoversExactAffineField() {
        val truth = Motion(2f, 0.004f, -0.003f, -1f, 0.003f, 0.004f)
        val tiles = (0 until 40).map { i ->
            val x = 50f + (i % 8) * 70f
            val y = 40f + (i / 8) * 80f
            MotionEstimator.TileMeasure(x, y, truth.dx(x, y), truth.dy(x, y))
        }
        val fit = MotionEstimator().fit(tiles)!!
        for ((x, y) in listOf(0f to 0f, 600f to 0f, 0f to 450f, 600f to 450f)) {
            assertEquals(truth.dx(x, y), fit.dx(x, y), 1e-2f)
            assertEquals(truth.dy(x, y), fit.dy(x, y), 1e-2f)
        }
    }

    @Test
    fun estimatesSmallRotationPlusTranslation() {
        val theta = 0.6
        val tx = 6.0
        val ty = -4.0
        val tgt = TestImages.addNoise(transformed(theta, tx, ty), 3f, 7)
        val noisyRef = TestImages.addNoise(ref, 3f, 8)
        val global = Shift(tx.toInt(), ty.toInt())
        val motion = MotionEstimator().estimate(noisyRef, tgt, global)
        assertTrue("debería detectar rotación", !motion.isTranslation)
        for ((x, y) in listOf(20.0 to 20.0, 620.0 to 20.0, 20.0 to 460.0, 620.0 to 460.0, 320.0 to 240.0)) {
            val ex = trueDx(theta, tx, x, y)
            val ey = trueDy(theta, ty, x, y)
            assertEquals("dx en ($x,$y)", ex, motion.dx(x.toFloat(), y.toFloat()).toDouble(), 0.8)
            assertEquals("dy en ($x,$y)", ey, motion.dy(x.toFloat(), y.toFloat()).toDouble(), 0.8)
        }
    }

    @Test
    fun pureTranslationStaysNearTranslation() {
        val tgt = TestImages.addNoise(transformed(0.0, 5.0, 3.0), 3f, 9)
        val motion = MotionEstimator().estimate(TestImages.addNoise(ref, 3f, 10), tgt, Shift(5, 3))
        for ((x, y) in listOf(0f to 0f, 639f to 479f)) {
            assertEquals(5f, motion.dx(x, y), 0.6f)
            assertEquals(3f, motion.dy(x, y), 0.6f)
        }
    }

    @Test
    fun rotatedBurstMergesBetterWithAffineMotion() {
        val sigma = 10f
        val params = listOf(0.0 to (0.0 to 0.0), 0.5 to (4.0 to -3.0), -0.4 to (-5.0 to 2.0), 0.3 to (2.0 to 5.0))
        val frames = params.mapIndexed { i, (th, t) ->
            TestImages.addNoise(if (i == 0) ref else transformed(th, t.first, t.second), sigma, 300 + i)
        }
        val withMotion = ArgbImage(w, h)
        val res = BurstMerger(merger = FrameMerger(FrameMerger.Config(parallelism = 1))).process(frames, withMotion)
        val translationOnly = ArgbImage(w, h)
        BurstMerger(motionEstimator = null).process(frames, translationOnly)

        val refClean = cleanFor(res.refIndex, params)
        // Esquinas (donde la rotación pesa más).
        fun cornerErr(img: ArgbImage): Double {
            var s = 0.0
            var n = 0
            for ((x0, y0) in listOf(10 to 10, w - 90 to 10, 10 to h - 90, w - 90 to h - 90)) {
                val a = TestImages.crop(img, x0, y0, 80, 80)
                val b = TestImages.crop(refClean, x0, y0, 80, 80)
                s += TestImages.rmse(a, b); n++
            }
            return s / n
        }
        val single = cornerErr(frames[res.refIndex])
        val affine = cornerErr(withMotion)
        val trans = cornerErr(translationOnly)
        assertTrue("afín $affine vs traslación $trans", affine < trans)
        assertTrue("afín $affine vs un frame $single", affine < single / 1.4)
    }

    private fun cleanFor(refIndex: Int, params: List<Pair<Double, Pair<Double, Double>>>): ArgbImage {
        if (refIndex == 0) return ref
        val (th, t) = params[refIndex]
        return transformed(th, t.first, t.second)
    }

    @Test
    fun rowSpanCoversRotation() {
        val mo = Motion(0f, 0f, 0f, 2f, 0.01f, 0f)
        val (lo, hi) = MotionEstimator.rowSpan(mo, 1000, 0)
        assertEquals(2, lo)
        assertTrue(abs(hi - 12) <= 1)
    }
}
