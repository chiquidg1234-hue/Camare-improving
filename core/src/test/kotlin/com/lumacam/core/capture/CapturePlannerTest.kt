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
    fun veryLowMemoryDownsamplesDecode() {
        val p = CapturePlanner.plan(12000, 9000, 1, 512L * 1024 * 1024)
        assertTrue(p.decodeSampleSize >= 2)
        assertEquals(1, p.frames)
    }
}
