package com.lumacam.core.look

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Herramienta opcional (no es una prueba automática): aplica cada look, con el mismo pipeline de
 * CPU que la foto final, a las fotos de la carpeta indicada en LUMACAM_SAMPLES y genera una
 * hoja de contactos en core/build/look-sheet/looks.png para revisar los looks a ojo.
 *
 *   LUMACAM_SAMPLES=/ruta/fotos ./gradlew -p core test --tests '*LookSheetTool*'
 */
class LookSheetTool {
    @Test
    fun makeSheet() {
        val dir = System.getenv("LUMACAM_SAMPLES")
        assumeTrue("LUMACAM_SAMPLES no definido", dir != null)
        val files = File(dir!!).listFiles { f -> f.extension.lowercase() in setOf("png", "jpg", "jpeg") }
            ?.sortedBy { it.name } ?: return
        val cell = 300
        val cols = 1 + Looks.ALL.size
        val labelH = 22
        val rows = files.mapNotNull { f -> ImageIO.read(f)?.let { f.nameWithoutExtension to it } }
        val sheet = BufferedImage(cols * cell, rows.size * (cell + labelH), BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
        for ((r, pair) in rows.withIndex()) {
            val (name, img) = pair
            val w = img.width
            val h = img.height
            val src = IntArray(w * h)
            img.getRGB(0, 0, w, h, src, 0, w)
            for (i in src.indices) src[i] = src[i] or (0xFF shl 24)
            val outputs = listOf("Original ($name)" to src) + Looks.ALL.map { preset ->
                val look = ResolvedLook.resolve(preset, 1f)
                var px = src
                if (look.chromaDenoise > 0f) {
                    px = ChromaDenoiser(ChromaDenoiser.adaptiveStrength(look.chromaDenoise, 3f), ChromaDenoiser.radiusFor(w, h)).processImage(px, w, h)
                }
                preset.displayName to LookProcessor(look).processImage(px, w, h)
            }
            for ((c, out) in outputs.withIndex()) {
                val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                bi.setRGB(0, 0, w, h, out.second, 0, w)
                val scale = minOf(cell.toDouble() / w, cell.toDouble() / h)
                val dw = (w * scale).toInt()
                val dh = (h * scale).toInt()
                val x = c * cell + (cell - dw) / 2
                val y = r * (cell + labelH) + labelH + (cell - dh) / 2
                g.drawImage(bi, x, y, dw, dh, null)
                g.color = Color.WHITE
                g.drawString(out.first, c * cell + 6, r * (cell + labelH) + 16)
            }
        }
        g.dispose()
        val outDir = File("build/look-sheet").apply { mkdirs() }
        ImageIO.write(sheet, "png", File(outDir, "looks.png"))

        // Recortes al 100 % (centro de la primera foto) para ver nitidez y halos.
        val (_, first) = rows.first()
        val w = first.width
        val h = first.height
        val src = IntArray(w * h)
        first.getRGB(0, 0, w, h, src, 0, w)
        val crop = 180
        val cx = w / 2 - crop / 2
        val cy = h / 4
        val variants = listOf("Original" to src) + listOf(Looks.NATURAL, Looks.VIVID, Looks.CINE).map {
            it.displayName to LookProcessor(ResolvedLook.resolve(it, 1f)).processImage(src, w, h)
        }
        val zoom = 2
        val crops = BufferedImage(variants.size * crop * zoom, crop * zoom, BufferedImage.TYPE_INT_RGB)
        val g2 = crops.createGraphics()
        for ((i, v) in variants.withIndex()) {
            val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            bi.setRGB(0, 0, w, h, v.second, 0, w)
            g2.drawImage(bi.getSubimage(cx, cy, crop, crop), i * crop * zoom, 0, crop * zoom, crop * zoom, null)
            g2.color = Color.YELLOW
            g2.drawString(v.first, i * crop * zoom + 6, 16)
        }
        g2.dispose()
        ImageIO.write(crops, "png", File(outDir, "crops.png"))
    }
}
