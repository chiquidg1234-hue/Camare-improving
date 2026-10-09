package com.lumacam.photo

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoPipelineTest {
    @Test
    fun rotationMapsToExifOrientation() {
        assertEquals(ExifInterface.ORIENTATION_NORMAL, PhotoPipeline.orientationTag(0))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, PhotoPipeline.orientationTag(90))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_180, PhotoPipeline.orientationTag(180))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_270, PhotoPipeline.orientationTag(270))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_270, PhotoPipeline.orientationTag(-90))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, PhotoPipeline.orientationTag(360))
    }
}
