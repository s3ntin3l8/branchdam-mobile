package com.branchdam.mobile

import com.branchdam.mobile.triage.ReclaimCandidate
import com.branchdam.mobile.triage.ReclaimGateResult
import com.branchdam.mobile.triage.ReclaimPlan
import com.branchdam.mobile.triage.SafeSpaceManager
import com.branchdam.mobile.triage.SafeSpaceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the two-phase reclaim in [SafeSpaceManager]: the engine's
 * reclaim gate ([SafeSpaceManager.planReclaim]) decides what may be
 * deleted, the MediaStore delete runs (and may wait for the user), and
 * [SafeSpaceManager.completeReclaim] rolls the offloaded flag back for
 * anything not actually deleted. The engine and flag calls are injected
 * seams, so no engine or ContentResolver is needed.
 */
class SafeSpaceManagerTest {

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
    fun testPlanKeepsOnlyWhatTheEngineApproves() {
        val gated = mutableListOf<String>()
        val plan = SafeSpaceManager.planReclaim(
            candidates = listOf(
                ReclaimCandidate("u1", 1_000_000L),
                ReclaimCandidate("u2", 2_000_000L),
                ReclaimCandidate("u3", 3_000_000L),
            ),
            engineReclaim = { uri ->
                gated += uri
                if (uri == "u2") ReclaimGateResult(false, "hash-mismatch") else ReclaimGateResult(true)
            },
        )

        assertEquals("every candidate goes through the gate", listOf("u1", "u2", "u3"), gated)
        assertEquals(3, plan.totalChecked)
        assertEquals(listOf("u1", "u3"), plan.approved.map { it.uri })
        assertEquals("a refused item's reason is kept", listOf("hash-mismatch"), plan.keptReasons)
    }

    @Test
    fun testCompleteCountsDeletedItems() {
        val rolledBack = mutableListOf<Pair<String, Boolean>>()
        val plan = ReclaimPlan(
            totalChecked = 1,
            approved = listOf(ReclaimCandidate("content://media/external/images/1", 1_000_000L)),
        )

        val result = SafeSpaceManager.completeReclaim(
            plan,
            deletedUris = setOf("content://media/external/images/1"),
            setOffloaded = { uri, flag ->
                rolledBack += uri to flag
                true
            },
        )

        assertEquals(SafeSpaceResult(1, 1, 1, 1_000_000L), result)
        assertTrue("a deleted item keeps its offloaded flag", rolledBack.isEmpty())
    }

    @Test
    fun testCancelledConsentRollsBackEveryApprovedItem() {
        // The engine set is_offloaded=1 for both, then the user cancelled
        // the system delete dialog: nothing was deleted, so both flags
        // must be rolled back or the assets stay permanently marked
        // offloaded while still on the device (PR #54 leak).
        val rolledBack = mutableListOf<Pair<String, Boolean>>()
        val plan = ReclaimPlan(
            totalChecked = 2,
            approved = listOf(ReclaimCandidate("u1", 10L), ReclaimCandidate("u2", 20L)),
        )

        val result = SafeSpaceManager.completeReclaim(
            plan,
            deletedUris = emptySet(),
            setOffloaded = { uri, flag ->
                rolledBack += uri to flag
                true
            },
        )

        assertEquals(listOf("u1" to false, "u2" to false), rolledBack)
        assertEquals(0, result.reclaimedCount)
        assertEquals(0L, result.freedBytesEstimate)
        assertEquals(2, result.eligibleCount)
    }

    @Test
    fun testMixedBatch() {
        // u1 deleted, u2 approved but its delete failed, u3 refused by the
        // engine gate (never offered for deletion, never rolled back).
        val rolledBack = mutableListOf<Pair<String, Boolean>>()
        val plan = SafeSpaceManager.planReclaim(
            candidates = listOf(
                ReclaimCandidate("u1", 1_000_000L),
                ReclaimCandidate("u2", 2_000_000L),
                ReclaimCandidate("u3", 3_000_000L),
            ),
            engineReclaim = { uri -> ReclaimGateResult(uri != "u3") },
        )

        val result = SafeSpaceManager.completeReclaim(
            plan,
            deletedUris = setOf("u1"),
            setOffloaded = { uri, flag ->
                rolledBack += uri to flag
                true
            },
        )

        assertEquals(3, result.totalChecked)
        assertEquals(2, result.eligibleCount)
        assertEquals(1, result.reclaimedCount)
        assertEquals(1_000_000L, result.freedBytesEstimate)
        assertEquals(listOf("u2" to false), rolledBack)
    }

    @Test
    fun testNothingApprovedMeansNothingToDelete() {
        val plan = SafeSpaceManager.planReclaim(
            candidates = listOf(ReclaimCandidate("u1", 1L)),
            engineReclaim = { ReclaimGateResult(false) },
        )
        assertTrue(plan.approved.isEmpty())
    }

    // Core error messages as the binding surfaces them (branchdam.go's
    // reclaimErrorToBranchdamError wraps the engine's reason).
    private val serverRefusal = "branchdam: VERIFIED_REQUIRED: reclaim ineligible: no-server-hash"
    private val localChanged =
        "branchdam: VERIFIED_REQUIRED: reclaim ineligible: local-changed: modification time 2 differs from 1 recorded at enqueue"
    private val noBaseline =
        "branchdam: VERIFIED_REQUIRED: reclaim ineligible: no-local-baseline: no size or modification time recorded locally"

    @Test
    fun testNothingApprovedMessageBlamesTheServerOnlyForServerRefusals() {
        val message = SafeSpaceManager.nothingApprovedMessage(listOf(serverRefusal, "engine unavailable"))
        assertTrue(message, message.contains("server could not confirm any item"))
    }

    @Test
    fun testNothingApprovedMessageNamesLocalGates() {
        // #232: items kept by the local gates must not be blamed on the server.
        val message = SafeSpaceManager.nothingApprovedMessage(listOf(localChanged, noBaseline))
        assertFalse(message, message.contains("server"))
        assertTrue(message, message.contains("changed on this device"))
    }

    @Test
    fun testNothingApprovedMessageForMixedReasons() {
        val message = SafeSpaceManager.nothingApprovedMessage(listOf(serverRefusal, localChanged))
        assertTrue(message, message.contains("server could not confirm some items"))
        assertTrue(message, message.contains("changed on this device"))
    }

    @Test
    fun testIsLocalGateReason() {
        assertTrue(SafeSpaceManager.isLocalGateReason(localChanged))
        assertTrue(SafeSpaceManager.isLocalGateReason(noBaseline))
        assertTrue("a bare reason matches", SafeSpaceManager.isLocalGateReason("local-changed: size 1 differs from 2"))
        assertFalse(SafeSpaceManager.isLocalGateReason(serverRefusal))
        assertFalse(SafeSpaceManager.isLocalGateReason("branchdam: INTERNAL: reclaim transient: dial tcp: no-local-baseline"))
    }
}
