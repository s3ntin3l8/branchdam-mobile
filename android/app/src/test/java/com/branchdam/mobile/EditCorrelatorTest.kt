package com.branchdam.mobile

import com.branchdam.mobile.lineage.EditCorrelator
import com.branchdam.mobile.observer.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class EditCorrelatorTest {

    @Test
    fun testInPhoneEditCorrelation() {
        val master = MediaItem(
            id = 1L,
            contentUri = "content://images/1",
            filePath = "/sdcard/DCIM/Camera/PXL_20260829_051500.jpg",
            displayName = "PXL_20260829_051500.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4_000_000L,
            dateTakenUnix = 1724000000L,
            isRaw = false
        )

        val luminarEdit = MediaItem(
            id = 2L,
            contentUri = "content://images/2",
            filePath = "/sdcard/Pictures/Luminar/PXL_20260829_051500_Luminar.jpg",
            displayName = "PXL_20260829_051500_Luminar.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 5_000_000L,
            dateTakenUnix = 1724000050L,
            isRaw = false
        )

        val googlePhotosEdit = MediaItem(
            id = 3L,
            contentUri = "content://images/3",
            filePath = "/sdcard/Pictures/Edited/PXL_20260829_051500_edited.jpg",
            displayName = "PXL_20260829_051500_edited.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4_500_000L,
            dateTakenUnix = 1724000060L,
            isRaw = false
        )

        val edits = EditCorrelator.findInPhoneEdits(listOf(master), listOf(luminarEdit, googlePhotosEdit))
        assertEquals(2, edits.size)
        assertEquals("Luminar Neo Mobile", edits[0].editorApp)
        assertEquals("Google Photos Editor", edits[1].editorApp)

        val registered = EditCorrelator.registerEditLineage(edits)
        assertEquals(2, registered)
    }
}
