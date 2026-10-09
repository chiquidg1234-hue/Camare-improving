package com.lumacam.core.look

import com.lumacam.core.color.ColorMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorTest {

    @Test
    fun neutralWhiteBalanceIsUnity() {
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), WhiteBalance.gains(0f, 0f), 1e-5f)
    }

    @Test
    fun warmTemperatureRaisesRedLowersBlueAndKeepsLuma() {
        val g = WhiteBalance.gains(0.5f, 0f)
        assertTrue(g[0] > 1f)
        assertTrue(g[2] < 1f)
        val l = ColorMath.LUMA_R * g[0] + ColorMath.LUMA_G * g[1] + ColorMath.LUMA_B * g[2]
        assertEquals(1f, l, 1e-4f)
    }

    @Test
    fun tintMagentaLowersGreen() {
        val g = WhiteBalance.gains(0f, 0.6f)
        assertTrue(g[1] < g[0] && g[1] < g[2])
    }

    @Test
    fun identityLutSamplesInput() {
        val lut = Lut3D.identity()
        val out = FloatArray(3)
        for (r in 0..10) for (g in 0..10) for (b in 0..10) {
            lut.sample(r / 10f, g / 10f, b / 10f, out)
            assertEquals(r / 10f, out[0], 1.5f / 255f)
            assertEquals(g / 10f, out[1], 1.5f / 255f)
            assertEquals(b / 10f, out[2], 1.5f / 255f)
        }
    }

    @Test
    fun atlasLayoutMatchesEntries() {
        val lut = Lut3D.fromGrade(Looks.CINE.grade, 9)
        val atlas = lut.toAtlasRgba()
        assertEquals(81 * 9 * 4, atlas.size)
        for (b in 0 until 9) for (g in 0 until 9) for (r in 0 until 9) {
            val x = b * 9 + r
            val i = (g * 81 + x) * 4
            assertEquals(lut.entry(r, g, b, 0), atlas[i].toInt() and 0xFF)
            assertEquals(lut.entry(r, g, b, 1), atlas[i + 1].toInt() and 0xFF)
            assertEquals(lut.entry(r, g, b, 2), atlas[i + 2].toInt() and 0xFF)
            assertEquals(255, atlas[i + 3].toInt() and 0xFF)
        }
    }

    @Test
    fun gradeKeepsGraysNearlyNeutralForNatural() {
        val out = FloatArray(3)
        for (v in listOf(0.1f, 0.5f, 0.9f)) {
            Looks.NATURAL.grade.apply(v, v, v, out)
            assertEquals(v, out[0], 0.01f)
            assertEquals(v, out[1], 0.01f)
            assertEquals(v, out[2], 0.01f)
        }
    }

    @Test
    fun cineGradePushesSkinTowardOrangeAndSkyTowardTeal() {
        val out = FloatArray(3)
        // Tono piel
        Looks.CINE.grade.apply(0.80f, 0.60f, 0.50f, out)
        assertTrue("piel más cálida", out[0] - out[2] > 0.80f - 0.50f)
        // Cielo
        Looks.CINE.grade.apply(0.40f, 0.60f, 0.85f, out)
        assertTrue("cielo menos rojo", out[0] < 0.40f)
    }

    @Test
    fun nightGradeDesaturatesDeepShadows() {
        val out = FloatArray(3)
        Looks.NIGHT.grade.apply(0.15f, 0.05f, 0.05f, out)
        val chromaIn = 0.10f
        val chromaOut = ColorMath.maxOf3(out[0], out[1], out[2]) - ColorMath.minOf3(out[0], out[1], out[2])
        assertTrue(chromaOut < chromaIn * 0.75f)
    }
}
