package com.lumacam.core.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class PoseTest {
    private val g = 9.81f

    /** Vector de gravedad ("hacia arriba") con el teléfono vertical inclinado [tiltDeg] (+ = parte de arriba hacia atrás). */
    private fun gravityPortrait(tiltDeg: Float): FloatArray {
        // tilt 0: vertical → (0, g, 0). tilt 90: tumbado boca arriba → (0, 0, g).
        val t = Math.toRadians(tiltDeg.toDouble())
        return floatArrayOf(0f, (g * cos(t)).toFloat(), (g * sin(t)).toFloat())
    }

    @Test
    fun uprightPhoneIsEyeLevelAndLevel() {
        val v = gravityPortrait(0f)
        val e = CameraAngles.elevation(v[0], v[1], v[2], frontCamera = false)
        assertEquals(0f, e, 0.01f)
        assertEquals(AngleCategory.EYE, CameraAngles.categoryOf(e))
        assertEquals(0f, CameraAngles.roll(v[0], v[1], v[2])!!, 0.01f)
    }

    @Test
    fun phoneFlatFaceUpBackCameraLooksDown() {
        val v = gravityPortrait(90f)
        val e = CameraAngles.elevation(v[0], v[1], v[2], frontCamera = false)
        assertEquals(-90f, e, 0.5f)
        assertEquals(AngleCategory.TOP_DOWN, CameraAngles.categoryOf(e))
        assertNull("plano: sin giro definido", CameraAngles.roll(v[0], v[1], v[2]))
    }

    @Test
    fun tiltingTopBackPointsBackCameraDown() {
        // Parte de arriba hacia atrás 30°: la cámara trasera mira 30° hacia abajo (picado).
        val v = gravityPortrait(30f)
        val back = CameraAngles.elevation(v[0], v[1], v[2], frontCamera = false)
        assertEquals(-30f, back, 0.5f)
        assertEquals(AngleCategory.HIGH, CameraAngles.categoryOf(back))
        // La frontal mira hacia arriba (contrapicado para un selfie desde abajo).
        val front = CameraAngles.elevation(v[0], v[1], v[2], frontCamera = true)
        assertEquals(30f, front, 0.5f)
        assertEquals(AngleCategory.LOW, CameraAngles.categoryOf(front))
    }

    @Test
    fun categoriesCoverAllElevations() {
        assertEquals(AngleCategory.TOP_DOWN, CameraAngles.categoryOf(-80f))
        assertEquals(AngleCategory.HIGH, CameraAngles.categoryOf(-30f))
        assertEquals(AngleCategory.EYE, CameraAngles.categoryOf(5f))
        assertEquals(AngleCategory.LOW, CameraAngles.categoryOf(30f))
        assertEquals(AngleCategory.BOTTOM_UP, CameraAngles.categoryOf(80f))
    }

    @Test
    fun hysteresisAvoidsFlicker() {
        // Justo pasado el límite EYE/LOW (12°) sigue en EYE gracias al margen.
        assertEquals(AngleCategory.EYE, CameraAngles.categoryWithHysteresis(14f, AngleCategory.EYE))
        assertEquals(AngleCategory.LOW, CameraAngles.categoryWithHysteresis(17f, AngleCategory.EYE))
        assertEquals(AngleCategory.LOW, CameraAngles.categoryWithHysteresis(10f, AngleCategory.LOW))
        assertEquals(AngleCategory.EYE, CameraAngles.categoryWithHysteresis(7f, AngleCategory.LOW))
    }

    @Test
    fun rollMeasuresTiltFromNearestAxis() {
        val r = Math.toRadians(5.0)
        val roll = CameraAngles.roll((g * sin(r)).toFloat(), (g * cos(r)).toFloat(), 0f)!!
        assertEquals(5f, roll, 0.1f)
        // Horizontal (apaisado) inclinado 3°.
        val r2 = Math.toRadians(93.0)
        assertEquals(3f, CameraAngles.roll((g * sin(r2)).toFloat(), (g * cos(r2)).toFloat(), 0f)!!, 0.1f)
        assertFalse(CameraAngle(0f, 3f, AngleCategory.EYE).isLevel)
        assertTrue(CameraAngle(0f, 1f, AngleCategory.EYE).isLevel)
    }

    @Test
    fun trackerSmoothsNoise() {
        val t = AngleTracker()
        val rnd = Random(3)
        var last: CameraAngle? = null
        repeat(200) {
            val v = gravityPortrait(0f)
            last = t.update(v[0] + rnd.nextFloat() - 0.5f, v[1] + rnd.nextFloat() - 0.5f, v[2] + rnd.nextFloat() - 0.5f, false)
        }
        assertEquals(AngleCategory.EYE, last!!.category)
        assertEquals(0f, last!!.elevationDeg, 2f)
    }

    // ---------- Biblioteca ----------

    @Test
    fun everyCategoryHasAtLeastThreePoses() {
        for (c in AngleCategory.entries) {
            assertTrue("$c para foto", PoseLibrary.forCategory(c, selfie = false).size >= 3)
            assertTrue("$c para selfie", PoseLibrary.forCategory(c, selfie = true).isNotEmpty())
        }
    }

    @Test
    fun templatesAreValid() {
        val ids = PoseLibrary.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (p in PoseLibrary.ALL) {
            assertTrue(p.tips.isNotEmpty() && p.name.isNotBlank())
            assertTrue(p.categories.isNotEmpty())
            assertTrue("${p.id}: necesita hombros", Joint.SHOULDER_L in p.points && Joint.SHOULDER_R in p.points)
            for ((j, pt) in p.points) assertTrue("${p.id} $j fuera", pt.x in 0f..1f && pt.y in 0f..1f)
            // Cada plantilla debe poder compararse (tener ángulos medibles).
            assertNotNull(p.id, PoseMatcher.match(p.points, p).score)
        }
    }

    @Test
    fun selfieOnlyPosesAreHiddenWhenSomeoneElseShoots() {
        val ids = PoseLibrary.forCategory(AngleCategory.HIGH, selfie = false).map { it.id }
        assertFalse("selfie-alto" in ids)
        assertTrue("selfie-alto" in PoseLibrary.forCategory(AngleCategory.HIGH, selfie = true).map { it.id })
    }

    // ---------- Comparación ----------

    private fun transform(p: Map<Joint, Pt>, scale: Float, dx: Float, dy: Float, noise: Float, seed: Int): Map<Joint, Pt> {
        val rnd = Random(seed)
        return p.mapValues { (_, v) ->
            Pt(
                (v.x - 0.5f) * scale + 0.5f + dx + (rnd.nextFloat() - 0.5f) * noise,
                (v.y - 0.5f) * scale + 0.5f + dy + (rnd.nextFloat() - 0.5f) * noise,
            )
        }
    }

    @Test
    fun samePoseScoresHighEvenScaledAndMoved() {
        for (p in PoseLibrary.ALL) {
            val detected = transform(p.points, 0.7f, 0.08f, -0.05f, 0.01f, p.id.hashCode())
            val r = PoseMatcher.match(detected, p)
            assertTrue("${p.id}: ${r.score}", r.score!! > 0.85f)
            assertEquals(0.7f, r.sizeRatio!!, 0.05f)
        }
    }

    @Test
    fun mirroredPoseAlsoMatches() {
        val p = PoseLibrary.byId("mano-cintura")!!
        val r = PoseMatcher.match(p.mirrored().points, p)
        assertTrue(r.score!! > 0.95f)
        assertTrue(r.mirrored)
    }

    @Test
    fun differentPosesScoreLow() {
        val arms = PoseLibrary.byId("brazos-arriba")!!
        val crossed = PoseLibrary.byId("brazos-cruzados")!!
        val r = PoseMatcher.match(crossed.points, arms)
        assertTrue("${r.score}", r.score!! < 0.6f)
        assertNotNull(r.hint)
    }

    @Test
    fun hintPointsToTheArmToRaise() {
        val arms = PoseLibrary.byId("brazos-arriba")!!
        // Igual que la plantilla pero con el brazo de la derecha (pantalla) abajo.
        val detected = arms.points + mapOf(
            Joint.ELBOW_R to Pt(0.62f, 0.40f), Joint.WRIST_R to Pt(0.63f, 0.53f),
        )
        val r = PoseMatcher.match(detected, arms)
        assertTrue(r.hint!!, r.hint!!.startsWith("Sube"))
        assertTrue(r.hint!!, r.hint!!.contains("derecha"))
    }

    @Test
    fun notEnoughBodyGivesNoScore() {
        val p = PoseLibrary.byId("pose-poder")!!
        val onlyHead = mapOf(Joint.NOSE to Pt(0.5f, 0.2f), Joint.SHOULDER_L to Pt(0.4f, 0.3f), Joint.SHOULDER_R to Pt(0.6f, 0.3f))
        val r = PoseMatcher.match(onlyHead, p)
        assertNull(r.score)
        assertNotNull(r.hint)
    }

    @Test
    fun angleHelper() {
        assertEquals(90f, PoseMatcher.angleAt(Pt(0f, 1f), Pt(0f, 0f), Pt(1f, 0f))!!, 1e-3f)
        assertEquals(180f, PoseMatcher.angleAt(Pt(-1f, 0f), Pt(0f, 0f), Pt(1f, 0f))!!, 1e-3f)
        assertNull(PoseMatcher.angleAt(Pt(0f, 0f), Pt(0f, 0f), Pt(1f, 0f)))
    }

    // ---------- Disparo automático ----------

    @Test
    fun triggerFiresAfterHoldAndCountdownOnce() {
        val t = PoseTrigger(threshold = 0.8f, holdMs = 500, countdownMs = 3000, cooldownMs = 2000)
        assertEquals(PoseTrigger.State.Idle, t.update(0.5f, 0))
        assertTrue(t.update(0.9f, 100) is PoseTrigger.State.Holding)
        assertTrue(t.update(0.9f, 700) is PoseTrigger.State.Countdown)
        val c = t.update(0.85f, 1700) as PoseTrigger.State.Countdown
        assertEquals(2, c.secondsLeft(1700))
        // Un pequeño temblor (por encima de umbral − margen) no cancela.
        assertTrue(t.update(0.7f, 2500) is PoseTrigger.State.Countdown)
        assertEquals(PoseTrigger.State.Fire, t.update(0.9f, 3800))
        assertTrue(t.update(0.9f, 3900) is PoseTrigger.State.Cooldown)
        assertTrue(t.update(0.9f, 5000) is PoseTrigger.State.Cooldown)
        assertEquals(PoseTrigger.State.Idle, t.update(0.9f, 6000))
    }

    @Test
    fun triggerCancelsWhenPoseIsLost() {
        val t = PoseTrigger(holdMs = 500, countdownMs = 3000)
        t.update(0.9f, 0)
        t.update(0.9f, 600)
        assertTrue(t.state is PoseTrigger.State.Countdown)
        assertEquals(PoseTrigger.State.Idle, t.update(0.3f, 1000))
        assertEquals(PoseTrigger.State.Idle, t.update(null, 1100))
    }
}
