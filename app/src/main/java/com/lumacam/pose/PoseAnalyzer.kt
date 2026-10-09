package com.lumacam.pose

import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.lumacam.core.pose.Joint
import com.lumacam.core.pose.Pt

/**
 * Detecta el cuerpo con ML Kit (modelo incluido en la app, sin internet) y devuelve las
 * articulaciones en coordenadas de PANTALLA normalizadas (0..1), con los lados de la pantalla:
 * - cámara trasera: la izquierda de la persona sale a la derecha de la pantalla;
 * - cámara frontal: la vista previa es un espejo, así que se refleja x y la izquierda queda a la izquierda.
 */
class PoseAnalyzer(
    /** Se llama en el hilo principal con los puntos detectados (vacío si no hay nadie). */
    private val onResult: (Map<Joint, Pt>) -> Unit,
) : ImageAnalysis.Analyzer {

    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build(),
    )

    @Volatile
    var frontCamera: Boolean = false

    /** Durante la captura de fotos no se analiza (libera CPU para la ráfaga). */
    @Volatile
    var paused: Boolean = false

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (paused || media == null) {
            image.close()
            return
        }
        val rotation = image.imageInfo.rotationDegrees
        val uprightW = if (rotation % 180 == 0) image.width else image.height
        val uprightH = if (rotation % 180 == 0) image.height else image.width
        val front = frontCamera
        try {
            detector.process(InputImage.fromMediaImage(media, rotation))
                .addOnSuccessListener { pose -> onResult(convert(pose, uprightW, uprightH, front)) }
                .addOnFailureListener { e -> Log.w(TAG, "Fallo detectando pose", e) }
                .addOnCompleteListener { image.close() }
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo analizar el frame", t)
            image.close()
        }
    }

    fun close() {
        detector.close()
    }

    companion object {
        private const val TAG = "PoseAnalyzer"
        private const val MIN_LIKELIHOOD = 0.5f

        /**
         * Landmark de ML Kit (lado de la PERSONA) → (articulación si cámara frontal, si trasera).
         * Frontal: la vista previa es un espejo, el lado de la persona queda del mismo lado de la
         * pantalla. Trasera: queda del lado contrario.
         */
        private val PERSON_LEFT = listOf(
            PoseLandmark.LEFT_SHOULDER to (Joint.SHOULDER_L to Joint.SHOULDER_R),
            PoseLandmark.LEFT_ELBOW to (Joint.ELBOW_L to Joint.ELBOW_R),
            PoseLandmark.LEFT_WRIST to (Joint.WRIST_L to Joint.WRIST_R),
            PoseLandmark.LEFT_HIP to (Joint.HIP_L to Joint.HIP_R),
            PoseLandmark.LEFT_KNEE to (Joint.KNEE_L to Joint.KNEE_R),
            PoseLandmark.LEFT_ANKLE to (Joint.ANKLE_L to Joint.ANKLE_R),
        )
        private val PERSON_RIGHT = listOf(
            PoseLandmark.RIGHT_SHOULDER to (Joint.SHOULDER_R to Joint.SHOULDER_L),
            PoseLandmark.RIGHT_ELBOW to (Joint.ELBOW_R to Joint.ELBOW_L),
            PoseLandmark.RIGHT_WRIST to (Joint.WRIST_R to Joint.WRIST_L),
            PoseLandmark.RIGHT_HIP to (Joint.HIP_R to Joint.HIP_L),
            PoseLandmark.RIGHT_KNEE to (Joint.KNEE_R to Joint.KNEE_L),
            PoseLandmark.RIGHT_ANKLE to (Joint.ANKLE_R to Joint.ANKLE_L),
        )

        fun convert(pose: Pose, w: Int, h: Int, front: Boolean): Map<Joint, Pt> {
            if (w <= 0 || h <= 0) return emptyMap()
            val out = HashMap<Joint, Pt>()
            fun put(type: Int, joint: Joint) {
                val lm = pose.getPoseLandmark(type) ?: return
                if (lm.inFrameLikelihood < MIN_LIKELIHOOD) return
                val x = lm.position.x / w
                val y = lm.position.y / h
                out[joint] = Pt(if (front) 1f - x else x, y)
            }
            put(PoseLandmark.NOSE, Joint.NOSE)
            // Frontal (espejo): lado de la persona = lado de la pantalla. Trasera: al revés.
            for ((type, sides) in PERSON_LEFT) put(type, if (front) sides.first else sides.second)
            for ((type, sides) in PERSON_RIGHT) put(type, if (front) sides.first else sides.second)
            return out
        }
    }
}
