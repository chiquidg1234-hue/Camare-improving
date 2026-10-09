package com.lumacam.core.merge

import com.lumacam.core.image.FrameSink
import com.lumacam.core.image.FrameSource
import com.lumacam.core.image.LumaImage
import kotlin.math.max

/**
 * Orquesta la foto multi-frame: elige como referencia el frame más nítido, descarta frames
 * movidos, alinea el resto y los fusiona con [FrameMerger].
 */
class BurstMerger(
    private val aligner: Aligner = Aligner(),
    private val merger: FrameMerger = FrameMerger(),
    private val motionEstimator: MotionEstimator? = MotionEstimator(),
    /** Frames con nitidez menor que esta fracción de la mejor se descartan (salieron movidos). */
    private val minRelativeSharpness: Float = 0.45f,
) {
    data class Result(
        val refIndex: Int,
        val usedIndices: List<Int>,
        val shifts: List<Shift>,
        val motions: List<Motion>,
        val noiseSigma: Float,
        val stats: FrameMerger.Stats,
    )

    fun process(
        frames: List<FrameSource>,
        sink: FrameSink,
        progress: (Float) -> Unit = {},
    ): Result {
        require(frames.isNotEmpty())
        val w = frames[0].width
        val baseFactor = baseFactorFor(w)

        val lumas = frames.map { LumaImage.downsampled(it, baseFactor) }
        progress(0.15f)
        val sharpness = lumas.map { ImageStats.sharpness(it) }
        val refIndex = sharpness.indices.maxByOrNull { sharpness[it] } ?: 0
        val best = sharpness[refIndex]
        val used = frames.indices.filter { it == refIndex || sharpness[it] >= best * minRelativeSharpness }

        val refPyr = aligner.pyramid(lumas[refIndex], baseFactor)
        val shifts = ArrayList<Shift>()
        val motions = ArrayList<Motion>()
        for ((n, k) in used.withIndex()) {
            if (k == refIndex) {
                shifts += Shift.ZERO
                motions += Motion.IDENTITY
            } else {
                val coarse = aligner.alignPyramids(refPyr, aligner.pyramid(lumas[k], baseFactor), w)
                val s = aligner.refineFullRes(frames[refIndex], frames[k], coarse, baseFactor / 2 + 1)
                shifts += s
                // Rotación/escala pequeña (mano sin estabilizador): modelo afín por bloques.
                motions += motionEstimator?.estimate(frames[refIndex], frames[k], s) ?: Motion.of(s)
            }
            progress(0.15f + 0.25f * (n + 1) / used.size)
        }

        val sigma = ImageStats.noiseSigma(frames[refIndex])
        val usedFrames = used.map { frames[it] }
        val stats = merger.merge(usedFrames, motions, used.indexOf(refIndex), sigma, sink) { p ->
            progress(0.4f + 0.6f * p)
        }
        return Result(refIndex, used, shifts, motions, sigma, stats)
    }

    companion object {
        /** Factor de reducción para que la base de la pirámide tenga ~1000 px de ancho. */
        fun baseFactorFor(width: Int): Int = max(1, width / 1000)
    }
}
