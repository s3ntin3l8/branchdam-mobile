package com.branchdam.mobile

import com.branchdam.mobile.lineage.PairDetector
import com.branchdam.mobile.observer.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PairDetectorTest {

    @Test
    fun testExactStemPairing() {
        val raw = MediaItem(
            id = 1L,
            contentUri = "content://images/1",
            filePath = "/sdcard/DCIM/Camera/PXL_20260829_051500.dng",
            displayName = "PXL_20260829_051500.dng",
            mimeType = "image/x-adobe-dng",
            sizeBytes = 25_000_000L,
            dateTakenUnix = 1724000000L,
            isRaw = true
        )

        val jpeg = MediaItem(
            id = 2L,
            contentUri = "content://images/2",
            filePath = "/sdcard/DCIM/Camera/PXL_20260829_051500.jpg",
            displayName = "PXL_20260829_051500.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4_000_000L,
            dateTakenUnix = 1724000000L,
            isRaw = false
        )

        val other = MediaItem(
            id = 3L,
            contentUri = "content://images/3",
            filePath = "/sdcard/DCIM/Camera/PXL_20260829_051600.jpg",
            displayName = "PXL_20260829_051600.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 3_500_000L,
            dateTakenUnix = 1724000060L,
            isRaw = false
        )

        val pairs = PairDetector.findPairs(listOf(raw, jpeg, other))
        assertEquals(1, pairs.size)
        assertEquals(raw.id, pairs[0].masterRaw.id)
        assertEquals(jpeg.id, pairs[0].derivativeJpeg.id)
        assertEquals(1.00, pairs[0].confidence, 0.001)

        val registered = PairDetector.registerPairLineage(pairs)
        assertEquals(1, registered)
    }

    @Test
    fun testTimeProximityPairing() {
        val raw = MediaItem(
            id = 10L,
            contentUri = "content://images/10",
            filePath = "/sdcard/DCIM/Camera/RAW_001.dng",
            displayName = "RAW_001.dng",
            mimeType = "image/x-adobe-dng",
            sizeBytes = 25_000_000L,
            dateTakenUnix = 1724000100L,
            isRaw = true
        )

        val jpeg = MediaItem(
            id = 11L,
            contentUri = "content://images/11",
            filePath = "/sdcard/DCIM/Camera/IMG_001.jpg",
            displayName = "IMG_001.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4_000_000L,
            dateTakenUnix = 1724000101L, // 1s delta
            isRaw = false
        )

        val pairs = PairDetector.findPairs(listOf(raw, jpeg))
        assertEquals(1, pairs.size)
        assertEquals(0.95, pairs[0].confidence, 0.001)
    }
}
