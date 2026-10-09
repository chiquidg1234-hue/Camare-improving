package com.lumacam.core.pose

/**
 * Articulaciones usadas para dibujar y comparar poses. L/R son el lado IZQUIERDO/DERECHO DE LA
 * PANTALLA (no de la persona): así la silueta se dibuja tal cual y la app convierte los puntos
 * detectados según la cámara (trasera: la izquierda de la persona sale a la derecha).
 */
enum class Joint {
    NOSE,
    SHOULDER_L, SHOULDER_R,
    ELBOW_L, ELBOW_R,
    WRIST_L, WRIST_R,
    HIP_L, HIP_R,
    KNEE_L, KNEE_R,
    ANKLE_L, ANKLE_R;

    /** La misma articulación del otro lado (para la versión en espejo). */
    val mirror: Joint
        get() = when (this) {
            NOSE -> NOSE
            SHOULDER_L -> SHOULDER_R
            SHOULDER_R -> SHOULDER_L
            ELBOW_L -> ELBOW_R
            ELBOW_R -> ELBOW_L
            WRIST_L -> WRIST_R
            WRIST_R -> WRIST_L
            HIP_L -> HIP_R
            HIP_R -> HIP_L
            KNEE_L -> KNEE_R
            KNEE_R -> KNEE_L
            ANKLE_L -> ANKLE_R
            ANKLE_R -> ANKLE_L
        }
}

/** Punto normalizado en el encuadre (0..1, y hacia abajo). */
data class Pt(val x: Float, val y: Float)

data class PoseTemplate(
    val id: String,
    val name: String,
    val tips: List<String>,
    val categories: Set<AngleCategory>,
    /** Puntos de la silueta; las articulaciones que no salen en el encuadre se omiten. */
    val points: Map<Joint, Pt>,
    /** Pensada para selfie (cámara frontal), para que te hagan la foto, o ambas. */
    val selfie: Boolean = false,
    val byOthers: Boolean = true,
) {
    fun mirrored(): PoseTemplate = copy(
        id = "$id-espejo",
        points = points.entries.associate { (j, p) -> j.mirror to Pt(1f - p.x, p.y) },
    )

    companion object {
        /** Huesos para dibujar la silueta. */
        val BONES: List<Pair<Joint, Joint>> = listOf(
            Joint.SHOULDER_L to Joint.SHOULDER_R,
            Joint.SHOULDER_L to Joint.ELBOW_L, Joint.ELBOW_L to Joint.WRIST_L,
            Joint.SHOULDER_R to Joint.ELBOW_R, Joint.ELBOW_R to Joint.WRIST_R,
            Joint.SHOULDER_L to Joint.HIP_L, Joint.SHOULDER_R to Joint.HIP_R,
            Joint.HIP_L to Joint.HIP_R,
            Joint.HIP_L to Joint.KNEE_L, Joint.KNEE_L to Joint.ANKLE_L,
            Joint.HIP_R to Joint.KNEE_R, Joint.KNEE_R to Joint.ANKLE_R,
        )
    }
}

/** Poses sugeridas según el ángulo de la cámara. */
object PoseLibrary {

    /** De pie, relajado, de frente (base para construir las demás). */
    private val STANDING: Map<Joint, Pt> = mapOf(
        Joint.NOSE to Pt(0.50f, 0.15f),
        Joint.SHOULDER_L to Pt(0.41f, 0.26f), Joint.SHOULDER_R to Pt(0.59f, 0.26f),
        Joint.ELBOW_L to Pt(0.38f, 0.40f), Joint.ELBOW_R to Pt(0.62f, 0.40f),
        Joint.WRIST_L to Pt(0.37f, 0.53f), Joint.WRIST_R to Pt(0.63f, 0.53f),
        Joint.HIP_L to Pt(0.45f, 0.54f), Joint.HIP_R to Pt(0.55f, 0.54f),
        Joint.KNEE_L to Pt(0.45f, 0.72f), Joint.KNEE_R to Pt(0.55f, 0.72f),
        Joint.ANKLE_L to Pt(0.45f, 0.90f), Joint.ANKLE_R to Pt(0.55f, 0.90f),
    )

    private fun standing(vararg changes: Pair<Joint, Pt>): Map<Joint, Pt> = STANDING + changes

    private val E = AngleCategory.EYE
    private val H = AngleCategory.HIGH
    private val L = AngleCategory.LOW
    private val T = AngleCategory.TOP_DOWN
    private val B = AngleCategory.BOTTOM_UP

    val ALL: List<PoseTemplate> = listOf(
        PoseTemplate(
            id = "mano-cintura",
            name = "Mano en la cintura",
            tips = listOf(
                "Gira el cuerpo un poco (tres cuartos) hacia la cámara.",
                "Mano en la cintura: el hueco entre brazo y cuerpo afina la silueta.",
                "Peso en la pierna de atrás y hombros relajados.",
            ),
            categories = setOf(E, L),
            points = standing(Joint.ELBOW_R to Pt(0.71f, 0.39f), Joint.WRIST_R to Pt(0.57f, 0.52f)),
        ),
        PoseTemplate(
            id = "brazos-cruzados",
            name = "Brazos cruzados",
            tips = listOf(
                "Cruza los brazos sin apretar, hombros abajo.",
                "Mentón un poco hacia adelante y sonrisa suave.",
                "Transmite seguridad: ideal para fotos de perfil profesional.",
            ),
            categories = setOf(E, L),
            points = standing(
                Joint.ELBOW_L to Pt(0.39f, 0.41f), Joint.ELBOW_R to Pt(0.61f, 0.41f),
                Joint.WRIST_L to Pt(0.58f, 0.37f), Joint.WRIST_R to Pt(0.42f, 0.37f),
            ),
        ),
        PoseTemplate(
            id = "caminando",
            name = "Caminando hacia la cámara",
            tips = listOf(
                "Camina despacio hacia la cámara, mirando un poco al lado.",
                "Usa el disparo automático o varias fotos seguidas.",
                "Brazos sueltos, como al caminar normal.",
            ),
            categories = setOf(E, L),
            points = standing(
                Joint.KNEE_L to Pt(0.44f, 0.72f), Joint.ANKLE_L to Pt(0.40f, 0.90f),
                Joint.KNEE_R to Pt(0.57f, 0.70f), Joint.ANKLE_R to Pt(0.61f, 0.86f),
                Joint.ELBOW_L to Pt(0.37f, 0.40f), Joint.WRIST_L to Pt(0.40f, 0.52f),
                Joint.ELBOW_R to Pt(0.63f, 0.40f), Joint.WRIST_R to Pt(0.66f, 0.50f),
            ),
        ),
        PoseTemplate(
            id = "apoyado",
            name = "Apoyado en la pared",
            tips = listOf(
                "Apoya el hombro o una mano en la pared.",
                "Cruza una pierna por delante de la otra.",
                "Inclina un poco la cabeza hacia la pared.",
            ),
            categories = setOf(E),
            points = standing(
                Joint.NOSE to Pt(0.54f, 0.15f),
                Joint.SHOULDER_L to Pt(0.44f, 0.26f), Joint.SHOULDER_R to Pt(0.62f, 0.27f),
                Joint.ELBOW_R to Pt(0.73f, 0.19f), Joint.WRIST_R to Pt(0.70f, 0.07f),
                Joint.ELBOW_L to Pt(0.41f, 0.40f), Joint.WRIST_L to Pt(0.40f, 0.53f),
                Joint.KNEE_L to Pt(0.50f, 0.72f), Joint.ANKLE_L to Pt(0.56f, 0.90f),
            ),
        ),
        PoseTemplate(
            id = "tres-cuartos",
            name = "Retrato tres cuartos",
            tips = listOf(
                "Gira los hombros unos 45° y la cara hacia la cámara.",
                "El hombro más cercano a la cámara, un poco más bajo.",
                "Mira un punto justo encima de la cámara.",
            ),
            categories = setOf(E, H),
            selfie = true,
            points = mapOf(
                Joint.NOSE to Pt(0.53f, 0.26f),
                Joint.SHOULDER_L to Pt(0.42f, 0.44f), Joint.SHOULDER_R to Pt(0.61f, 0.46f),
                Joint.ELBOW_L to Pt(0.39f, 0.68f), Joint.ELBOW_R to Pt(0.64f, 0.70f),
                Joint.HIP_L to Pt(0.46f, 0.92f), Joint.HIP_R to Pt(0.58f, 0.93f),
            ),
        ),
        PoseTemplate(
            id = "mano-menton",
            name = "Mano en el mentón",
            tips = listOf(
                "Apoya el mentón suavemente sobre la mano, sin aplastar la cara.",
                "Codo hacia abajo y hacia la cámara.",
                "Desde un poco más arriba queda más estilizado.",
            ),
            categories = setOf(H, E),
            selfie = true,
            points = mapOf(
                Joint.NOSE to Pt(0.50f, 0.27f),
                Joint.SHOULDER_L to Pt(0.38f, 0.46f), Joint.SHOULDER_R to Pt(0.62f, 0.46f),
                Joint.ELBOW_L to Pt(0.35f, 0.72f), Joint.ELBOW_R to Pt(0.60f, 0.62f),
                Joint.WRIST_R to Pt(0.52f, 0.36f),
                Joint.HIP_L to Pt(0.43f, 0.94f), Joint.HIP_R to Pt(0.57f, 0.94f),
            ),
        ),
        PoseTemplate(
            id = "mirada-arriba",
            name = "Mirada hacia la cámara",
            tips = listOf(
                "Levanta sólo los ojos hacia la cámara, no toda la cabeza.",
                "Mentón un poco hacia adelante (marca la mandíbula).",
                "Manos juntas por delante, relajadas.",
            ),
            categories = setOf(H),
            selfie = true,
            points = standing(
                Joint.ELBOW_L to Pt(0.40f, 0.42f), Joint.ELBOW_R to Pt(0.60f, 0.42f),
                Joint.WRIST_L to Pt(0.48f, 0.50f), Joint.WRIST_R to Pt(0.52f, 0.50f),
            ),
        ),
        PoseTemplate(
            id = "selfie-alto",
            name = "Selfie desde arriba",
            tips = listOf(
                "Teléfono un poco por encima de tus ojos, inclinado hacia ti.",
                "Gira la cara ligeramente; evita salir totalmente de frente.",
                "Ponte de frente a una ventana para que la luz te dé en la cara.",
            ),
            categories = setOf(H),
            selfie = true,
            byOthers = false,
            points = mapOf(
                Joint.NOSE to Pt(0.50f, 0.32f),
                Joint.SHOULDER_L to Pt(0.35f, 0.52f), Joint.SHOULDER_R to Pt(0.65f, 0.52f),
                Joint.ELBOW_R to Pt(0.80f, 0.40f), Joint.WRIST_R to Pt(0.78f, 0.18f),
                Joint.HIP_L to Pt(0.42f, 0.96f), Joint.HIP_R to Pt(0.58f, 0.96f),
            ),
        ),
        PoseTemplate(
            id = "sentado-suelo",
            name = "Sentado en el suelo",
            tips = listOf(
                "Siéntate con las piernas cruzadas y mira hacia arriba.",
                "Quien hace la foto, de pie, apuntando hacia abajo.",
                "Manos sobre las rodillas o jugando con el pelo.",
            ),
            categories = setOf(H, T),
            points = mapOf(
                Joint.NOSE to Pt(0.50f, 0.26f),
                Joint.SHOULDER_L to Pt(0.40f, 0.38f), Joint.SHOULDER_R to Pt(0.60f, 0.38f),
                Joint.ELBOW_L to Pt(0.36f, 0.52f), Joint.ELBOW_R to Pt(0.64f, 0.52f),
                Joint.WRIST_L to Pt(0.37f, 0.65f), Joint.WRIST_R to Pt(0.63f, 0.65f),
                Joint.HIP_L to Pt(0.45f, 0.62f), Joint.HIP_R to Pt(0.55f, 0.62f),
                Joint.KNEE_L to Pt(0.32f, 0.68f), Joint.KNEE_R to Pt(0.68f, 0.68f),
                Joint.ANKLE_L to Pt(0.47f, 0.73f), Joint.ANKLE_R to Pt(0.53f, 0.73f),
            ),
        ),
        PoseTemplate(
            id = "acostado-brazos",
            name = "Acostado, brazos abiertos",
            tips = listOf(
                "Acuéstate boca arriba (pasto, cama, hojas) con el pelo extendido.",
                "La cámara justo encima y paralela al suelo.",
                "Mira directo a la cámara o cierra los ojos.",
            ),
            categories = setOf(T),
            points = standing(
                Joint.ELBOW_L to Pt(0.28f, 0.27f), Joint.WRIST_L to Pt(0.15f, 0.26f),
                Joint.ELBOW_R to Pt(0.72f, 0.27f), Joint.WRIST_R to Pt(0.85f, 0.26f),
            ),
        ),
        PoseTemplate(
            id = "acostado-mano-pelo",
            name = "Acostado, mano en el pelo",
            tips = listOf(
                "Boca arriba, una mano detrás de la cabeza.",
                "Dobla un poco la rodilla del lado contrario.",
                "Cámara justo encima: cuidado con tu sombra.",
            ),
            categories = setOf(T),
            points = standing(
                Joint.ELBOW_R to Pt(0.72f, 0.12f), Joint.WRIST_R to Pt(0.56f, 0.09f),
                Joint.KNEE_L to Pt(0.40f, 0.70f), Joint.ANKLE_L to Pt(0.46f, 0.86f),
            ),
        ),
        PoseTemplate(
            id = "pose-poder",
            name = "Pose de poder",
            tips = listOf(
                "Piernas separadas al ancho de los hombros, manos en la cintura.",
                "Pecho afuera y mirada al horizonte.",
                "Desde abajo te verás más alto e imponente.",
            ),
            categories = setOf(L, E),
            points = standing(
                Joint.ELBOW_L to Pt(0.29f, 0.39f), Joint.WRIST_L to Pt(0.43f, 0.52f),
                Joint.ELBOW_R to Pt(0.71f, 0.39f), Joint.WRIST_R to Pt(0.57f, 0.52f),
                Joint.KNEE_L to Pt(0.40f, 0.72f), Joint.KNEE_R to Pt(0.60f, 0.72f),
                Joint.ANKLE_L to Pt(0.35f, 0.90f), Joint.ANKLE_R to Pt(0.65f, 0.90f),
            ),
        ),
        PoseTemplate(
            id = "brazos-arriba",
            name = "Brazos arriba",
            tips = listOf(
                "Brazos estirados hacia arriba en V, como celebrando.",
                "Perfecto con cielo de fondo.",
                "Activa el disparo automático para no perder el momento.",
            ),
            categories = setOf(L, B),
            points = standing(
                Joint.ELBOW_L to Pt(0.34f, 0.14f), Joint.WRIST_L to Pt(0.29f, 0.03f),
                Joint.ELBOW_R to Pt(0.66f, 0.14f), Joint.WRIST_R to Pt(0.71f, 0.03f),
            ),
        ),
        PoseTemplate(
            id = "salto",
            name = "Salto",
            tips = listOf(
                "Salta con los brazos arriba y las rodillas dobladas.",
                "Cámara baja, apuntando hacia arriba: el salto parece más alto.",
                "Dispara en ráfaga o con disparo automático justo antes de saltar.",
            ),
            categories = setOf(B, L),
            points = standing(
                Joint.ELBOW_L to Pt(0.34f, 0.14f), Joint.WRIST_L to Pt(0.29f, 0.03f),
                Joint.ELBOW_R to Pt(0.66f, 0.14f), Joint.WRIST_R to Pt(0.71f, 0.03f),
                Joint.KNEE_L to Pt(0.40f, 0.66f), Joint.ANKLE_L to Pt(0.43f, 0.80f),
                Joint.KNEE_R to Pt(0.60f, 0.66f), Joint.ANKLE_R to Pt(0.57f, 0.80f),
            ),
        ),
        PoseTemplate(
            id = "gigante",
            name = "Gigante desde el suelo",
            tips = listOf(
                "Deja el teléfono en el suelo mirando hacia arriba (usa el disparo automático).",
                "Párate encima, piernas abiertas, y mira hacia la cámara.",
                "Manos en la cintura para un efecto de superhéroe.",
            ),
            categories = setOf(B),
            points = mapOf(
                Joint.NOSE to Pt(0.50f, 0.30f),
                Joint.SHOULDER_L to Pt(0.40f, 0.40f), Joint.SHOULDER_R to Pt(0.60f, 0.40f),
                Joint.ELBOW_L to Pt(0.30f, 0.50f), Joint.WRIST_L to Pt(0.43f, 0.60f),
                Joint.ELBOW_R to Pt(0.70f, 0.50f), Joint.WRIST_R to Pt(0.57f, 0.60f),
                Joint.HIP_L to Pt(0.45f, 0.62f), Joint.HIP_R to Pt(0.55f, 0.62f),
                Joint.KNEE_L to Pt(0.38f, 0.79f), Joint.KNEE_R to Pt(0.62f, 0.79f),
                Joint.ANKLE_L to Pt(0.30f, 0.96f), Joint.ANKLE_R to Pt(0.70f, 0.96f),
            ),
        ),
    )

    /** Poses para un ángulo; [selfie] filtra las que tienen sentido con la cámara frontal. */
    fun forCategory(category: AngleCategory, selfie: Boolean): List<PoseTemplate> {
        val matching = ALL.filter { category in it.categories && (if (selfie) it.selfie || it.byOthers else it.byOthers) }
        if (!selfie) return matching
        // En selfie primero las pensadas para selfie.
        return matching.sortedByDescending { it.selfie }
    }

    fun byId(id: String): PoseTemplate? = ALL.firstOrNull { it.id == id }
}
