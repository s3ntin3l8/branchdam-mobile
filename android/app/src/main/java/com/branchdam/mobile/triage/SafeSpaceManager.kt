package com.branchdam.mobile.triage

import android.content.Context
import android.net.Uri
import com.branchdam.mobile.NativeBridge

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
     */
    fun reclaimSafeSpace(
        context: Context,
        candidateUris: List<String>,
        statusChecker: (uri: String) -> Pair<Boolean, Long> // returns Pair(isVerified, sizeBytes)
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

            // 1. Perform deletion of local copy
            val deleted = deleteLocalMedia(context, Uri.parse(uriString))
            if (deleted) {
                // 2. Mark offloaded in local queue database after successful deletion
                NativeBridge.setMediaOffloaded(uriString, true)
                reclaimedCount++
                freedBytes += sizeBytes
            }
        }

        return SafeSpaceResult(
            totalChecked = candidateUris.size,
            eligibleCount = eligibleCount,
            reclaimedCount = reclaimedCount,
            freedBytesEstimate = freedBytes
        )
    }

    private fun deleteLocalMedia(context: Context, uri: Uri): Boolean {
        return try {
            val rows = context.contentResolver.delete(uri, null, null)
            rows > 0
        } catch (_: Exception) {
            // SecurityException or mock fallback
            false
        }
    }
}
