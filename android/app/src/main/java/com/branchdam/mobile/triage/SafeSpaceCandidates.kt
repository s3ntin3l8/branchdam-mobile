package com.branchdam.mobile.triage

import com.branchdam.mobile.observer.MediaItem
import org.json.JSONArray

/**
 * One entry of `BindingCheckSafeSpaceCandidates`'s JSON array
 * (`{"localId": ..., "eligible": ..., "reason": ...}`).
 */
data class SafeSpaceVerdict(
    val localId: String,
    val eligible: Boolean,
    val reason: String? = null,
)

/** What the Safe Space screen shows and reclaims. */
data class SafeSpaceSummary(
    /** Items the engine says are verified on a live Tier-3 master. */
    val eligible: List<MediaItem>,
    val reclaimableBytes: Long,
    /** Backed-up items that were checked against the server. */
    val checkedCount: Int,
    /** Checked items that are not (yet) safe to reclaim. */
    val ineligibleCount: Int,
)

/** Engine statuses for media that has a node UUID on the server. */
private val UPLOADED_STATUSES = setOf("COMPLETED", "OFFLOADED")

/**
 * Local media that is worth asking the server about: the upload
 * COMPLETED, so the engine has a node UUID for it. Items reported as
 * OFFLOADED but still present in MediaStore are included too -- that is
 * a reclaim whose delete never happened (process killed while the system
 * confirmation was showing) and can be finished from here.
 *
 * Statuses are keyed by local ID, which on Android is the MediaStore
 * content URI.
 */
fun reclaimCandidateItems(items: List<MediaItem>, statuses: Map<String, String>): List<MediaItem> =
    items
        .distinctBy { it.contentUri }
        .filter { statuses[it.contentUri] in UPLOADED_STATUSES }

/** Parses `BindingCheckSafeSpaceCandidates`'s JSON. Malformed entries are skipped. */
fun parseSafeSpaceVerdicts(json: String?): List<SafeSpaceVerdict> {
    if (json.isNullOrBlank()) return emptyList()
    val array = JSONArray(json)
    val out = ArrayList<SafeSpaceVerdict>(array.length())
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        val localId = obj.optString("localId", "")
        if (localId.isEmpty()) continue
        val reason = if (obj.has("reason") && !obj.isNull("reason")) obj.optString("reason") else null
        out += SafeSpaceVerdict(
            localId = localId,
            eligible = obj.optBoolean("eligible", false),
            reason = reason?.takeIf { it.isNotEmpty() },
        )
    }
    return out
}

/**
 * Combines the candidates with the engine's verdicts. An item without a
 * verdict is treated as ineligible: only an explicit `eligible: true`
 * makes something reclaimable.
 */
fun summarizeSafeSpace(candidates: List<MediaItem>, verdicts: List<SafeSpaceVerdict>): SafeSpaceSummary {
    val eligibleIds = verdicts.filter { it.eligible }.map { it.localId }.toSet()
    val eligible = candidates.filter { it.contentUri in eligibleIds }
    return SafeSpaceSummary(
        eligible = eligible,
        reclaimableBytes = eligible.sumOf { it.sizeBytes },
        checkedCount = candidates.size,
        ineligibleCount = candidates.size - eligible.size,
    )
}
