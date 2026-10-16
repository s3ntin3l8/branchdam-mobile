package com.branchdam.mobile.lineage

import android.util.Log
import com.branchdam.mobile.EngineHolder
import io.branchdam.core.branchdam.Branchdam
import org.json.JSONArray

/**
 * Who decides a lineage pair.
 *
 * Only exact pairs (confidence 1.00: a DNG RAW and its Ultra HDR JPEG
 * sharing a stem, a Motion Photo and its embedded video) are sent on the
 * device's own say-so (AGENTS.md invariant 4). Anything weaker, such as a
 * RAW/JPEG pair matched only by capture time or an in-phone edit matched by
 * filename, is a guess: it waits in the lineage review screen until the
 * user confirms or rejects it. The core enforces the same rule
 * (`BindingEnqueueLineageEvent` answers REVIEW_REQUIRED below 1.00), and
 * keeps the user's decisions in its database so a rejected pair stays
 * rejected after a restart.
 */
object LineageReview {
    /** The confidence at which a pair is sent without asking the user. */
    const val AUTO_CONFIDENCE = 1.00

    fun isAutomatic(confidence: Double): Boolean = confidence >= AUTO_CONFIDENCE

    /** Key of a proposed pair, parent first. Matches the review screen's edge IDs. */
    fun pairKey(parentLocalId: String, childLocalId: String): String = "$parentLocalId|$childLocalId"

    /**
     * Parses `BindingListLineageReviews`'s JSON array into the keys of every
     * pair the user already decided (confirmed or rejected).
     */
    fun parseDecidedKeys(json: String): Set<String> {
        if (json.isBlank()) return emptySet()
        val arr = JSONArray(json)
        val out = HashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val parent = o.optString("parentLocalId")
            val child = o.optString("childLocalId")
            if (parent.isNotEmpty() && child.isNotEmpty()) out.add(pairKey(parent, child))
        }
        return out
    }
}

/**
 * The engine calls the review screen needs. An interface so the view model
 * logic can be tested without the native library.
 */
interface LineageReviewBackend {
    /** Queues the confirmed edge at [confidence] (never raised). Returns the event UUID, or null on failure. */
    fun confirm(parentLocalId: String, childLocalId: String, relationshipType: String, resolver: String, confidence: Double): String?

    /** Persists the rejection. Returns false on failure. */
    fun reject(parentLocalId: String, childLocalId: String, relationshipType: String, resolver: String, confidence: Double): Boolean

    /** Keys ([LineageReview.pairKey]) of every decided pair, or null when they can't be read. */
    fun decidedPairKeys(): Set<String>?
}

/**
 * [LineageReviewBackend] over the gomobile binding. Calls block on the Go
 * engine, so call it from a background dispatcher only. When the engine is
 * not open every call fails: nothing is confirmed, rejected or listed
 * behind the user's back, and the review screen reports the failure.
 */
object CoreLineageReviewBackend : LineageReviewBackend {
    private const val TAG = "LineageReview"

    override fun confirm(
        parentLocalId: String,
        childLocalId: String,
        relationshipType: String,
        resolver: String,
        confidence: Double,
    ): String? {
        if (!EngineHolder.isInitialized()) return null
        return try {
            Branchdam.bindingConfirmLineageEdge(parentLocalId, childLocalId, relationshipType, resolver, confidence)
                .takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Log.w(TAG, "confirm failed: $t")
            null
        }
    }

    override fun reject(
        parentLocalId: String,
        childLocalId: String,
        relationshipType: String,
        resolver: String,
        confidence: Double,
    ): Boolean {
        if (!EngineHolder.isInitialized()) return false
        return try {
            Branchdam.bindingRejectLineageEdge(parentLocalId, childLocalId, relationshipType, resolver, confidence)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "reject failed: $t")
            false
        }
    }

    override fun decidedPairKeys(): Set<String>? {
        if (!EngineHolder.isInitialized()) return null
        return try {
            LineageReview.parseDecidedKeys(Branchdam.bindingListLineageReviews())
        } catch (t: Throwable) {
            Log.w(TAG, "list decisions failed: $t")
            null
        }
    }
}
