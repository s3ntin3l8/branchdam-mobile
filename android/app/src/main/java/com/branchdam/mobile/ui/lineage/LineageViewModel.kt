package com.branchdam.mobile.ui.lineage

import android.app.Application
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
import kotlinx.coroutines.launch

class LineageViewModel(application: Application) : AndroidViewModel(application) {

    private val _candidates = MutableStateFlow<List<AuditCandidate>>(emptyList())
    val candidates: StateFlow<List<AuditCandidate>> = _candidates.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadCandidates()
    }

    fun loadCandidates() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            val context = getApplication<Application>()
            val images = MediaScanner.queryRecentImages(context)
            val videos = MediaScanner.queryRecentVideos(context)
            val allItems = images + videos

            val pairs = PairDetector.findPairs(allItems)
            val edits = EditCorrelator.findInPhoneEdits(allItems, allItems)

            val newCandidates = pairs.map { fromPair(it) } + edits.map { fromEdit(it) }
            _candidates.value = newCandidates
            _isLoading.value = false
        }
    }

    fun confirm(candidate: AuditCandidate) {
        val (parentUri, childUri) = parseEdgeId(candidate.edgeId)
        if (parentUri != null && childUri != null) {
            EngineHolder.enqueueLineageEvent(
                parentLocalID = parentUri,
                childLocalID = childUri,
                relationshipType = "DERIVED_FROM",
                resolver = candidate.resolver,
                confidence = candidate.confidence,
            )
        }
        _candidates.value = _candidates.value.filter { it.edgeId != candidate.edgeId }
    }

    fun reject(candidate: AuditCandidate) {
        _candidates.value = _candidates.value.filter { it.edgeId != candidate.edgeId }
    }

    private fun parseEdgeId(edgeId: String): Pair<String?, String?> {
        val parts = edgeId.split("|", limit = 2)
        return if (parts.size == 2) parts[0] to parts[1] else null to null
    }

    companion object {
        fun fromPair(pair: LineagePair): AuditCandidate {
            return AuditCandidate(
                edgeId = "${pair.masterRaw.contentUri}|${pair.derivativeJpeg.contentUri}",
                masterFilename = pair.masterRaw.displayName,
                childFilename = pair.derivativeJpeg.displayName,
                confidence = pair.confidence,
                resolver = pair.resolver,
            )
        }

        fun fromEdit(edit: InPhoneEdit): AuditCandidate {
            return AuditCandidate(
                edgeId = "${edit.originalMaster.contentUri}|${edit.editedDerivative.contentUri}",
                masterFilename = edit.originalMaster.displayName,
                childFilename = edit.editedDerivative.displayName,
                confidence = edit.confidence,
                resolver = "in_phone_${edit.editorApp.lowercase().replace(" ", "_")}",
            )
        }
    }
}
