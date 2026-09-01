package com.branchdam.mobile

import com.branchdam.mobile.observer.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaItemTest {

    @Test
    fun testMediaItemFlags() {
        val dngPhoto = MediaItem(
            id = 101L,
            contentUri = "content://media/external/images/media/101",
            filePath = "/sdcard/DCIM/Camera/PXL_20260829_001.dng",
            displayName = "PXL_20260829_001.dng",
            mimeType = "image/x-adobe-dng",
            sizeBytes = 24_000_000L,
            dateTakenUnix = 1724000000L,
            isRaw = true
        )

        assertTrue(dngPhoto.isDng)
        assertTrue(dngPhoto.isRaw)
        assertFalse(dngPhoto.isVideo)

        val mp4Video = MediaItem(
            id = 102L,
            contentUri = "content://media/external/video/media/102",
            filePath = "/sdcard/DCIM/Camera/PXL_20260829_002.mp4",
            displayName = "PXL_20260829_002.mp4",
            mimeType = "video/mp4",
            sizeBytes = 150_000_000L,
            dateTakenUnix = 1724000010L,
            isRaw = false
        )

        assertTrue(mp4Video.isVideo)
        assertFalse(mp4Video.isDng)
        assertFalse(mp4Video.isRaw)
    }

    @Test
    fun testEngineHolderStub() {
        val mediaId = EngineHolder.enqueueMedia(
            localPath = "/sdcard/DCIM/Camera/test.jpg",
            filename = "test.jpg",
            capturedAtUnix = 1724000000L,
            localId = "content://test/1"
        )
        assertEquals(1L, mediaId)

        EngineHolder.syncBatch(120, 10)
    }
}
