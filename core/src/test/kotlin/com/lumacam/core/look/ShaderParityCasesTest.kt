package com.lumacam.core.look

import com.lumacam.core.TestImages
import com.lumacam.core.color.ColorMath
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Genera casos (entrada, uniforms, texturas y salida esperada de la CPU) para comprobar que el
 * shader look.frag hace las mismas cuentas que [LookProcessor]. El script
 * tools/shader-check/check.mjs los ejecuta en WebGL (Chromium sin pantalla) y compara.
 */
class ShaderParityCasesTest {

    private fun rgba(argb: IntArray): ByteArray {
        val out = ByteArray(argb.size * 4)
        for (i in argb.indices) {
            val p = argb[i]
            out[i * 4] = ColorMath.red(p).toByte()
            out[i * 4 + 1] = ColorMath.green(p).toByte()
            out[i * 4 + 2] = ColorMath.blue(p).toByte()
            out[i * 4 + 3] = 0xFF.toByte()
        }
        return out
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun withoutLocalContrast(l: ResolvedLook) = ResolvedLook(
        presetId = l.presetId, intensity = l.intensity, effective = l.effective, gains = l.gains,
        curve = l.curve, saturation = l.saturation, vibrance = l.vibrance, localContrast = 0f,
        sharpness = l.sharpness, sigmaFrac = l.sigmaFrac, lut = l.lut, lutMix = l.lutMix, vignette = l.vignette,
    )

    @Test
    fun writeCases() {
        val w = 160
        val h = 120
        val img = TestImages.scene(w, h, seed = 5)
        val cases = ArrayList<Pair<String, ResolvedLook>>()
        for (p in Looks.ALL) cases += "${p.id}_100" to withoutLocalContrast(ResolvedLook.resolve(p, 1f))
        cases += "CINE_50" to withoutLocalContrast(ResolvedLook.resolve(Looks.CINE, 0.5f))
        cases += "USER_EXTREMOS" to withoutLocalContrast(
            ResolvedLook.resolve(
                Looks.WARM, 0.7f,
                Adjustments(temperature = -0.8f, tint = 0.6f, contrast = 0.7f, shadows = -0.5f, highlights = 0.6f, saturation = -0.4f, vibrance = 0.9f, sharpness = 1f),
            ),
        )
        cases += "NEUTRO" to ResolvedLook.NEUTRAL
        // Con contraste local: la GPU desenfoca distinto (pirámide 1/4), se compara con tolerancia.
        // En la vista previa real σ ≈ 6.5 px (0.6% de 1080); con la imagen de prueba de 120 px de
        // alto se usa una fracción mayor para tener el mismo σ en píxeles.
        val lc = ResolvedLook.resolve(Looks.VIVID, 1f, Adjustments(localContrast = 0.6f))
        cases += "VIVID_LC" to ResolvedLook(
            presetId = lc.presetId, intensity = lc.intensity, effective = lc.effective, gains = lc.gains,
            curve = lc.curve, saturation = lc.saturation, vibrance = lc.vibrance, localContrast = lc.localContrast,
            sharpness = lc.sharpness, sigmaFrac = 0.054f, lut = lc.lut, lutMix = lc.lutMix, vignette = lc.vignette,
        )

        val sb = StringBuilder()
        sb.append("{\"width\":$w,\"height\":$h,\"input\":\"${b64(rgba(img.pixels))}\",\"cases\":[")
        cases.forEachIndexed { i, (name, look) ->
            val expected = LookProcessor(look).processImage(img.pixels, w, h)
            if (i > 0) sb.append(',')
            sb.append("{\"name\":\"$name\"")
            sb.append(",\"exactLocalContrast\":${look.localContrast == 0f}")
            sb.append(",\"gains\":[${look.gains[0]},${look.gains[1]},${look.gains[2]}]")
            sb.append(",\"wb\":${if (look.wbNeutral) 0 else 1}")
            sb.append(",\"saturation\":${look.saturation},\"vibrance\":${look.vibrance}")
            sb.append(",\"localContrast\":${look.localContrast * LookProcessor.LC_GAIN}")
            sb.append(",\"sigmaFrac\":${look.sigmaFrac}")
            sb.append(",\"sharpness\":${look.sharpness * LookProcessor.SH_GAIN}")
            sb.append(",\"lutSize\":${look.lut.size},\"lutMix\":${look.lutMix},\"vignette\":${look.vignette}")
            sb.append(",\"curve\":\"${b64(look.curve.toRgbaTexture())}\"")
            sb.append(",\"lut\":\"${b64(look.lut.toAtlasRgba())}\"")
            sb.append(",\"expected\":\"${b64(rgba(expected))}\"}")
        }
        sb.append("]}")
        val dir = File("build/shader-check").apply { mkdirs() }
        val f = File(dir, "cases.json")
        f.writeText(sb.toString())
        assertTrue(f.length() > 1000)
    }
}
