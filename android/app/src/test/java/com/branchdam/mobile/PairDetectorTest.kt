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

    @Test
    fun testPixelRawSuffixStemPairing() {
        val raw = MediaItem(
            id = 20L,
            contentUri = "content://images/20",
            filePath = "/sdcard/DCIM/Camera/PXL_20260912_185506763.RAW-02.ORIGINAL.dng",
            displayName = "PXL_20260912_185506763.RAW-02.ORIGINAL.dng",
            mimeType = "image/x-adobe-dng",
            sizeBytes = 25_000_000L,
            dateTakenUnix = 1724000200L,
            isRaw = true
        )

        val jpeg = MediaItem(
            id = 21L,
            contentUri = "content://images/21",
            filePath = "/sdcard/DCIM/Camera/PXL_20260912_185506763.RAW-01.jpg",
            displayName = "PXL_20260912_185506763.RAW-01.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4_000_000L,
            dateTakenUnix = 1724000200L,
            isRaw = false
        )

        val pairs = PairDetector.findPairs(listOf(raw, jpeg))
        assertEquals(1, pairs.size)
        assertEquals(raw.id, pairs[0].masterRaw.id)
        assertEquals(jpeg.id, pairs[0].derivativeJpeg.id)
        assertEquals(1.00, pairs[0].confidence, 0.001)
    }

    @Test
    fun testPixelTopShotSuffixStemPairing() {
        val raw = MediaItem(
            id = 30L,
            contentUri = "content://images/30",
            filePath = "/sdcard/DCIM/Camera/PXL_20260912_185504997.TS-001-02.ORIGINAL.dng",
            displayName = "PXL_20260912_185504997.TS-001-02.ORIGINAL.dng",
            mimeType = "image/x-adobe-dng",
            sizeBytes = 25_000_000L,
            dateTakenUnix = 1724000300L,
            isRaw = true
        )

        val jpeg = MediaItem(
            id = 31L,
            contentUri = "content://images/31",
            filePath = "/sdcard/DCIM/Camera/PXL_20260912_185504997.TS-001-01.jpg",
            displayName = "PXL_20260912_185504997.TS-001-01.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4_000_000L,
            dateTakenUnix = 1724000300L,
            isRaw = false
        )

        val pairs = PairDetector.findPairs(listOf(raw, jpeg))
        assertEquals(1, pairs.size)
        assertEquals(raw.id, pairs[0].masterRaw.id)
        assertEquals(jpeg.id, pairs[0].derivativeJpeg.id)
        assertEquals(1.00, pairs[0].confidence, 0.001)
    }

    @Test
    fun testExtractStem_NegativeAndEdgeCases() {
        // Words containing tokens mid-word or prefix must remain intact
        assertEquals("EXAMPLE", PairDetector.extractStem("EXAMPLE.dng"))
        assertEquals("MYCOVER", PairDetector.extractStem("MYCOVER.dng"))
        assertEquals("RAW_001", PairDetector.extractStem("RAW_001.dng"))
        assertEquals("LAST_NIGHT", PairDetector.extractStem("LAST_NIGHT.dng"))
        assertEquals("ALBUM_COVER", PairDetector.extractStem("ALBUM_COVER.dng"))
        assertEquals("road_trip_ACTION", PairDetector.extractStem("road_trip_ACTION.dng"))

        // Trailing suffixes with required separator stripped cleanly
        assertEquals("PXL_20260912_185504997", PairDetector.extractStem("PXL_20260912_185504997.TS-001-02.ORIGINAL.dng"))
        assertEquals("IMG_20260912_120000", PairDetector.extractStem("IMG_20260912_120000.BURST001.dng"))
    }
}
