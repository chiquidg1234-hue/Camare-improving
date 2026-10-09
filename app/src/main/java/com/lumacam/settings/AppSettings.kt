package com.lumacam.settings

import android.content.Context
import androidx.camera.core.ImageCapture
import com.lumacam.camera.CaptureMode
import com.lumacam.camera.ResolutionMode
import com.lumacam.camera.VendorMode
import com.lumacam.camera.VideoQualityOption
import com.lumacam.core.look.Adjustments
import com.lumacam.core.look.LookId

/** Preferencias que se guardan entre sesiones. */
data class AppSettings(
    val lookId: LookId = LookId.NATURAL,
    val intensity: Float = 0.8f,
    val adjustments: Adjustments = Adjustments.NEUTRAL,
    val multiFrame: Boolean = true,
    /** Frames por foto; 0 = automático según el look. */
    val frames: Int = 0,
    val jpegQuality: Int = 95,
    val resolutionMode: ResolutionMode = ResolutionMode.STANDARD,
    val videoQuality: VideoQualityOption = VideoQualityOption.FHD,
    val stabilization: Boolean = true,
    val longExposureNight: Boolean = true,
    val mode: CaptureMode = CaptureMode.PHOTO,
    val vendorMode: VendorMode = VendorMode.NONE,
    val sceneHdr: Boolean = false,
    val flashMode: Int = ImageCapture.FLASH_MODE_OFF,
    val front: Boolean = false,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("lumacam", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        val p = prefs
        return AppSettings(
            lookId = enumOr(p.getString("look", null), d.lookId),
            intensity = p.getFloat("intensity", d.intensity),
            adjustments = Adjustments(
                temperature = p.getFloat("adj_temperature", 0f),
                tint = p.getFloat("adj_tint", 0f),
                contrast = p.getFloat("adj_contrast", 0f),
                shadows = p.getFloat("adj_shadows", 0f),
                highlights = p.getFloat("adj_highlights", 0f),
                saturation = p.getFloat("adj_saturation", 0f),
                vibrance = p.getFloat("adj_vibrance", 0f),
                localContrast = p.getFloat("adj_local_contrast", 0f),
                sharpness = p.getFloat("adj_sharpness", 0f),
            ),
            multiFrame = p.getBoolean("multi_frame", d.multiFrame),
            frames = p.getInt("frames", d.frames),
            jpegQuality = p.getInt("jpeg_quality", d.jpegQuality),
            resolutionMode = enumOr(p.getString("resolution_mode", null), d.resolutionMode),
            videoQuality = enumOr(p.getString("video_quality", null), d.videoQuality),
            stabilization = p.getBoolean("stabilization", d.stabilization),
            longExposureNight = p.getBoolean("long_exposure_night", d.longExposureNight),
            mode = enumOr(p.getString("mode", null), d.mode),
            vendorMode = enumOr(p.getString("vendor_mode", null), d.vendorMode),
            sceneHdr = p.getBoolean("scene_hdr", d.sceneHdr),
            flashMode = p.getInt("flash_mode", d.flashMode),
            front = p.getBoolean("front", d.front),
        )
    }

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("look", s.lookId.name)
            .putFloat("intensity", s.intensity)
            .putFloat("adj_temperature", s.adjustments.temperature)
            .putFloat("adj_tint", s.adjustments.tint)
            .putFloat("adj_contrast", s.adjustments.contrast)
            .putFloat("adj_shadows", s.adjustments.shadows)
            .putFloat("adj_highlights", s.adjustments.highlights)
            .putFloat("adj_saturation", s.adjustments.saturation)
            .putFloat("adj_vibrance", s.adjustments.vibrance)
            .putFloat("adj_local_contrast", s.adjustments.localContrast)
            .putFloat("adj_sharpness", s.adjustments.sharpness)
            .putBoolean("multi_frame", s.multiFrame)
            .putInt("frames", s.frames)
            .putInt("jpeg_quality", s.jpegQuality)
            .putString("resolution_mode", s.resolutionMode.name)
            .putString("video_quality", s.videoQuality.name)
            .putBoolean("stabilization", s.stabilization)
            .putBoolean("long_exposure_night", s.longExposureNight)
            .putString("mode", s.mode.name)
            .putString("vendor_mode", s.vendorMode.name)
            .putBoolean("scene_hdr", s.sceneHdr)
            .putInt("flash_mode", s.flashMode)
            .putBoolean("front", s.front)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
}
