package com.lumacam.core.dual

import com.lumacam.core.prompter.Teleprompter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualGeometryTest {
    private val eps = 1e-5f

    @Test
    fun identityWhenAspectsMatchAndNoRotation() {
        val t = DualGeometry.texCoords(regionAspect = 0.75f, imageAspect = 0.75f, rotation = 0, mirror = false)
        // abajo-izq, abajo-der, arriba-izq, arriba-der en (s, t) estándar.
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), t, eps)
    }

    @Test
    fun mirrorSwapsLeftAndRight() {
        val t = DualGeometry.texCoords(0.75f, 0.75f, 0, mirror = true)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 1f, 0f, 1f), t, eps)
    }

    @Test
    fun rotation90TakesUprightTopLeftFromBufferBottomLeft() {
        // Buffer apaisado girado 90° horario: la esquina de arriba-izquierda de la imagen derecha
        // viene de la esquina de abajo-izquierda del buffer, que en (s, t) es (0, 0).
        val t = DualGeometry.texCoords(0.75f, 0.75f, rotation = 90, mirror = false)
        assertEquals(0f, t[4], eps)
        assertEquals(0f, t[5], eps)
        // Y arriba-derecha de la imagen viene de arriba-izquierda del buffer: (0, 1).
        assertEquals(0f, t[6], eps)
        assertEquals(1f, t[7], eps)
    }

    @Test
    fun rotation270IsTheInverseTurn() {
        val t = DualGeometry.texCoords(0.75f, 0.75f, rotation = 270, mirror = false)
        // arriba-izquierda de la imagen ← arriba-derecha del buffer = (1, 1) en (s, t).
        assertEquals(1f, t[4], eps)
        assertEquals(1f, t[5], eps)
    }

    @Test
    fun widerImageIsCroppedLeftAndRight() {
        // Imagen 9:16 en una región 3:4... no: imagen 4:3 (apaisada) en región cuadrada.
        val w = DualGeometry.cropWindow(regionAspect = 1f, imageAspect = 4f / 3f)
        assertEquals(0.125f, w[0], eps)
        assertEquals(0f, w[1], eps)
        assertEquals(0.875f, w[2], eps)
        assertEquals(1f, w[3], eps)
    }

    @Test
    fun tallerImageIsCroppedTopAndBottom() {
        // Imagen 9:16 en región 3:4: se ve el centro en vertical.
        val w = DualGeometry.cropWindow(regionAspect = 0.75f, imageAspect = 9f / 16f)
        assertEquals(0f, w[0], eps)
        assertEquals(1f, w[2], eps)
        val visible = w[3] - w[1]
        assertEquals((9f / 16f) / 0.75f, visible, eps)
        assertEquals(0.5f, (w[1] + w[3]) / 2f, eps)
    }

    @Test
    fun uprightAspectSwapsForQuarterTurns() {
        assertEquals(0.75f, DualGeometry.uprightAspect(1440, 1080, 90), eps)
        assertEquals(0.75f, DualGeometry.uprightAspect(1440, 1080, 270), eps)
        assertEquals(4f / 3f, DualGeometry.uprightAspect(1440, 1080, 0), eps)
    }

    @Test
    fun pipSitsInTheChosenCornerAndIsPortrait() {
        val r = DualGeometry.regions(DualLayout.PIP, 1080, 1440, PipCorner.TOP_LEFT)
        val s = r.second!!
        assertEquals(Box.FULL, r.main)
        assertEquals(DualGeometry.PIP_MARGIN, s.x, eps)
        assertTrue(s.y > 0f && s.y < 0.1f)
        // Proporción 3:4 en píxeles.
        assertEquals(0.75f, (s.w * 1080) / (s.h * 1440), 1e-3f)
        val br = DualGeometry.regions(DualLayout.PIP, 1080, 1440, PipCorner.BOTTOM_RIGHT).second!!
        assertEquals(1f - DualGeometry.PIP_MARGIN, br.x + br.w, eps)
        assertTrue(br.y + br.h < 1f)
    }

    @Test
    fun splitUsesTwoHalvesWithAGap() {
        val r = DualGeometry.regions(DualLayout.SPLIT, 1080, 1920, PipCorner.TOP_LEFT)
        assertEquals(0f, r.main.y, eps)
        val s = r.second!!
        assertTrue(s.y > r.main.y + r.main.h)
        assertEquals(1f, s.y + s.h, eps)
    }

    @Test
    fun singleHasNoSecondRegion() {
        val r = DualGeometry.regions(DualLayout.PIP, 1080, 1920, PipCorner.TOP_LEFT, single = true)
        assertNull(r.second)
        assertFalse(DualGeometry.hitsSecond(r, 0.1f, 0.1f))
    }

    @Test
    fun tapOnPipIsDetected() {
        val r = DualGeometry.regions(DualLayout.PIP, 1080, 1440, PipCorner.TOP_LEFT)
        assertTrue(DualGeometry.hitsSecond(r, 0.1f, 0.1f))
        assertFalse(DualGeometry.hitsSecond(r, 0.8f, 0.8f))
    }

    @Test
    fun boxToPixels() {
        val px = Box(0.5f, 0.25f, 0.5f, 0.5f).toPixels(1000, 800)
        assertArrayEquals(intArrayOf(500, 200, 500, 400), px)
    }

    // ---------- Teleprompter ----------

    @Test
    fun countsWordsWithAccentsAndPunctuation() {
        assertEquals(6, Teleprompter.wordCount("Hola, ¿cómo estás? Bien, gracias... ¡vamos!"))
        assertEquals(0, Teleprompter.wordCount("  \n ¿? "))
        assertEquals(3, Teleprompter.wordCount("auto-escuela 2026 l'été"))
    }

    @Test
    fun durationAndSpeedFollowWordsPerMinute() {
        assertEquals(60f, Teleprompter.durationSeconds(130, 130), eps)
        assertEquals(30f, Teleprompter.durationSeconds(130, 260), eps)
        // 1300 px de texto en 60 s → ~21.7 px/s.
        assertEquals(1300f / 60f, Teleprompter.scrollSpeed(1300f, 130, 130), 1e-3f)
        assertEquals(0f, Teleprompter.scrollSpeed(0f, 100, 130), eps)
        // Velocidades fuera de rango se acotan.
        assertEquals(Teleprompter.durationSeconds(100, Teleprompter.MAX_WPM), Teleprompter.durationSeconds(100, 9999), eps)
    }

    @Test
    fun formatsDurationAndTitles() {
        assertEquals("1:05", Teleprompter.formatDuration(65f))
        assertEquals("0:00", Teleprompter.formatDuration(-3f))
        assertEquals("Mi discurso", Teleprompter.titleFor("\n  Mi discurso  \nresto"))
        assertEquals("Guion sin título", Teleprompter.titleFor("   "))
        assertTrue(Teleprompter.titleFor("x".repeat(100)).endsWith("…"))
        assertTrue(Teleprompter.wordCount(Teleprompter.SAMPLE) > 40)
    }
}
