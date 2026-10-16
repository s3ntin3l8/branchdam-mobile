package com.branchdam.mobile

import com.branchdam.mobile.observer.MediaItem
import com.branchdam.mobile.observer.UploadSource
import com.branchdam.mobile.observer.UploadStaging
import com.branchdam.mobile.observer.chooseUploadSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for how uploads read camera-roll media. On API 29+ the DATA path
 * and plain content URI serve location-redacted bytes, so the original is
 * staged via setRequireOriginal when ACCESS_MEDIA_LOCATION is granted, and
 * the staged copy is deleted once its upload COMPLETED.
 */
class UploadStagingTest {

    @Test
    fun legacyStorageReadsTheDataPath() {
        assertEquals(UploadSource.DATA_PATH, chooseUploadSource(28, hasMediaLocation = false))
        assertEquals(UploadSource.DATA_PATH, chooseUploadSource(28, hasMediaLocation = true))
    }

    @Test
    fun scopedStorageStagesTheOriginalWhenAllowed() {
        assertEquals(UploadSource.STAGE_ORIGINAL, chooseUploadSource(29, hasMediaLocation = true))
        assertEquals(UploadSource.STAGE_ORIGINAL, chooseUploadSource(35, hasMediaLocation = true))
    }

    @Test
    fun scopedStorageWithoutLocationPermissionStagesRedactedBytes() {
        assertEquals(UploadSource.STAGE_REDACTED, chooseUploadSource(29, hasMediaLocation = false))
        assertEquals(UploadSource.STAGE_REDACTED, chooseUploadSource(34, hasMediaLocation = false))
    }

    @Test
    fun stagedFileNameIsUniquePerRowAndKeepsTheExtension() {
        val item = MediaItem(
            id = 42L,
            contentUri = "content://media/external/images/media/42",
            filePath = "",
            displayName = "PXL 2026/10 #1.dng",
            mimeType = "image/x-adobe-dng",
            sizeBytes = 1L,
            dateTakenUnix = 0L,
            isRaw = true,
        )
        val name = UploadStaging.stagedFileName(item)
        assertEquals("42_PXL_2026_10__1.dng", name)
        assertFalse(name.contains('/'))
    }

    @Test
    fun completedUploadsAreCleanedUpImmediately() {
        val now = 10_000_000L
        assertTrue(UploadStaging.shouldDeleteStaged("1_a.jpg", "COMPLETED", now, now))
        assertTrue(UploadStaging.shouldDeleteStaged("1_a.jpg", "OFFLOADED", now, now))
    }

    @Test
    fun copiesStillNeededForUploadAreKept() {
        val ancient = 0L
        val now = 100L * UploadStaging.ORPHAN_GRACE_MS
        for (status in listOf("PENDING", "IN_PROGRESS", "FAILED")) {
            assertFalse(status, UploadStaging.shouldDeleteStaged("1_a.jpg", status, ancient, now))
        }
    }

    @Test
    fun unreferencedCopiesGetAGracePeriod() {
        val now = 10L * UploadStaging.ORPHAN_GRACE_MS
        val fresh = now - 1_000L
        val stale = now - UploadStaging.ORPHAN_GRACE_MS - 1L
        assertFalse(UploadStaging.shouldDeleteStaged("1_a.jpg", null, fresh, now))
        assertTrue(UploadStaging.shouldDeleteStaged("1_a.jpg", null, stale, now))
        assertTrue(UploadStaging.shouldDeleteStaged("1_a.jpg", "NOT_ENQUEUED", stale, now))
    }

    @Test
    fun interruptedCopiesAreRemovedAfterTheirGrace() {
        val now = 10L * UploadStaging.PART_GRACE_MS
        assertFalse(UploadStaging.shouldDeleteStaged("1_a.jpg.part", null, now - 1_000L, now))
        assertTrue(
            UploadStaging.shouldDeleteStaged("1_a.jpg.part", "PENDING", now - UploadStaging.PART_GRACE_MS - 1L, now),
        )
    }
}
