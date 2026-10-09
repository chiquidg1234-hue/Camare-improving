package com.lumacam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePlannerTest {
    private val gb = 1024L * 1024 * 1024

    @Test
    fun twelveMegapixelWithPlentyOfMemoryKeepsRequestedFrames() {
        val p = CapturePlanner.plan(4000, 3000, 6, 2 * gb)
        assertEquals(6, p.frames)
        assertEquals(1, p.decodeSampleSize)
        assertNull(p.note)
    }

    @Test
    fun framesAreClampedToMax() {
        assertEquals(CapturePlanner.MAX_FRAMES, CapturePlanner.plan(4000, 3000, 50, 4 * gb).frames)
        assertEquals(1, CapturePlanner.plan(4000, 3000, 0, 4 * gb).frames)
    }

    @Test
    fun hundredEightMegapixelDisablesMultiFrame() {
        val p = CapturePlanner.plan(12000, 9000, 6, 4 * gb)
        assertEquals(1, p.frames)
        assertEquals(1, p.decodeSampleSize)
        assertNotNull(p.note)
    }

    @Test
    fun lowMemoryReducesFrames() {
        // 48 MB por frame; con 256 MB caben 3 frames + 2 buffers.
        val p = CapturePlanner.plan(4000, 3000, 8, 256L * 1024 * 1024)
        assertTrue(p.frames in 2..4)
        assertNotNull(p.note)
    }

    @Test
    fun requestedFramesRules() {
        assertEquals(1, CapturePlanner.requestedFrames(false, false, false, 0, 4))
        assertEquals(1, CapturePlanner.requestedFrames(true, true, false, 6, 4))
        assertEquals(1, CapturePlanner.requestedFrames(true, false, true, 6, 8))
        assertEquals(6, CapturePlanner.requestedFrames(true, false, false, 6, 4))
        assertEquals(8, CapturePlanner.requestedFrames(true, false, false, 0, 8))
        assertEquals(CapturePlanner.MAX_FRAMES, CapturePlanner.requestedFrames(true, false, false, 20, 4))
    }

    @Test
    fun veryLowMemoryDownsamplesDecode() {
        val p = CapturePlanner.plan(12000, 9000, 1, 512L * 1024 * 1024)
        assertTrue(p.decodeSampleSize >= 2)
        assertEquals(1, p.frames)
    }
}
