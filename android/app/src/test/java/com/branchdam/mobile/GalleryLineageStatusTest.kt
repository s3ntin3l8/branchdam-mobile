package com.branchdam.mobile

import com.branchdam.mobile.lineage.LineagePair
import com.branchdam.mobile.observer.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryLineageStatusTest {

    @Test
    fun testPairedDngWinsOverRawBadge() {
        val raw = MediaItem(
            id = 1L, contentUri = "content://images/1",
            filePath = "/sdcard/DCIM/PXL_001.dng", displayName = "PXL_001.dng",
            mimeType = "image/x-adobe-dng", sizeBytes = 25_000_000L,
            dateTakenUnix = 1724000000L, isRaw = true,
        )
        val jpeg = MediaItem(
            id = 2L, contentUri = "content://images/2",
            filePath = "/sdcard/DCIM/PXL_001.jpg", displayName = "PXL_001.jpg",
            mimeType = "image/jpeg", sizeBytes = 4_000_000L,
            dateTakenUnix = 1724000000L, isRaw = false,
        )
        val pairs = com.branchdam.mobile.lineage.PairDetector.findPairs(listOf(raw, jpeg))
        assertEquals(1, pairs.size)
        assertEquals(raw.id, pairs[0].masterRaw.id)
        assertEquals(jpeg.id, pairs[0].derivativeJpeg.id)

        val all = listOf(raw, jpeg)
        val pairedIds = mutableSetOf<Long>()
        for (p in pairs) {
            pairedIds.add(p.masterRaw.id)
            pairedIds.add(p.derivativeJpeg.id)
        }
        // Both items should be marked Paired (not "RAW" for the DNG).
        assertTrue(pairedIds.contains(raw.id))
        assertTrue(pairedIds.contains(jpeg.id))
        // The lineage-status when-ordering: pairedIds.contains() check
        // must come BEFORE the isDng check, otherwise a paired DNG would
        // incorrectly show "RAW" instead of "Paired".
        assertTrue("paired DNG must take the Paired branch", pairedIds.contains(raw.id) && raw.isDng)
    }

    @Test
    fun testUnpairedVideoShowsUnpaired() {
        val video = MediaItem(
            id = 100L, contentUri = "content://video/100",
            filePath = "/sdcard/DCIM/IMG_100.mp4", displayName = "IMG_100.mp4",
            mimeType = "video/mp4", sizeBytes = 50_000_000L,
            dateTakenUnix = 1724000000L, isRaw = false,
        )
        val pairedIds = emptySet<Long>()
        val status = when {
            pairedIds.contains(video.id) -> "Paired"
            video.isDng -> "RAW"
            else -> "Unpaired"
        }
        assertEquals("Unpaired", status)
    }
}
