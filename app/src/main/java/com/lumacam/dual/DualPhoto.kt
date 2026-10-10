package com.lumacam.dual

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.lumacam.core.dual.Box
import com.lumacam.core.dual.DualGeometry
import com.lumacam.core.dual.DualLayout
import com.lumacam.core.dual.PipCorner
import com.lumacam.core.look.ResolvedLook
import com.lumacam.photo.PhotoPipeline
import kotlin.math.min

/** Una foto de una cámara: [rotation] grados (horario) para verla derecha; [mirror] = selfie en espejo. */
class DualShot(val bitmap: Bitmap, val rotation: Int, val mirror: Boolean) {
    val uprightW: Int get() = if (rotation % 180 == 0) bitmap.width else bitmap.height
    val uprightH: Int get() = if (rotation % 180 == 0) bitmap.height else bitmap.width
}

/**
 * Foto doble: compone las dos fotos con la misma geometría que la vista previa ([DualGeometry]) y
 * aplica el look a cada cámara por separado (como en OpenGL, viñeta incluida).
 */
object DualPhoto {
    /** Ancho máximo del resultado (3:4 → 2160x2880, ~6 MP). */
    private const val MAX_WIDTH = 2160
    private val BACKGROUND = Color.rgb(10, 11, 13)

    suspend fun compose(
        main: DualShot,
        second: DualShot?,
        layout: DualLayout,
        corner: PipCorner,
        look: ResolvedLook,
    ): Bitmap {
        val w = (min(main.uprightW, MAX_WIDTH) / 2) * 2
        val h = w * 4 / 3
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(BACKGROUND)
        val regions = DualGeometry.regions(layout, w, h, corner, single = second == null)

        drawRegion(canvas, main, regions.main, w, h, look, radius = 0f, border = 0f)
        if (second != null && regions.second != null) {
            drawRegion(canvas, second, regions.second!!, w, h, look, regions.radius * w, regions.border * w)
        }
        return out
    }

    private suspend fun drawRegion(
        canvas: Canvas,
        shot: DualShot,
        box: Box,
        canvasW: Int,
        canvasH: Int,
        look: ResolvedLook,
        radius: Float,
        border: Float,
    ) {
        val px = box.toPixels(canvasW, canvasH)
        val rw = px[2]
        val rh = px[3]
        // La cámara recortada al tamaño de su región, con el look aplicado sólo a ella.
        var region = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
        Canvas(region).drawBitmap(shot.bitmap, fillMatrix(shot, rw, rh), Paint(Paint.FILTER_BITMAP_FLAG))
        if (!look.isNeutral) {
            val styled = PhotoPipeline.applyLook(region, look)
            region.recycle()
            region = styled
        }
        val left = px[0].toFloat()
        val top = px[1].toFloat()
        if (radius <= 0f) {
            canvas.drawBitmap(region, left, top, null)
        } else {
            val shader = BitmapShader(region, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setTranslate(left, top) })
            }
            val rect = RectF(left, top, left + rw, top + rh)
            canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
            if (border > 0f) {
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = border
                    color = Color.rgb(5, 5, 5)
                }
                val inset = border / 2f
                canvas.drawRoundRect(
                    RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset),
                    radius - inset, radius - inset, stroke,
                )
            }
        }
        region.recycle()
    }

    /** Matriz que gira, refleja y recorta (llenando) la foto para un destino de [dw]x[dh]. */
    private fun fillMatrix(shot: DualShot, dw: Int, dh: Int): Matrix {
        val bw = shot.bitmap.width.toFloat()
        val bh = shot.bitmap.height.toFloat()
        val uw = shot.uprightW.toFloat()
        val uh = shot.uprightH.toFloat()
        val m = Matrix()
        // Girar en sentido horario y devolver al cuadrante positivo.
        when ((shot.rotation % 360 + 360) % 360) {
            90 -> { m.postRotate(90f); m.postTranslate(bh, 0f) }
            180 -> { m.postRotate(180f); m.postTranslate(bw, bh) }
            270 -> { m.postRotate(270f); m.postTranslate(0f, bw) }
        }
        if (shot.mirror) m.postScale(-1f, 1f, uw / 2f, 0f)
        val c = DualGeometry.cropWindow(dw.toFloat() / dh, uw / uh)
        val visW = (c[2] - c[0]) * uw
        val visH = (c[3] - c[1]) * uh
        m.postTranslate(-c[0] * uw, -c[1] * uh)
        m.postScale(dw / visW, dh / visH)
        return m
    }
}
