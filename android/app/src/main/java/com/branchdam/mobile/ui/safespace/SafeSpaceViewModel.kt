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

data class SafeSpaceUiState(
    val reclaimableBytes: Long = 0L,
    val verifiedCount: Int = 0,
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

            val verifiedBytes = allItems
                .filter { EngineHolder.isMediaOffloaded(it.contentUri) }
                .sumOf { it.sizeBytes }
            val verifiedCount = allItems.count { EngineHolder.isMediaOffloaded(it.contentUri) }

            _uiState.value = _uiState.value.copy(
                reclaimableBytes = verifiedBytes,
                verifiedCount = verifiedCount,
            )
        }
    }

    fun reclaim() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isReclaiming = true, reclaimMessage = null)
            val context = getApplication<Application>()
            val images = MediaScanner.queryRecentImages(context)
            val videos = MediaScanner.queryRecentVideos(context)
            val allItems = images + videos
            val verified = allItems.filter { EngineHolder.isMediaOffloaded(it.contentUri) }
            val candidateUris = verified.map { it.contentUri }
            val candidatesByUri = verified.associate { it.contentUri to it.sizeBytes }
            val result = SafeSpaceManager.reclaimSafeSpace(
                context = context,
                candidateUris = candidateUris,
                statusChecker = { uri ->
                    val size = candidatesByUri[uri] ?: 0L
                    true to size
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
