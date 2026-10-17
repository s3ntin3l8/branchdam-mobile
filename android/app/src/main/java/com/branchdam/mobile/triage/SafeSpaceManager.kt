package com.branchdam.mobile.triage

import android.content.Context
import com.branchdam.mobile.EngineHolder

data class SafeSpaceResult(
    val totalChecked: Int,
    val eligibleCount: Int,
    val reclaimedCount: Int,
    val freedBytesEstimate: Long
)

/** A local media item the Safe Space screen wants to reclaim. */
data class ReclaimCandidate(val uri: String, val sizeBytes: Long)

/**
 * The result of the engine's reclaim gate: [approved] items already have
 * `is_offloaded = 1` set and are waiting for the MediaStore delete (which
 * may need the user's confirmation). [completeReclaim] must run once the
 * delete has finished, so the flag is rolled back for anything that was
 * not actually deleted.
 */
data class ReclaimPlan(
    val totalChecked: Int,
    val approved: List<ReclaimCandidate>,
)

object SafeSpaceManager {

    /**
     * Phase 1 of a reclaim: run the engine-owned atomic reclaim gate
     * (B.2.7) on each candidate. The engine re-queries the server for the
     * node's current verified + Tier-3 + live state and sets the local
     * offloaded flag only when the node is safe to reclaim. Only the
     * candidates it approves may be deleted locally.
     *
     * @param engineReclaim the gate for one item. In the app it reads the
     *   item's current MediaStore size and modification time right before
     *   calling [EngineHolder.reclaimSafeSpace], so the engine can refuse
     *   an item whose bytes changed since upload (#229); see
     *   [reclaimWithCurrentState]. Tests inject a fake.
     */
    fun planReclaim(
        candidates: List<ReclaimCandidate>,
        engineReclaim: (uri: String) -> Boolean,
    ): ReclaimPlan {
        val approved = candidates
            .distinctBy { it.uri }
            .filter { engineReclaim(it.uri) }
        return ReclaimPlan(totalChecked = candidates.size, approved = approved)
    }

    /**
     * The production [planReclaim] gate: reads [uri]'s current MediaStore
     * state immediately before the engine's reclaim call, which compares
     * it with the baseline recorded at enqueue.
     */
    fun reclaimWithCurrentState(context: Context, uri: String): Boolean =
        EngineHolder.reclaimSafeSpace(uri, queryLocalFileState(context, uri))

    /**
     * Phase 2 of a reclaim, after the MediaStore delete (and the user's
     * confirmation, on API 29+) has finished. Every approved item that
     * was not deleted -- the user cancelled, or the delete failed -- gets
     * its offloaded flag rolled back: the file is still on the device, and
     * a stale flag would make the engine treat it as intentionally
     * offloaded forever (PR #54 leak).
     *
     * @param setOffloaded test seam; defaults to
     *   [EngineHolder.setMediaOffloaded].
     */
    fun completeReclaim(
        plan: ReclaimPlan,
        deletedUris: Set<String>,
        setOffloaded: (uri: String, isOffloaded: Boolean) -> Boolean = { uri, flag ->
            EngineHolder.setMediaOffloaded(uri, flag)
        },
    ): SafeSpaceResult {
        var reclaimedCount = 0
        var freedBytes = 0L
        for (candidate in plan.approved) {
            if (candidate.uri in deletedUris) {
                reclaimedCount++
                freedBytes += candidate.sizeBytes
            } else {
                setOffloaded(candidate.uri, false)
            }
        }
        return SafeSpaceResult(
            totalChecked = plan.totalChecked,
            eligibleCount = plan.approved.size,
            reclaimedCount = reclaimedCount,
            freedBytesEstimate = freedBytes,
        )
    }
}
