package com.branchdam.mobile

import com.branchdam.mobile.lineage.InPhoneEdit
import com.branchdam.mobile.lineage.LineagePair
import com.branchdam.mobile.observer.MediaItem
import com.branchdam.mobile.ui.lineage.LineageViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineageViewModelTest {

    @Test
    fun testConvertPairToCandidate() {
        val raw = MediaItem(
            id = 1L,
            contentUri = "content://images/1",
            filePath = "/sdcard/DCIM/PXL_001.dng",
            displayName = "PXL_001.dng",
            mimeType = "image/x-adobe-dng",
            sizeBytes = 25_000_000L,
            dateTakenUnix = 1724000000L,
            isRaw = true,
        )
        val jpeg = MediaItem(
            id = 2L,
            contentUri = "content://images/2",
            filePath = "/sdcard/DCIM/PXL_001.jpg",
            displayName = "PXL_001.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4_000_000L,
            dateTakenUnix = 1724000000L,
            isRaw = false,
        )
        val pair = LineagePair(raw, jpeg, 1.00, "android_camera_pair")
        val candidate = LineageViewModel.fromPair(pair)
        assertEquals("PXL_001.dng", candidate.masterFilename)
        assertEquals("PXL_001.jpg", candidate.childFilename)
        assertEquals(1.00, candidate.confidence, 0.001)
        assertEquals("android_camera_pair", candidate.resolver)
        assertTrue(candidate.edgeId.contains("|"))
    }

    @Test
    fun testConvertEditToCandidate() {
        val master = MediaItem(
            id = 10L,
            contentUri = "content://images/10",
            filePath = "/sdcard/DCIM/IMG_001.jpg",
            displayName = "IMG_001.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4_000_000L,
            dateTakenUnix = 1724000000L,
            isRaw = false,
        )
        val edited = MediaItem(
            id = 11L,
            contentUri = "content://images/11",
            filePath = "/sdcard/Pictures/Edited/IMG_001 Edited.jpg",
            displayName = "IMG_001 Edited.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 5_000_000L,
            dateTakenUnix = 1724000100L,
            isRaw = false,
        )
        val edit = InPhoneEdit(master, edited, "Google Photos Editor", 0.95)
        val candidate = LineageViewModel.fromEdit(edit)
        assertEquals("IMG_001.jpg", candidate.masterFilename)
        assertEquals("IMG_001 Edited.jpg", candidate.childFilename)
        assertEquals(0.95, candidate.confidence, 0.001)
        assertTrue(candidate.resolver.contains("google_photos"))
    }

    @Test
    fun testInPhoneEditResolverFormat() {
        assertEquals("in_phone_google_photos_editor", com.branchdam.mobile.ui.lineage.InPhoneEditResolver.format("Google Photos Editor"))
        assertEquals("in_phone_luminar_neo_mobile", com.branchdam.mobile.ui.lineage.InPhoneEditResolver.format("Luminar Neo Mobile"))
    }
}
