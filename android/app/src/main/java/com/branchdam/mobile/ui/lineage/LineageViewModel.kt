package com.branchdam.mobile.ui.lineage

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.branchdam.mobile.lineage.CoreLineageReviewBackend
import com.branchdam.mobile.lineage.EditCorrelator
import com.branchdam.mobile.lineage.InPhoneEdit
import com.branchdam.mobile.lineage.LineagePair
import com.branchdam.mobile.lineage.LineageReview
import com.branchdam.mobile.lineage.LineageReviewBackend
import com.branchdam.mobile.lineage.PairDetector
import com.branchdam.mobile.observer.MediaScanner
import com.branchdam.mobile.ui.AuditCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The lineage review screen: pairs the device found below confidence 1.00
 * (a RAW/JPEG pair matched only by capture time, an in-phone edit matched
 * by filename). They are never sent on their own; the user confirms or
 * rejects each one here. Both decisions are stored in the core database
 * through [LineageReviewBackend], so a decided pair is not offered again,
 * even after a restart. A confirmed edge keeps the resolver's confidence.
 */
class LineageViewModel @JvmOverloads constructor(
    application: Application,
    private val review: LineageReviewBackend = CoreLineageReviewBackend,
) : AndroidViewModel(application) {

    private val _candidates = MutableStateFlow<List<AuditCandidate>>(emptyList())
    val candidates: StateFlow<List<AuditCandidate>> = _candidates.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    /** The last confirm or reject that could not be saved, for the screen to show. */
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    init {
        loadCandidates()
    }

    fun loadCandidates() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Phase 1: Fast initial query (top 100 recent items) for <30ms initial render
                val initialCandidates = withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    val images = MediaScanner.queryRecentImages(context, limit = 100)
                    val videos = MediaScanner.queryRecentVideos(context, limit = 30)
                    val fastAll = images + videos
                    processCandidates(fastAll)
                }
                _candidates.value = initialCandidates
                _isLoading.value = false
                _loadError.value = null

                // Phase 2: Full background scan
                withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    val fullImages = MediaScanner.queryRecentImages(context, limit = 5000)
                    val fullVideos = MediaScanner.queryRecentVideos(context, limit = 1000)
                    val fullAll = fullImages + fullVideos
                    if (fullAll.size > 130) {
                        _candidates.value = processCandidates(fullAll)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "loadCandidates failed", t)
                if (_candidates.value.isEmpty()) {
                    _loadError.value = t.message ?: "Failed to load media"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun processCandidates(allItems: List<com.branchdam.mobile.observer.MediaItem>): List<AuditCandidate> {
        val pairs = PairDetector.findPairs(allItems)
        val edits = EditCorrelator.findInPhoneEdits(allItems, allItems)
        // Without the stored decisions a rejected pair would be offered
        // again, so a failed read is an error, not an empty set.
        val decided = review.decidedPairKeys()
            ?: throw IllegalStateException("Lineage review is unavailable: the engine is not open")
        return pendingReview(pairs, edits, decided)
    }

    fun confirm(candidate: AuditCandidate) {
        decide(candidate, "confirm") { parent, child ->
            // The edge keeps the resolver's confidence: confirming is not
            // new evidence about the files.
            review.confirm(parent, child, RELATIONSHIP, candidate.resolver, candidate.confidence) != null
        }
    }

    fun reject(candidate: AuditCandidate) {
        decide(candidate, "reject") { parent, child ->
            review.reject(parent, child, RELATIONSHIP, candidate.resolver, candidate.confidence)
        }
    }

    /**
     * Saves the user's decision and only then drops the candidate. When the
     * decision can't be saved the candidate stays, so it is neither lost
     * nor silently offered again on the next launch.
     */
    private fun decide(candidate: AuditCandidate, what: String, save: (String, String) -> Boolean) {
        val (parentUri, childUri) = parseEdgeId(candidate.edgeId)
        if (parentUri == null || childUri == null) {
            Log.w(TAG, "$what: malformed edgeId=${candidate.edgeId}, dropping candidate")
            _candidates.update { list -> list.filter { it.edgeId != candidate.edgeId } }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (save(parentUri, childUri)) {
                _actionError.value = null
                _candidates.update { list -> list.filter { it.edgeId != candidate.edgeId } }
            } else {
                Log.w(TAG, "$what failed for ${candidate.edgeId}")
                _actionError.value = "Could not $what ${candidate.childFilename}. Try again."
            }
        }
    }

    private fun parseEdgeId(edgeId: String): Pair<String?, String?> {
        val parts = edgeId.split("|", limit = 2)
        return if (parts.size == 2) parts[0] to parts[1] else null to null
    }

    companion object {
        private const val TAG = "LineageViewModel"
        private const val RELATIONSHIP = "DERIVED_FROM"

        /**
         * The candidates the user still has to decide: every pair and
         * edit below confidence 1.00 (exact pairs are sent automatically
         * and never need review) that is not already confirmed or
         * rejected ([decided] holds [LineageReview.pairKey] keys).
         */
        @androidx.annotation.VisibleForTesting
        fun pendingReview(
            pairs: List<LineagePair>,
            edits: List<InPhoneEdit>,
            decided: Set<String>,
        ): List<AuditCandidate> {
            val raw = pairs.filter { !LineageReview.isAutomatic(it.confidence) }.map { fromPair(it) } +
                edits.filter { !LineageReview.isAutomatic(it.confidence) }.map { fromEdit(it) }
            return raw
                .filter { it.edgeId !in decided }
                .sortedByDescending { it.confidence }
                .distinctBy { it.edgeId }
        }

        @androidx.annotation.VisibleForTesting
        fun fromPair(pair: LineagePair): AuditCandidate {
            return AuditCandidate(
                edgeId = LineageReview.pairKey(pair.masterRaw.contentUri, pair.derivativeJpeg.contentUri),
                masterFilename = pair.masterRaw.displayName,
                childFilename = pair.derivativeJpeg.displayName,
                confidence = pair.confidence,
                resolver = pair.resolver,
                masterUri = pair.masterRaw.contentUri,
                childUri = pair.derivativeJpeg.contentUri,
                masterMimeType = pair.masterRaw.mimeType,
                childMimeType = pair.derivativeJpeg.mimeType,
            )
        }

        @androidx.annotation.VisibleForTesting
        fun fromEdit(edit: InPhoneEdit): AuditCandidate {
            return AuditCandidate(
                edgeId = LineageReview.pairKey(edit.originalMaster.contentUri, edit.editedDerivative.contentUri),
                masterFilename = edit.originalMaster.displayName,
                childFilename = edit.editedDerivative.displayName,
                confidence = edit.confidence,
                resolver = InPhoneEditResolver.format(edit.editorApp),
                masterUri = edit.originalMaster.contentUri,
                childUri = edit.editedDerivative.contentUri,
                masterMimeType = edit.originalMaster.mimeType,
                childMimeType = edit.editedDerivative.mimeType,
            )
        }
    }
}

/**
 * Shared resolver-string formatter for in-phone edits. Mirrors the
 * resolver that [EditCorrelator.registerEditLineage] enqueues so the
 * UI and the engine write the same string into the lineage event.
 */
object InPhoneEditResolver {
    fun format(editorApp: String): String =
        "in_phone_${editorApp.lowercase(java.util.Locale.ROOT).replace(" ", "_")}"
}
