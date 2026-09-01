package com.branchdam.mobile

import android.content.Context
import com.branchdam.mobile.triage.SafeSpaceManager
import com.branchdam.mobile.triage.SafeSpaceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Tests for [SafeSpaceManager.reclaimSafeSpace] using the test seams
 * (engineReclaim, deleteLocal) to avoid requiring a real engine or
 * ContentResolver. Production callers pass the defaults; tests pass
 * lambdas that record what was called.
 */
class SafeSpaceManagerTest {

    /**
     * The context is only used by the default `deleteLocal` (which
     * calls `contentResolver.delete`). Since every test in this file
     * overrides `deleteLocal`, the context is never dereferenced.
     * We pass a mock to satisfy the non-null parameter type without
     * actually constructing a Context (which would require Android
     * framework classes).
     */
    private val stubContext: Context = mock()

    @Test
    fun testSafeSpaceResultModel() {
        val result = SafeSpaceResult(
            totalChecked = 10,
            eligibleCount = 8,
            reclaimedCount = 8,
            freedBytesEstimate = 120_000_000L
        )

        assertEquals(10, result.totalChecked)
        assertEquals(8, result.eligibleCount)
        assertEquals(8, result.reclaimedCount)
        assertEquals(120_000_000L, result.freedBytesEstimate)
    }

    @Test
    fun testReclaimEligibleTriggersDelete() {
        // Engine says "eligible", delete succeeds → reclaimedCount=1.
        val reclaimed = mutableListOf<String>()
        val result = SafeSpaceManager.reclaimSafeSpace(
            context = stubContext,
            candidateUris = listOf("content://media/external/images/1"),
            statusChecker = { _ -> true to 1_000_000L },
            engineReclaim = { true },
            deleteLocal = { _, uri ->
                reclaimed.add(uri)
                true
            },
        )

        assertEquals(1, result.totalChecked)
        assertEquals(1, result.eligibleCount)
        assertEquals(1, result.reclaimedCount)
        assertEquals(1_000_000L, result.freedBytesEstimate)
        assertEquals(listOf("content://media/external/images/1"), reclaimed)
    }

    @Test
    fun testReclaimIneligibleDoesNotDelete() {
        // Engine says "not eligible" → delete is NOT called, reclaimedCount=0.
        var deleteCalled = false
        val result = SafeSpaceManager.reclaimSafeSpace(
            context = stubContext,
            candidateUris = listOf("content://media/external/images/2"),
            statusChecker = { _ -> true to 1_000_000L },
            engineReclaim = { false },
            deleteLocal = { _, _ ->
                deleteCalled = true
                true
            },
        )

        assertEquals(1, result.totalChecked)
        assertEquals(1, result.eligibleCount) // verified, but engine said no
        assertEquals(0, result.reclaimedCount)
        assertEquals(0L, result.freedBytesEstimate)
        assertTrue("delete must not be called when engine says ineligible", !deleteCalled)
    }

    @Test
    fun testReclaimUnverifiedSkipsEntirely() {
        // statusChecker says not verified → loop continues, engine never called.
        var engineCalled = false
        var deleteCalled = false
        val result = SafeSpaceManager.reclaimSafeSpace(
            context = stubContext,
            candidateUris = listOf("content://media/external/images/3"),
            statusChecker = { _ -> false to 0L },
            engineReclaim = {
                engineCalled = true
                true
            },
            deleteLocal = { _, _ ->
                deleteCalled = true
                true
            },
        )

        assertEquals(1, result.totalChecked)
        assertEquals(0, result.eligibleCount)
        assertEquals(0, result.reclaimedCount)
        assertTrue("engine must not be called for unverified items", !engineCalled)
        assertTrue("delete must not be called for unverified items", !deleteCalled)
    }

    @Test
    fun testReclaimDeleteFailureRollsBack() {
        // Engine says eligible, delete fails → reclaimedCount=0,
        // AND the rollback seam must be called with (uri, false)
        // so the asset is not permanently marked offloaded.
        val rolledBack = mutableListOf<Pair<String, Boolean>>()
        val result = SafeSpaceManager.reclaimSafeSpace(
            context = stubContext,
            candidateUris = listOf("content://media/external/images/4"),
            statusChecker = { _ -> true to 500_000L },
            engineReclaim = { true },
            deleteLocal = { _, _ -> false }, // delete fails
            setOffloaded = { uri, flag ->
                rolledBack.add(uri to flag)
                true
            },
        )

        // B.2.7 invariant: when delete fails after the engine sets
        // is_offloaded=1, the shell MUST call setMediaOffloaded(uri, false)
        // to prevent the asset from being permanently unreachable.
        // If this assertion fails, the rollback path was removed
        // and the asset is permanently marked offloaded (PR #54 leak).
        assertEquals(1, rolledBack.size)
        assertEquals("content://media/external/images/4" to false, rolledBack[0])

        // The result still shows 0 reclaimed (delete failed).
        assertEquals(1, result.totalChecked)
        assertEquals(1, result.eligibleCount)
        assertEquals(0, result.reclaimedCount)
        assertEquals(0L, result.freedBytesEstimate)
    }

    @Test
    fun testReclaimMixedBatch() {
        // 4 items: 2 verified, 1 ineligible, 1 delete-fails.
        val deleted = mutableListOf<String>()
        val result = SafeSpaceManager.reclaimSafeSpace(
            context = stubContext,
            candidateUris = listOf("u1", "u2", "u3", "u4"),
            statusChecker = { uri ->
                when (uri) {
                    "u1" -> true to 1_000_000L
                    "u2" -> true to 2_000_000L
                    "u3" -> true to 3_000_000L
                    "u4" -> false to 0L
                    else -> false to 0L
                }
            },
            engineReclaim = { uri -> uri != "u3" }, // u3 ineligible
            deleteLocal = { _, uri ->
                deleted.add(uri)
                uri != "u2" // u2 delete fails
            },
        )

        assertEquals(4, result.totalChecked)
        assertEquals(3, result.eligibleCount) // u1, u2, u3 verified
        assertEquals(1, result.reclaimedCount) // u1 succeeded
        assertEquals(1_000_000L, result.freedBytesEstimate)
        // u3 is ineligible (engineReclaim returns false), so deleteLocal
        // is not called for it. u1 and u2 are attempted; u2's delete
        // fails but the attempt still records the URI.
        assertEquals(setOf("u1", "u2"), deleted.toSet())
    }
}
