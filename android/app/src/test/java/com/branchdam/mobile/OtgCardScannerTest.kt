package com.branchdam.mobile

import com.branchdam.mobile.otg.OtgCardScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OtgCardScannerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testScanDirectoryStructure() {
        val root = tempFolder.newFolder("SD_CARD")
        val dcim = File(root, "DCIM/100EOSR5").apply { mkdirs() }
        val misc = File(root, "MISC").apply { mkdirs() }

        // Create media files
        File(dcim, "IMG_0001.CR3").writeBytes(ByteArray(1024))
        File(dcim, "IMG_0001.JPG").writeBytes(ByteArray(512))
        File(dcim, "MVI_0002.MP4").writeBytes(ByteArray(2048))

        // Create non-media files / hidden files
        File(misc, "info.txt").writeText("metadata")
        File(dcim, ".hidden_cache").writeBytes(ByteArray(256))

        val result = OtgCardScanner.scanDirectory(root, "CANON EOS R5")

        assertEquals("CANON EOS R5", result.deviceLabel)
        assertEquals(3, result.totalCount)
        assertEquals(1, result.rawCount)
        assertEquals(1, result.jpegCount)
        assertEquals(1, result.videoCount)
        assertEquals(3584L, result.totalSizeBytes)

        val fileNames = result.candidates.map { it.fileName }
        assertTrue(fileNames.contains("IMG_0001.CR3"))
        assertTrue(fileNames.contains("IMG_0001.JPG"))
        assertTrue(fileNames.contains("MVI_0002.MP4"))
    }

    @Test
    fun testScanEmptyOrMissingDirectory() {
        val missingDir = File(tempFolder.root, "non_existent")
        val result = OtgCardScanner.scanDirectory(missingDir)

        assertEquals(0, result.totalCount)
        assertEquals(0L, result.totalSizeBytes)
    }
}
