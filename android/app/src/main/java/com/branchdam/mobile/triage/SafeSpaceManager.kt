package com.branchdam.mobile.triage

import android.content.Context
import android.net.Uri
import com.branchdam.mobile.EngineHolder

data class SafeSpaceResult(
    val totalChecked: Int,
    val eligibleCount: Int,
    val reclaimedCount: Int,
    val freedBytesEstimate: Long
)

object SafeSpaceManager {

    /**
     * Executes safe space reclaim for verified media items:
     * 1. Confirms node is archived and verified on Tier 3 NAS storage.
     * 2. Sets is_offloaded = 1 in SQLite queue.db to suppress deletion sync.
     * 3. Deletes local full-res file via MediaStore.
     *
     * @param engineReclaim optional test seam: returns true if the engine
     *   confirms the asset is safely reclaimable. Defaults to
     *   [EngineHolder.reclaimSafeSpace].
     * @param deleteLocal optional test seam: deletes the local MediaStore
     *   row for the given URI. Defaults to `contentResolver.delete`.
     * @param setOffloaded optional test seam: sets the engine's offloaded
     *   flag. Defaults to [EngineHolder.setMediaOffloaded]. Called on
     *   the rollback path when deleteLocal fails, to prevent the asset
     *   from being permanently marked offloaded.
     */
    fun reclaimSafeSpace(
        context: Context,
        candidateUris: List<String>,
        statusChecker: (uri: String) -> Pair<Boolean, Long>,
        engineReclaim: (uri: String) -> Boolean = { EngineHolder.reclaimSafeSpace(it) },
        deleteLocal: (context: Context, uri: String) -> Boolean = { ctx, u ->
            try {
                ctx.contentResolver.delete(Uri.parse(u), null, null) > 0
            } catch (_: Exception) {
                false
            }
        },
        setOffloaded: (uri: String, isOffloaded: Boolean) -> Boolean = { uri, flag ->
            EngineHolder.setMediaOffloaded(uri, flag)
        },
    ): SafeSpaceResult {
        var eligibleCount = 0
        var reclaimedCount = 0
        var freedBytes = 0L

        for (uriString in candidateUris) {
            val (isVerified, sizeBytes) = statusChecker(uriString)
            if (!isVerified) {
                continue
            }

            eligibleCount++

            // 1. Engine-owned atomic reclaim (B.2.7): the engine re-queries
            // the server for current verified + tier state, sets the
            // local flag, and only returns Eligible=true on success.
            // The shell deletes the local copy only after the engine
            // confirms the asset is safely archived.
            val eligible = engineReclaim(uriString)
            if (eligible) {
                // 2. Delete the local copy AFTER the engine confirms.
                val deleted = deleteLocal(context, uriString)
                if (deleted) {
                    reclaimedCount++
                    freedBytes += sizeBytes
                } else {
                    // Rollback: if delete fails the file is still on disk
                    // but the engine already set is_offloaded=1. Without
                    // rollback the asset is permanently unreachable — the
                    // engine treats it as intentionally offloaded and
                    // suppresses any future reclaim attempts.
                    setOffloaded(uriString, false)
                }
            }
        }

        return SafeSpaceResult(
            totalChecked = candidateUris.size,
            eligibleCount = eligibleCount,
            reclaimedCount = reclaimedCount,
            freedBytesEstimate = freedBytes
        )
    }
}
