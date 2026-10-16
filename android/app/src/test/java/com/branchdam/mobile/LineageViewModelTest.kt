package com.branchdam.mobile

import com.branchdam.mobile.lineage.InPhoneEdit
import com.branchdam.mobile.lineage.LineagePair
import com.branchdam.mobile.lineage.LineageReview
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
        assertEquals("content://images/1", candidate.masterUri)
        assertEquals("content://images/2", candidate.childUri)
        assertEquals("image/x-adobe-dng", candidate.masterMimeType)
        assertEquals("image/jpeg", candidate.childMimeType)
        assertEquals("content://images/1", candidate.resolvedMasterUri)
        assertEquals("content://images/2", candidate.resolvedChildUri)
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
        assertEquals("content://images/10", candidate.masterUri)
        assertEquals("content://images/11", candidate.childUri)
        assertEquals("image/jpeg", candidate.masterMimeType)
        assertEquals("image/jpeg", candidate.childMimeType)
        assertTrue(candidate.resolver.contains("google_photos"))
    }

    @Test
    fun testInPhoneEditResolverFormat() {
        assertEquals("in_phone_google_photos_editor", com.branchdam.mobile.ui.lineage.InPhoneEditResolver.format("Google Photos Editor"))
        assertEquals("in_phone_luminar_neo_mobile", com.branchdam.mobile.ui.lineage.InPhoneEditResolver.format("Luminar Neo Mobile"))
    }
    private fun media(id: Long, name: String, raw: Boolean = false, path: String = "/sdcard/DCIM/Camera/$name") = MediaItem(
        id = id,
        contentUri = "content://images/$id",
        filePath = path,
        displayName = name,
        mimeType = if (raw) "image/x-adobe-dng" else "image/jpeg",
        sizeBytes = 1L,
        dateTakenUnix = 1724000000L,
        isRaw = raw,
    )

    @Test
    fun pendingReview_offersOnlyUndecidedGuesses() {
        val exact = LineagePair(media(1, "PXL_1.dng", raw = true), media(2, "PXL_1.jpg"), 1.00, "android_camera_pair")
        val proximity = LineagePair(media(3, "RAW_9.dng", raw = true), media(4, "IMG_9.jpg"), 0.95, "android_camera_pair")
        val rejected = LineagePair(media(5, "RAW_8.dng", raw = true), media(6, "IMG_8.jpg"), 0.95, "android_camera_pair")
        val edit = InPhoneEdit(media(7, "IMG_7.jpg"), media(8, "IMG_7_edited.jpg", path = "/sdcard/Pictures/Edited/IMG_7_edited.jpg"), "Google Photos Editor", 0.95)
        val confirmedEdit = InPhoneEdit(media(9, "IMG_6.jpg"), media(10, "IMG_6_edited.jpg"), "Google Photos Editor", 0.95)

        val decided = setOf(
            LineageReview.pairKey("content://images/5", "content://images/6"),
            LineageReview.pairKey("content://images/9", "content://images/10"),
        )
        val pending = LineageViewModel.pendingReview(listOf(exact, proximity, rejected), listOf(edit, confirmedEdit), decided)

        assertEquals(
            "exact pairs are automatic, decided pairs are never offered again",
            listOf("content://images/3|content://images/4", "content://images/7|content://images/8"),
            pending.map { it.edgeId },
        )
        // The candidate carries the resolver's confidence, which is what a
        // confirmation sends: never bumped to 1.00.
        assertTrue(pending.all { it.confidence == 0.95 })
    }

    @Test
    fun pendingReview_dedupesAPairFoundTwice() {
        val pair = LineagePair(media(1, "IMG_1.jpg"), media(2, "IMG_1_edited.jpg"), 0.95, "android_camera_pair")
        val edit = InPhoneEdit(media(1, "IMG_1.jpg"), media(2, "IMG_1_edited.jpg"), "Google Photos Editor", 0.95)
        assertEquals(1, LineageViewModel.pendingReview(listOf(pair), listOf(edit), emptySet()).size)
    }
}
