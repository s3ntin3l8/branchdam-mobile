package com.branchdam.mobile.ui.lineage

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.lineage.EditCorrelator
import com.branchdam.mobile.lineage.InPhoneEdit
import com.branchdam.mobile.lineage.LineagePair
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

class LineageViewModel(application: Application) : AndroidViewModel(application) {

    private val _candidates = MutableStateFlow<List<AuditCandidate>>(emptyList())
    val candidates: StateFlow<List<AuditCandidate>> = _candidates.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

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

        // Filter out 1.00 confidence exact pairs as they are automatically registered
        // by the background scanner and do not need manual audit confirmation.
        val auditPairs = pairs.filter { it.confidence < 1.00 }
        val raw = auditPairs.map { fromPair(it) } + edits.map { fromEdit(it) }
        return dedupeByEdgeId(raw)
    }

    fun confirm(candidate: AuditCandidate) {
        val (parentUri, childUri) = parseEdgeId(candidate.edgeId)
        if (parentUri == null || childUri == null) {
            Log.w(TAG, "confirm: malformed edgeId=${candidate.edgeId}, dropping candidate")
            _candidates.update { list -> list.filter { it.edgeId != candidate.edgeId } }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = EngineHolder.enqueueLineageEvent(
                parentLocalID = parentUri,
                childLocalID = childUri,
                relationshipType = "DERIVED_FROM",
                resolver = candidate.resolver,
                confidence = candidate.confidence,
            )
            if (result.isBlank()) {
                Log.w(TAG, "confirm: enqueueLineageEvent returned empty edgeId for ${candidate.edgeId}")
            }
            _candidates.update { list -> list.filter { it.edgeId != candidate.edgeId } }
        }
    }

    fun reject(candidate: AuditCandidate) {
        _candidates.update { list -> list.filter { it.edgeId != candidate.edgeId } }
    }

    private fun dedupeByEdgeId(candidates: List<AuditCandidate>): List<AuditCandidate> {
        return candidates
            .sortedByDescending { it.confidence }
            .distinctBy { it.edgeId }
    }

    private fun parseEdgeId(edgeId: String): Pair<String?, String?> {
        val parts = edgeId.split("|", limit = 2)
        return if (parts.size == 2) parts[0] to parts[1] else null to null
    }

    companion object {
        private const val TAG = "LineageViewModel"

        @androidx.annotation.VisibleForTesting
        fun fromPair(pair: LineagePair): AuditCandidate {
            return AuditCandidate(
                edgeId = "${pair.masterRaw.contentUri}|${pair.derivativeJpeg.contentUri}",
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
                edgeId = "${edit.originalMaster.contentUri}|${edit.editedDerivative.contentUri}",
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
