package com.lumacam.core.pose

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Compara una pose detectada (puntos en coordenadas de pantalla, normalizadas 0..1) con una
 * plantilla usando ÁNGULOS de las articulaciones: no importa el tamaño ni la posición de la
 * persona en el encuadre, sólo la forma del cuerpo. También prueba la plantilla en espejo, así
 * se puede hacer la pose con cualquiera de los dos lados.
 */
object PoseMatcher {

    /** Ángulo que se compara: articulación central y sus dos vecinas. */
    enum class Measure(val a: Joint, val b: Joint, val c: Joint, val weight: Float, val part: String) {
        ELBOW_L(Joint.SHOULDER_L, Joint.ELBOW_L, Joint.WRIST_L, 1f, "el codo de la izquierda"),
        ELBOW_R(Joint.SHOULDER_R, Joint.ELBOW_R, Joint.WRIST_R, 1f, "el codo de la derecha"),
        SHOULDER_L(Joint.HIP_L, Joint.SHOULDER_L, Joint.ELBOW_L, 1.2f, "el brazo de la izquierda"),
        SHOULDER_R(Joint.HIP_R, Joint.SHOULDER_R, Joint.ELBOW_R, 1.2f, "el brazo de la derecha"),
        HIP_L(Joint.SHOULDER_L, Joint.HIP_L, Joint.KNEE_L, 0.8f, "la pierna de la izquierda"),
        HIP_R(Joint.SHOULDER_R, Joint.HIP_R, Joint.KNEE_R, 0.8f, "la pierna de la derecha"),
        KNEE_L(Joint.HIP_L, Joint.KNEE_L, Joint.ANKLE_L, 0.8f, "la rodilla de la izquierda"),
        KNEE_R(Joint.HIP_R, Joint.KNEE_R, Joint.ANKLE_R, 0.8f, "la rodilla de la derecha"),
    }

    const val ANGLE_TOLERANCE = 40f
    const val TORSO_TOLERANCE = 25f
    private const val TORSO_WEIGHT = 1f

    data class Result(
        /** 0..1; null si no se ve suficiente cuerpo para comparar. */
        val score: Float?,
        /** Si la mejor coincidencia fue con la plantilla en espejo. */
        val mirrored: Boolean,
        /** Consejo para acercarse a la pose (el peor ángulo), o null si va bien. */
        val hint: String?,
        /** Tamaño de la persona respecto a la silueta (torso detectado / torso de plantilla). */
        val sizeRatio: Float?,
    )

    fun angleAt(a: Pt, b: Pt, c: Pt): Float? {
        val v1x = a.x - b.x
        val v1y = a.y - b.y
        val v2x = c.x - b.x
        val v2y = c.y - b.y
        val n1 = sqrt(v1x * v1x + v1y * v1y)
        val n2 = sqrt(v2x * v2x + v2y * v2y)
        if (n1 < 1e-4f || n2 < 1e-4f) return null
        val cos = ((v1x * v2x + v1y * v2y) / (n1 * n2)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }

    /** Inclinación del tronco (centro de caderas → centro de hombros) respecto a la vertical. */
    fun torsoLean(p: Map<Joint, Pt>): Float? {
        val sl = p[Joint.SHOULDER_L] ?: return null
        val sr = p[Joint.SHOULDER_R] ?: return null
        val hl = p[Joint.HIP_L] ?: return null
        val hr = p[Joint.HIP_R] ?: return null
        val dx = (sl.x + sr.x) / 2 - (hl.x + hr.x) / 2
        val dy = (sl.y + sr.y) / 2 - (hl.y + hr.y) / 2
        return Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
    }

    fun torsoLength(p: Map<Joint, Pt>): Float? {
        val sl = p[Joint.SHOULDER_L] ?: return null
        val sr = p[Joint.SHOULDER_R] ?: return null
        val hl = p[Joint.HIP_L] ?: return null
        val hr = p[Joint.HIP_R] ?: return null
        val dx = (sl.x + sr.x) / 2 - (hl.x + hr.x) / 2
        val dy = (sl.y + sr.y) / 2 - (hl.y + hr.y) / 2
        return sqrt(dx * dx + dy * dy)
    }

    fun match(detected: Map<Joint, Pt>, template: PoseTemplate): Result {
        val direct = compare(detected, template.points)
        val mirror = compare(detected, template.mirrored().points)
        val bestIsMirror = (mirror.score ?: -1f) > (direct.score ?: -1f)
        val best = if (bestIsMirror) mirror else direct
        val ratio = run {
            val d = torsoLength(detected)
            val t = torsoLength(template.points)
            if (d != null && t != null && t > 1e-4f) d / t else null
        }
        return Result(best.score, bestIsMirror, best.hint, ratio)
    }

    private class Partial(val score: Float?, val hint: String?)

    private fun compare(detected: Map<Joint, Pt>, target: Map<Joint, Pt>): Partial {
        var sum = 0f
        var weights = 0f
        var possible = 0f
        var worst = 1f
        var worstHint: String? = null
        for (m in Measure.entries) {
            val ta = target[m.a] ?: continue
            val tb = target[m.b] ?: continue
            val tc = target[m.c] ?: continue
            val targetAngle = angleAt(ta, tb, tc) ?: continue
            possible += m.weight
            val da = detected[m.a] ?: continue
            val db = detected[m.b] ?: continue
            val dc = detected[m.c] ?: continue
            val angle = angleAt(da, db, dc) ?: continue
            val diff = angle - targetAngle
            val sim = (1f - abs(diff) / ANGLE_TOLERANCE).coerceIn(0f, 1f)
            sum += sim * m.weight
            weights += m.weight
            if (sim < worst) {
                worst = sim
                worstHint = hintFor(m, diff)
            }
        }
        val tLean = torsoLean(target)
        if (tLean != null) {
            possible += TORSO_WEIGHT
            val dLean = torsoLean(detected)
            if (dLean != null) {
                val diff = dLean - tLean
                val sim = (1f - abs(diff) / TORSO_TOLERANCE).coerceIn(0f, 1f)
                sum += sim * TORSO_WEIGHT
                weights += TORSO_WEIGHT
                if (sim < worst) {
                    worst = sim
                    worstHint = if (diff > 0) "Inclina el cuerpo hacia la izquierda" else "Inclina el cuerpo hacia la derecha"
                }
            }
        }
        // Hace falta ver al menos la mitad de lo que la plantilla compara.
        if (possible == 0f || weights < possible * 0.5f) return Partial(null, "No te veo completo: aléjate o encuadra mejor")
        val score = sum / weights
        return Partial(score, if (worst < 0.75f) worstHint else null)
    }

    private fun hintFor(m: Measure, diff: Float): String = when (m) {
        Measure.ELBOW_L, Measure.ELBOW_R ->
            if (diff > 0) "Dobla más ${m.part}" else "Estira más ${m.part}"
        Measure.SHOULDER_L, Measure.SHOULDER_R ->
            if (diff > 0) "Baja ${m.part}" else "Sube ${m.part}"
        Measure.HIP_L, Measure.HIP_R ->
            if (diff > 0) "Abre o dobla más ${m.part}" else "Junta o estira ${m.part}"
        Measure.KNEE_L, Measure.KNEE_R ->
            if (diff > 0) "Dobla más ${m.part}" else "Estira ${m.part}"
    }
}

/**
 * Disparo automático cuando la pose coincide: hay que mantenerla [holdMs], luego cuenta atrás
 * de [countdownMs] y dispara una sola vez. Después espera [cooldownMs] antes de volver a armar.
 */
class PoseTrigger(
    private val threshold: Float = 0.8f,
    private val holdMs: Long = 700,
    private val countdownMs: Long = 3000,
    private val cooldownMs: Long = 4000,
    /** Margen para no cancelar la cuenta atrás por un temblor. */
    private val release: Float = 0.15f,
) {
    sealed interface State {
        data object Idle : State
        data class Holding(val since: Long) : State
        data class Countdown(val endsAt: Long) : State {
            fun secondsLeft(now: Long): Int = ((endsAt - now + 999) / 1000).toInt().coerceAtLeast(0)
        }
        data object Fire : State
        data class Cooldown(val until: Long) : State
    }

    var state: State = State.Idle
        private set

    /** Actualiza con la puntuación actual (null = no se ve a la persona). Devuelve el nuevo estado. */
    fun update(score: Float?, now: Long): State {
        val s = score ?: 0f
        state = when (val st = state) {
            State.Idle -> if (s >= threshold) State.Holding(now) else State.Idle
            is State.Holding -> when {
                s < threshold -> State.Idle
                now - st.since >= holdMs -> State.Countdown(now + countdownMs)
                else -> st
            }
            is State.Countdown -> when {
                s < threshold - release -> State.Idle
                now >= st.endsAt -> State.Fire
                else -> st
            }
            State.Fire -> State.Cooldown(now + cooldownMs)
            is State.Cooldown -> if (now >= st.until) State.Idle else st
        }
        return state
    }

    fun reset() {
        state = State.Idle
    }
}
