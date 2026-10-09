package com.lumacam.core.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ZoomPlannerTest {

    // Sensor principal típico (≈ 7.6 x 5.7 mm) con lente de 5.4 mm (ángulo de ~24 mm equivalentes).
    private fun main(id: String = "0", min: Float? = 1f, max: Float? = 10f, logical: Boolean = false, physical: List<String> = emptyList()) =
        CameraDescriptor(
            id = id, facing = Facing.BACK, focalLengthsMm = listOf(5.4f),
            sensorWidthMm = 7.6f, sensorHeightMm = 5.7f, zoomRatioMin = min, zoomRatioMax = max,
            maxDigitalZoom = 10f, isLogicalMultiCamera = logical, physicalIds = physical,
        )

    private fun ultraWide(id: String = "2", focal: Float = 1.7f) = CameraDescriptor(
        id = id, facing = Facing.BACK, focalLengthsMm = listOf(focal),
        sensorWidthMm = 3.7f, sensorHeightMm = 2.8f, zoomRatioMin = 1f, zoomRatioMax = 4f,
    )

    private fun depth(id: String = "3") = CameraDescriptor(
        id = id, facing = Facing.BACK, focalLengthsMm = listOf(1.9f),
        sensorWidthMm = 1.8f, sensorHeightMm = 1.3f, zoomRatioMin = 1f, zoomRatioMax = 1f,
        backwardCompatible = false,
    )

    private val front = CameraDescriptor(
        id = "1", facing = Facing.FRONT, focalLengthsMm = listOf(2.2f),
        sensorWidthMm = 3.2f, sensorHeightMm = 2.4f, zoomRatioMin = 1f, zoomRatioMax = 4f,
    )

    @Test
    fun singleUsefulCameraStartsAtOneWithWarning() {
        // Caso esperado en el Honor X7c 5G: principal 50 MP + profundidad 2 MP (no utilizable) + frontal.
        val plan = ZoomPlanner.plan(listOf(main(), front, depth()))!!
        assertEquals("0", plan.cameraId)
        assertEquals(1f, plan.initialZoomRatio, 0f)
        assertEquals(UltraWideKind.NONE, plan.ultraWide)
        assertEquals(ZoomPlanner.WARNING_NO_ULTRAWIDE, plan.warning)
        assertEquals(listOf("1x", "2x"), plan.stops.map { it.label })
    }

    @Test
    fun logicalCameraWithPointSixMinimumStartsAtPointSix() {
        val plan = ZoomPlanner.plan(listOf(main(min = 0.6f, logical = true, physical = listOf("2", "3")), front))!!
        assertEquals(UltraWideKind.LOGICAL_ZOOM, plan.ultraWide)
        assertEquals(0.6f, plan.initialZoomRatio, 1e-6f)
        assertNull(plan.warning)
        assertEquals(listOf("0.6x", "1x", "2x"), plan.stops.map { it.label })
    }

    @Test
    fun logicalCameraWithPointFiveMinimumStillStartsAtPointSix() {
        val plan = ZoomPlanner.plan(listOf(main(min = 0.5f, logical = true)))!!
        assertEquals(0.6f, plan.initialZoomRatio, 1e-6f)
        assertEquals(0.5f, plan.minZoomRatio, 1e-6f)
    }

    @Test
    fun logicalCameraNotReachingPointSixStartsAtItsMinimumWithNote() {
        val plan = ZoomPlanner.plan(listOf(main(min = 0.7f, logical = true)))!!
        assertEquals(0.7f, plan.initialZoomRatio, 1e-6f)
        assertNotNull(plan.warning)
    }

    @Test
    fun separateUltraWideCameraIsSelected() {
        val uw = ultraWide()
        val plan = ZoomPlanner.plan(listOf(main(), front, uw))!!
        assertEquals(UltraWideKind.SEPARATE_CAMERA, plan.ultraWide)
        assertEquals("2", plan.cameraId)
        assertEquals(1f, plan.initialZoomRatio, 0f)
        val eq = plan.ultraWideEquivalent!!
        assertEquals(eq, ZoomPlanner.equivalentZoom(main(), uw)!!, 1e-6f)
        assertEquals("0", plan.mainCameraId)
        assertEquals("2", plan.stops.first().cameraId)
    }

    @Test
    fun narrowerOrSimilarCameraIsNotUltraWide() {
        // Una "macro" o de profundidad con FOV parecido al principal no cuenta.
        val similar = CameraDescriptor(
            id = "4", facing = Facing.BACK, focalLengthsMm = listOf(2.5f),
            sensorWidthMm = 3.4f, sensorHeightMm = 2.6f, zoomRatioMin = 1f, zoomRatioMax = 1f,
        )
        val plan = ZoomPlanner.plan(listOf(main(), similar))!!
        assertEquals(UltraWideKind.NONE, plan.ultraWide)
        assertEquals(1f, plan.initialZoomRatio, 0f)
    }

    @Test
    fun physicalSubCameraOfLogicalIsNotTreatedAsSeparate() {
        val plan = ZoomPlanner.plan(listOf(main(logical = true, physical = listOf("2")), ultraWide("2")))!!
        assertEquals(UltraWideKind.NONE, plan.ultraWide)
    }

    @Test
    fun missingZoomRatioRangeFallsBackToDigitalZoom() {
        val plan = ZoomPlanner.plan(listOf(main(min = null, max = null)))!!
        assertEquals(1f, plan.minZoomRatio, 0f)
        assertEquals(10f, plan.maxZoomRatio, 0f)
        assertEquals(1f, plan.initialZoomRatio, 0f)
    }

    @Test
    fun onlyFrontCameraStillProducesPlan() {
        val plan = ZoomPlanner.plan(listOf(front))!!
        assertEquals("1", plan.cameraId)
        assertEquals(1f, plan.initialZoomRatio, 0f)
    }

    @Test
    fun noCamerasGivesNull() {
        assertNull(ZoomPlanner.plan(emptyList()))
    }

    @Test
    fun formatting() {
        assertEquals("0.6", ZoomPlanner.fmt(0.6f))
        assertEquals("1", ZoomPlanner.fmt(1.0f))
        assertEquals("0.5", ZoomPlanner.fmt(0.54f))
    }
}
