package com.lumacam.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Range
import android.util.Size
import com.lumacam.core.zoom.CameraDescriptor
import com.lumacam.core.zoom.Facing

/** Datos de Camera2 de una cámara, para decidir qué funciones ofrecer y para el diagnóstico. */
data class CameraCaps(
    val descriptor: CameraDescriptor,
    val hardwareLevel: String,
    val capabilities: List<String>,
    val sceneModes: List<Int>,
    val videoStabilizationModes: List<Int>,
    val opticalStabilizationModes: List<Int>,
    val aeFpsRanges: List<Range<Int>>,
    val maxJpeg: Size?,
    val maxHighResJpeg: Size?,
    val ultraHighResSensor: Boolean,
    val pixelArray: Size?,
    val hasFlash: Boolean,
) {
    val id: String get() = descriptor.id
    val hdrSceneMode: Boolean get() = CameraMetadata.CONTROL_SCENE_MODE_HDR in sceneModes
    val hasOis: Boolean get() = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in opticalStabilizationModes
    val hasEis: Boolean get() = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in videoStabilizationModes

    /** Rango de FPS con el mínimo más bajo y máximo <= 30, para exposiciones largas de noche. */
    fun longExposureFpsRange(): Range<Int>? = aeFpsRanges
        .filter { it.upper <= 30 && it.lower < it.upper }
        .minWithOrNull(compareBy<Range<Int>> { it.lower }.thenByDescending { it.upper })
}

object DeviceProbe {

    fun probe(context: Context): List<CameraCaps> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val result = ArrayList<CameraCaps>()
        val ids = try {
            manager.cameraIdList.toList()
        } catch (t: Throwable) {
            emptyList()
        }
        for (id in ids) {
            try {
                result += describe(id, manager.getCameraCharacteristics(id))
            } catch (_: Throwable) {
                // Cámara inaccesible: se ignora.
            }
        }
        return result
    }

    private fun describe(id: String, c: CameraCharacteristics): CameraCaps {
        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_FRONT -> Facing.FRONT
            CameraMetadata.LENS_FACING_BACK -> Facing.BACK
            else -> Facing.EXTERNAL
        }
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
        val zoomRange: Range<Float>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else null
        val physicalSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
        val highRes = map?.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
        val maxJpeg = jpegSizes.maxByOrNull { it.width.toLong() * it.height }
        val maxHigh = highRes.maxByOrNull { it.width.toLong() * it.height }
        val pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val ultraHigh = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR in caps
        val maxPixels = listOfNotNull(maxJpeg, maxHigh).maxOfOrNull { it.width.toLong() * it.height } ?: 0L

        val descriptor = CameraDescriptor(
            id = id,
            facing = facing,
            focalLengthsMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList(),
            sensorWidthMm = physicalSize?.width ?: 0f,
            sensorHeightMm = physicalSize?.height ?: 0f,
            zoomRatioMin = zoomRange?.lower,
            zoomRatioMax = zoomRange?.upper,
            maxDigitalZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
            isLogicalMultiCamera = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps,
            physicalIds = c.physicalCameraIds.toList(),
            backwardCompatible = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE in caps,
            maxPixels = maxPixels,
        )
        return CameraCaps(
            descriptor = descriptor,
            hardwareLevel = hardwareLevelName(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)),
            capabilities = caps.map(::capabilityName),
            sceneModes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.toList() ?: emptyList(),
            videoStabilizationModes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList() ?: emptyList(),
            opticalStabilizationModes = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList() ?: emptyList(),
            aeFpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList(),
            maxJpeg = maxJpeg,
            maxHighResJpeg = maxHigh,
            ultraHighResSensor = ultraHigh,
            pixelArray = pixelArray,
            hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
        )
    }

    private fun hardwareLevelName(level: Int?): String = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "?"
    }

    private fun capabilityName(c: Int): String = when (c) {
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "HIGH_SPEED_VIDEO"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "OFFLINE_PROCESSING"
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            c == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR
        ) "ULTRA_HIGH_RESOLUTION_SENSOR" else "CAP_$c"
    }

    /** Texto de diagnóstico para copiar y pegar. */
    fun report(caps: List<CameraCaps>): String = buildString {
        appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("SoC: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE}")
        appendLine("Cámaras visibles por Camera2: ${caps.size}")
        for (c in caps) {
            val d = c.descriptor
            appendLine()
            appendLine("· Cámara ${d.id} [${d.facing}] nivel ${c.hardwareLevel}")
            appendLine("  focal ${d.focalLengthsMm} mm, sensor ${d.sensorWidthMm}x${d.sensorHeightMm} mm, FOV diag ${"%.1f".format(Math.toDegrees(d.diagonalFovRad))}°")
            appendLine("  zoom ratio: ${d.zoomRatioMin ?: "-"} .. ${d.zoomRatioMax ?: "-"}, zoom digital máx ${d.maxDigitalZoom}")
            appendLine("  lógica multi-cámara: ${d.isLogicalMultiCamera}, físicas: ${d.physicalIds.ifEmpty { listOf("-") }}")
            appendLine("  JPEG máx: ${c.maxJpeg}, alta resolución (lenta): ${c.maxHighResJpeg ?: "-"}, pixel array: ${c.pixelArray}, ultra-alta-res: ${c.ultraHighResSensor}")
            appendLine("  HDR por escena: ${c.hdrSceneMode}, OIS: ${c.hasOis}, EIS: ${c.hasEis}, flash: ${c.hasFlash}")
            appendLine("  FPS AE: ${c.aeFpsRanges.joinToString()}")
            appendLine("  capacidades: ${c.capabilities.joinToString()}")
        }
    }
}
