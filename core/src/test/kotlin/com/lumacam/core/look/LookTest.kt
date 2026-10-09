package com.lumacam.core.look

import com.lumacam.core.TestImages
import com.lumacam.core.color.ColorMath
import com.lumacam.core.image.ArgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LookTest {

    private val scene = TestImages.scene(160, 120, seed = 7)

    private fun process(look: ResolvedLook, img: ArgbImage = scene): ArgbImage =
        ArgbImage(img.width, img.height, LookProcessor(look).processImage(img.pixels, img.width, img.height))

    private fun meanSaturation(img: ArgbImage): Double = img.pixels.map { p ->
        val r = ColorMath.red(p); val g = ColorMath.green(p); val b = ColorMath.blue(p)
        (maxOf(r, g, b) - minOf(r, g, b)).toDouble()
    }.average()

    private fun meanLuma(img: ArgbImage, x0: Int = 0, y0: Int = 0, x1: Int = img.width, y1: Int = img.height): Double {
        var s = 0.0
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            s += ColorMath.lumaOf(img[x, y]); n++
        }
        return s / n
    }

    @Test
    fun allPresetsHaveUniqueIdsAndResolve() {
        assertEquals(LookId.entries.size, Looks.ALL.size)
        assertEquals(Looks.ALL.size, Looks.ALL.map { it.id }.toSet().size)
        for (p in Looks.ALL) {
            val r = ResolvedLook.resolve(p, 1f)
            assertEquals(p.id, r.presetId)
            assertFalse("${p.id} no debería ser neutro al 100%", r.isNeutral)
        }
    }

    @Test
    fun byNameFallsBackToNatural() {
        assertEquals(LookId.CINE, Looks.byName("CINE").id)
        assertEquals(LookId.NATURAL, Looks.byName("desconocido").id)
        assertEquals(LookId.NATURAL, Looks.byName(null).id)
    }

    @Test
    fun zeroIntensityWithoutUserAdjustmentsIsNeutral() {
        for (p in Looks.ALL) {
            val r = ResolvedLook.resolve(p, 0f)
            assertTrue("${p.id} al 0% debe ser neutro", r.isNeutral)
            val out = process(r)
            assertEquals(0.0, TestImages.rmse(out, scene), 0.6)
        }
    }

    @Test
    fun neutralLookIsIdentity() {
        val out = process(ResolvedLook.NEUTRAL)
        assertTrue(out.pixels.contentEquals(scene.pixels))
    }

    @Test
    fun intensityScalesTheEffect() {
        for (p in Looks.ALL) {
            val half = TestImages.rmse(process(ResolvedLook.resolve(p, 0.5f)), scene)
            val full = TestImages.rmse(process(ResolvedLook.resolve(p, 1f)), scene)
            assertTrue("${p.id}: 50%=$half 100%=$full", full > half && half > 0.2)
        }
    }

    @Test
    fun naturalIsSubtle() {
        val d = TestImages.rmse(process(ResolvedLook.resolve(Looks.NATURAL, 1f)), scene)
        assertTrue("Natural cambia demasiado: $d", d < 12.0)
    }

    @Test
    fun vividIncreasesAndCineDecreasesSaturation() {
        val base = meanSaturation(scene)
        assertTrue(meanSaturation(process(ResolvedLook.resolve(Looks.VIVID, 1f))) > base * 1.1)
        assertTrue(meanSaturation(process(ResolvedLook.resolve(Looks.CINE, 1f))) < base)
    }

    @Test
    fun warmShiftsTowardRed() {
        val out = process(ResolvedLook.resolve(Looks.WARM, 1f))
        val ratioIn = scene.pixels.map { ColorMath.red(it) - ColorMath.blue(it) }.average()
        val ratioOut = out.pixels.map { ColorMath.red(it) - ColorMath.blue(it) }.average()
        assertTrue(ratioOut > ratioIn + 5)
    }

    @Test
    fun nightLiftsShadows() {
        val dark = ArgbImage(64, 64, IntArray(64 * 64) { ColorMath.pack(40, 40, 45) })
        val out = process(ResolvedLook.resolve(Looks.NIGHT, 1f), dark)
        assertTrue(meanLuma(out) > meanLuma(dark) + 5)
    }

    @Test
    fun userAdjustmentsAddOnTopOfPreset() {
        val p = Looks.NATURAL
        val r = ResolvedLook.resolve(p, 1f, Adjustments(saturation = 0.5f, sharpness = 2f))
        assertEquals(p.base.saturation + 0.5f, r.effective.saturation, 1e-5f)
        assertEquals(1f, r.effective.sharpness, 1e-5f) // recortado a 1
    }

    @Test
    fun vignetteDarkensCornersOnly() {
        val flat = ArgbImage(100, 80, IntArray(100 * 80) { ColorMath.pack(180, 180, 180) })
        val look = ResolvedLook.resolve(Looks.CINE, 1f)
        val out = process(look, flat)
        val corner = meanLuma(out, 0, 0, 8, 8)
        val center = meanLuma(out, 45, 35, 55, 45)
        assertTrue("esquina $corner centro $center", corner < center - 10)
        assertEquals(1f, LookProcessor.vignetteFactor(0.5f, 0.5f, 1f), 1e-6f)
    }

    @Test
    fun stripProcessingMatchesFullImage() {
        val img = TestImages.scene(200, 150, seed = 3)
        val look = ResolvedLook.resolve(Looks.VIVID, 1f, Adjustments(localContrast = 0.6f))
        val proc = LookProcessor(look)
        val full = proc.processImage(img.pixels, img.width, img.height)
        val margin = proc.requiredMargin(img.width, img.height)
        val stripped = IntArray(img.pixels.size)
        val strip = 23
        var y = 0
        while (y < img.height) {
            val rows = minOf(strip, img.height - y)
            val s0 = maxOf(0, y - margin)
            val s1 = minOf(img.height, y + rows + margin)
            val src = IntArray((s1 - s0) * img.width)
            img.readRows(s0, s1 - s0, src, 0)
            val dst = IntArray(rows * img.width)
            proc.processRows(src, s0, s1 - s0, img.width, img.height, y, rows, dst)
            System.arraycopy(dst, 0, stripped, y * img.width, dst.size)
            y += rows
        }
        assertTrue(full.contentEquals(stripped))
    }

    @Test
    fun localContrastIncreasesMidScaleDetail() {
        val img = TestImages.scene(200, 150, seed = 9)
        val look = ResolvedLook.resolve(Looks.NATURAL, 0f, Adjustments(localContrast = 1f))
        val out = process(look, img)
        fun detail(a: ArgbImage): Double {
            val l = IntArray(a.pixels.size) { (ColorMath.lumaOf(a.pixels[it]) * 16).toInt() }
            val b = LookProcessor.blurSeparable(l, a.width, a.height, 4)
            return l.indices.sumOf { abs(l[it] - b[it]).toDouble() } / l.size
        }
        assertTrue(detail(out) > detail(img) * 1.1)
    }

    @Test
    fun sharpeningIncreasesEdgeContrast() {
        val w = 40
        val h = 10
        val edge = ArgbImage(w, h, IntArray(w * h) { i -> if (i % w < w / 2) ColorMath.pack(80, 80, 80) else ColorMath.pack(160, 160, 160) })
        val out = process(ResolvedLook.resolve(Looks.NATURAL, 0f, Adjustments(sharpness = 1f)), edge)
        assertTrue(ColorMath.red(out[w / 2 - 1, 5]) < 80)
        assertTrue(ColorMath.red(out[w / 2, 5]) > 160)
        // Lejos del borde no cambia nada (umbral de ruido).
        assertEquals(80, ColorMath.red(out[3, 5]))
    }
}
