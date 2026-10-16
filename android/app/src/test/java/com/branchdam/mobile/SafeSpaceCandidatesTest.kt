package com.branchdam.mobile

import com.branchdam.mobile.observer.MediaItem
import com.branchdam.mobile.triage.SafeSpaceVerdict
import com.branchdam.mobile.triage.reclaimCandidateItems
import com.branchdam.mobile.triage.summarizeSafeSpace
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for how the Safe Space screen builds its candidates: items whose
 * upload COMPLETED (the engine has a node UUID for them) are asked about,
 * and only the ones the engine's verdict marks eligible are reclaimable.
 * Pre-fix the screen counted only items that were already offloaded, so a
 * reclaim could never start.
 */
class SafeSpaceCandidatesTest {

    private fun item(id: Long, size: Long) = MediaItem(
        id = id,
        contentUri = "content://media/external/images/media/$id",
        filePath = "/storage/emulated/0/DCIM/Camera/IMG_$id.jpg",
        displayName = "IMG_$id.jpg",
        mimeType = "image/jpeg",
        sizeBytes = size,
        dateTakenUnix = 1_700_000_000L + id,
        isRaw = false,
    )

    @Test
    fun candidatesAreUploadedItemsOnly() {
        val completed = item(1, 100)
        val offloadedButPresent = item(2, 200)
        val pending = item(3, 300)
        val failed = item(4, 400)
        val unknown = item(5, 500)
        val statuses = mapOf(
            completed.contentUri to "COMPLETED",
            offloadedButPresent.contentUri to "OFFLOADED",
            pending.contentUri to "PENDING",
            failed.contentUri to "FAILED",
            // Keyed by path, not local ID: must not count.
            unknown.filePath to "COMPLETED",
        )

        val candidates = reclaimCandidateItems(
            listOf(completed, offloadedButPresent, pending, failed, unknown, completed),
            statuses,
        )

        assertEquals(listOf(completed, offloadedButPresent), candidates)
    }

    @Test
    fun summaryCountsOnlyEligibleVerdicts() {
        val a = item(1, 1_000)
        val b = item(2, 2_000)
        val c = item(3, 4_000)
        val verdicts = listOf(
            SafeSpaceVerdict(a.contentUri, eligible = true),
            SafeSpaceVerdict(b.contentUri, eligible = false, reason = "VERIFIED_REQUIRED: not on tier 3"),
            // c has no verdict at all: not eligible.
        )

        val summary = summarizeSafeSpace(listOf(a, b, c), verdicts)

        assertEquals(listOf(a), summary.eligible)
        assertEquals(1_000L, summary.reclaimableBytes)
        assertEquals(3, summary.checkedCount)
        assertEquals(2, summary.ineligibleCount)
    }

    @Test
    fun emptyInputsGiveAnEmptySummary() {
        val summary = summarizeSafeSpace(emptyList(), emptyList())
        assertEquals(0, summary.eligible.size)
        assertEquals(0L, summary.reclaimableBytes)
        assertEquals(0, summary.checkedCount)
        assertEquals(0, summary.ineligibleCount)
    }
}
