package com.lumacam.core.look

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ToneCurveTest {

    @Test
    fun neutralParamsGiveIdentity() {
        val c = ToneCurve.build()
        assertTrue(c.isIdentity)
        for (i in 0..100) {
            val x = i / 100f
            assertEquals(x, c.eval(x), 1e-4f)
        }
    }

    @Test
    fun curveIsMonotonicForRandomParams() {
        val rnd = Random(42)
        repeat(500) {
            val c = ToneCurve.build(
                contrast = rnd.nextFloat() * 2 - 1,
                shadows = rnd.nextFloat() * 2 - 1,
                highlights = rnd.nextFloat() * 2 - 1,
                fade = rnd.nextFloat() * 0.3f,
                shoulder = rnd.nextFloat() * 0.3f,
            )
            for (i in 1 until ToneCurve.SIZE) {
                assertTrue("no monótona en $i", c.values16[i] >= c.values16[i - 1])
            }
        }
    }

    @Test
    fun extremeParamsStayMonotonicAndInRange() {
        for (contrast in listOf(-1f, 1f)) for (shadows in listOf(-1f, 1f)) for (highlights in listOf(-1f, 1f)) {
            val c = ToneCurve.build(contrast, shadows, highlights, fade = 0.3f, shoulder = 0.3f)
            for (i in 1 until ToneCurve.SIZE) assertTrue(c.values16[i] >= c.values16[i - 1])
            assertTrue(c.values16.all { it in 0..65535 })
        }
    }

    @Test
    fun contrastDarkensShadowsAndBrightensHighlights() {
        val c = ToneCurve.build(contrast = 0.8f)
        assertTrue(c.eval(0.25f) < 0.25f)
        assertTrue(c.eval(0.75f) > 0.75f)
        assertEquals(0.5f, c.eval(0.5f), 0.002f)
    }

    @Test
    fun shadowsLiftAndHighlightsRecover() {
        val c = ToneCurve.build(shadows = 1f, highlights = -1f)
        assertTrue(c.eval(0.25f) > 0.3f)
        assertTrue(c.eval(0.75f) < 0.7f)
    }

    @Test
    fun fadeLiftsBlackAndShoulderLowersWhite() {
        val c = ToneCurve.build(fade = 0.05f, shoulder = 0.08f)
        assertEquals(0.05f, c.eval(0f), 1e-3f)
        assertEquals(0.92f, c.eval(1f), 1e-3f)
    }

    @Test
    fun textureEncodesSixteenBitValues() {
        val c = ToneCurve.build(contrast = 0.3f, shadows = 0.2f)
        val tex = c.toRgbaTexture()
        assertEquals(256 * 4, tex.size)
        for (i in 0 until 256) {
            val hi = tex[i * 4].toInt() and 0xFF
            val lo = tex[i * 4 + 1].toInt() and 0xFF
            assertEquals(c.values16[i], hi * 256 + lo)
        }
    }
}
