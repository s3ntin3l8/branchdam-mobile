package com.branchdam.mobile

import com.branchdam.mobile.lineage.MotionPhotoExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class MotionPhotoExtractorTest {

    @Test
    fun testMotionPhotoDetection() {
        val xmpHeader = "http://ns.google.com/photos/1.0/camera/ GCamera:MotionPhoto=\"1\" GCamera:MicroVideoOffset=\"150000\" padding data"
        val stream = ByteArrayInputStream(xmpHeader.toByteArray(Charsets.ISO_8859_1))

        val info = MotionPhotoExtractor.parseMotionPhotoStream(stream, fileLength = 500_000L)
        assertTrue(info.isMotionPhoto)
        assertEquals(150_000L, info.microVideoLength)
        assertEquals(350_000L, info.microVideoOffset)
    }

    @Test
    fun testNonMotionPhoto() {
        val plainJpeg = "standard jpeg image without xmp metadata tags"
        val stream = ByteArrayInputStream(plainJpeg.toByteArray(Charsets.ISO_8859_1))

        val info = MotionPhotoExtractor.parseMotionPhotoStream(stream, fileLength = 50_000L)
        assertFalse(info.isMotionPhoto)
    }
}
