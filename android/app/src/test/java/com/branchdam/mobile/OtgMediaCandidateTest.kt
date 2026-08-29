package com.branchdam.mobile

import com.branchdam.mobile.otg.OtgMediaCandidate
import com.branchdam.mobile.otg.OtgScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtgMediaCandidateTest {

    @Test
    fun testRawExtensionRecognition() {
        assertTrue(OtgMediaCandidate.isRawExtension("IMG_0001.CR3"))
        assertTrue(OtgMediaCandidate.isRawExtension("_DSC1234.ARW"))
        assertTrue(OtgMediaCandidate.isRawExtension("DSC_5678.NEF"))
        assertTrue(OtgMediaCandidate.isRawExtension("PXL_2026.dng"))
        assertTrue(OtgMediaCandidate.isRawExtension("PXL_2026.DNG"))
        assertTrue(OtgMediaCandidate.isRawExtension("test.orf"))
        assertTrue(OtgMediaCandidate.isRawExtension("test.rw2"))

        assertFalse(OtgMediaCandidate.isRawExtension("IMG_0001.JPG"))
        assertFalse(OtgMediaCandidate.isRawExtension("video.mp4"))
        assertFalse(OtgMediaCandidate.isRawExtension("document.pdf"))
    }

    @Test
    fun testVideoExtensionRecognition() {
        assertTrue(OtgMediaCandidate.isVideoExtension("clip.mp4"))
        assertTrue(OtgMediaCandidate.isVideoExtension("movie.MOV"))
        assertTrue(OtgMediaCandidate.isVideoExtension("test.m4v"))

        assertFalse(OtgMediaCandidate.isVideoExtension("photo.jpg"))
        assertFalse(OtgMediaCandidate.isVideoExtension("raw.dng"))
    }

    @Test
    fun testSupportedMediaFilter() {
        assertTrue(OtgMediaCandidate.isSupportedMedia("photo.jpg"))
        assertTrue(OtgMediaCandidate.isSupportedMedia("photo.heic"))
        assertTrue(OtgMediaCandidate.isSupportedMedia("raw.cr3"))
        assertTrue(OtgMediaCandidate.isSupportedMedia("video.mp4"))

        assertFalse(OtgMediaCandidate.isSupportedMedia("readme.txt"))
        assertFalse(OtgMediaCandidate.isSupportedMedia(".DS_Store"))
        assertFalse(OtgMediaCandidate.isSupportedMedia("archive.zip"))
    }

    @Test
    fun testByteFormatting() {
        assertEquals("0 B", OtgMediaCandidate.formatBytes(0))
        assertEquals("500 B", OtgMediaCandidate.formatBytes(500))
        assertEquals("1.0 KB", OtgMediaCandidate.formatBytes(1024))
        assertEquals("1.5 MB", OtgMediaCandidate.formatBytes(1_572_864))
        assertEquals("4.2 GB", OtgMediaCandidate.formatBytes(4_509_715_660))
    }

    @Test
    fun testScanResultAggregation() {
        val candidates = listOf(
            OtgMediaCandidate("uri1", "DCIM/100CANON/IMG_0001.CR3", "IMG_0001.CR3", 30_000_000, 1700000000, isRaw = true, isVideo = false),
            OtgMediaCandidate("uri2", "DCIM/100CANON/IMG_0001.JPG", "IMG_0001.JPG", 5_000_000, 1700000000, isRaw = false, isVideo = false),
            OtgMediaCandidate("uri3", "DCIM/100CANON/MVI_0002.MP4", "MVI_0002.MP4", 100_000_000, 1700000010, isRaw = false, isVideo = true)
        )

        val result = OtgScanResult(
            deviceLabel = "CANON R5",
            rootUri = "content://tree/otg",
            candidates = candidates
        )

        assertEquals(3, result.totalCount)
        assertEquals(1, result.rawCount)
        assertEquals(1, result.jpegCount)
        assertEquals(1, result.videoCount)
        assertEquals(135_000_000, result.totalSizeBytes)
    }
}
