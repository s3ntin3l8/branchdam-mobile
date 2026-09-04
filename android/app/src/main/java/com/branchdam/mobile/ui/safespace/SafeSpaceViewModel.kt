package com.branchdam.mobile.ui.safespace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.observer.MediaScanner
import com.branchdam.mobile.triage.SafeSpaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SafeSpaceCandidate(
    val contentUri: String,
    val displayName: String,
    val sizeBytes: Long,
    val isVerified: Boolean,
)

data class SafeSpaceUiState(
    val reclaimableBytes: Long = 0L,
    val verifiedCount: Int = 0,
    val candidates: List<SafeSpaceCandidate> = emptyList(),
    val isReclaiming: Boolean = false,
    val reclaimMessage: String? = null,
)

class SafeSpaceViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SafeSpaceUiState())
    val uiState: StateFlow<SafeSpaceUiState> = _uiState.asStateFlow()

    init {
        loadCandidates()
    }

    fun loadCandidates() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val images = MediaScanner.queryRecentImages(context)
            val videos = MediaScanner.queryRecentVideos(context)
            val allItems = images + videos

            val candidates = allItems.map { item ->
                val isOffloaded = EngineHolder.isMediaOffloaded(item.contentUri)
                SafeSpaceCandidate(
                    contentUri = item.contentUri,
                    displayName = item.displayName,
                    sizeBytes = item.sizeBytes,
                    isVerified = isOffloaded,
                )
            }

            val verified = candidates.filter { it.isVerified }
            _uiState.value = _uiState.value.copy(
                reclaimableBytes = verified.sumOf { it.sizeBytes },
                verifiedCount = verified.size,
                candidates = verified,
            )
        }
    }

    fun reclaim() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isReclaiming = true, reclaimMessage = null)
            val context = getApplication<Application>()
            val candidateUris = _uiState.value.candidates.map { it.contentUri }
            val candidatesByUri = _uiState.value.candidates.associateBy { it.contentUri }
            val result = SafeSpaceManager.reclaimSafeSpace(
                context = context,
                candidateUris = candidateUris,
                statusChecker = { uri ->
                    val item = candidatesByUri[uri]
                    (item?.isVerified ?: false) to (item?.sizeBytes ?: 0L)
                },
            )
            val freedMb = result.freedBytesEstimate / (1024L * 1024L)
            _uiState.value = _uiState.value.copy(
                isReclaiming = false,
                reclaimMessage = "Reclaimed ${result.reclaimedCount} of ${result.eligibleCount} items, freed ~$freedMb MB",
            )
            loadCandidates()
        }
    }
}
