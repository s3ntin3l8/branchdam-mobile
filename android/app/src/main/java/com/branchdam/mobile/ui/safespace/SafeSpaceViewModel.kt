package com.branchdam.mobile.ui.safespace

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.observer.MediaItem
import com.branchdam.mobile.observer.MediaScanner
import com.branchdam.mobile.triage.MediaDeleteCoordinator
import com.branchdam.mobile.triage.MediaStoreDeleter
import com.branchdam.mobile.triage.PendingDeleteConsent
import com.branchdam.mobile.triage.ReclaimCandidate
import com.branchdam.mobile.triage.SafeSpaceManager
import com.branchdam.mobile.triage.reclaimCandidateItems
import com.branchdam.mobile.triage.summarizeSafeSpace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SafeSpaceUiState(
    val reclaimableBytes: Long = 0L,
    /** Items verified on a live Tier-3 master, ready to reclaim. */
    val verifiedCount: Int = 0,
    /** Backed-up items checked against the server. */
    val checkedCount: Int = 0,
    /** Backed-up items the server does not (yet) consider safe to reclaim. */
    val notYetVerifiedCount: Int = 0,
    val isLoading: Boolean = false,
    val isReclaiming: Boolean = false,
    val reclaimMessage: String? = null,
    val loadError: String? = null,
)

class SafeSpaceViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SafeSpaceUiState())
    val uiState: StateFlow<SafeSpaceUiState> = _uiState.asStateFlow()

    /** The eligible items behind [SafeSpaceUiState.verifiedCount]. */
    @Volatile
    private var eligibleItems: List<MediaItem> = emptyList()

    private val deleteCoordinator = MediaDeleteCoordinator(viewModelScope) { ioDispatcher }

    /** System delete confirmation the screen must launch (API 29+). */
    val pendingDeleteConsent: StateFlow<PendingDeleteConsent?> = deleteCoordinator.pendingConsent

    init {
        loadCandidates()
    }

    fun loadCandidates() {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isLoading = true, loadError = null) }
            val context = getApplication<Application>()
            val allItems = MediaScanner.queryRecentImages(context) + MediaScanner.queryRecentVideos(context)
            val candidates = reclaimCandidateItems(allItems, EngineHolder.getAllMediaStatuses())

            val verdicts = EngineHolder.checkSafeSpaceCandidates(candidates.map { it.contentUri })
            if (verdicts == null) {
                eligibleItems = emptyList()
                _uiState.update {
                    it.copy(
                        reclaimableBytes = 0L,
                        verifiedCount = 0,
                        checkedCount = candidates.size,
                        notYetVerifiedCount = 0,
                        isLoading = false,
                        loadError = "Couldn't check archive status with the branchDAM server. " +
                            "Nothing will be removed until it can be verified.",
                    )
                }
                return@launch
            }

            val summary = summarizeSafeSpace(candidates, verdicts)
            eligibleItems = summary.eligible
            _uiState.update {
                it.copy(
                    reclaimableBytes = summary.reclaimableBytes,
                    verifiedCount = summary.eligible.size,
                    checkedCount = summary.checkedCount,
                    notYetVerifiedCount = summary.ineligibleCount,
                    isLoading = false,
                )
            }
        }
    }

    /**
     * Frees up the eligible items: the engine's reclaim gate runs first
     * (re-verifying each node with the server and setting the offloaded
     * flag), and only the items it approves are handed to MediaStore for
     * deletion -- which on API 29+ waits for the user's confirmation via
     * [pendingDeleteConsent]. Anything not deleted is rolled back.
     */
    fun reclaim() {
        if (_uiState.value.isReclaiming || deleteCoordinator.isBusy) return
        val toReclaim = eligibleItems
        if (toReclaim.isEmpty()) return
        _uiState.update { it.copy(isReclaiming = true, reclaimMessage = null) }

        viewModelScope.launch(ioDispatcher) {
            val context = getApplication<Application>()
            val plan = SafeSpaceManager.planReclaim(
                toReclaim.map { ReclaimCandidate(it.contentUri, it.sizeBytes) },
            ) { uri -> SafeSpaceManager.reclaimWithCurrentState(context, uri) }
            if (plan.approved.isEmpty()) {
                finishReclaim(SafeSpaceManager.nothingApprovedMessage(plan.keptReasons))
                return@launch
            }

            val accepted = deleteCoordinator.delete(
                deleterFactory(context),
                plan.approved.map { it.uri },
            ) { done ->
                val result = SafeSpaceManager.completeReclaim(plan, done.deleted)
                val freedMb = result.freedBytesEstimate / (1024L * 1024L)
                val message = if (result.reclaimedCount == 0) {
                    "Nothing was removed from this device."
                } else {
                    "Reclaimed ${result.reclaimedCount} of ${result.eligibleCount} items, freed ~$freedMb MB"
                }
                finishReclaim(message)
            }
            if (!accepted) {
                // Another delete is still waiting on the user; undo the
                // gate's flags rather than leave them dangling.
                SafeSpaceManager.completeReclaim(plan, emptySet())
                finishReclaim("Another delete is still waiting for confirmation.")
            }
        }
    }

    fun onDeleteConsentLaunched(id: Long) = deleteCoordinator.onConsentLaunched(id)

    fun onDeleteConsentResult(approved: Boolean) = deleteCoordinator.onConsentResult(approved)

    private fun finishReclaim(message: String) {
        _uiState.update { it.copy(isReclaiming = false, reclaimMessage = message) }
        loadCandidates()
    }

    companion object {
        @androidx.annotation.VisibleForTesting
        internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

        @androidx.annotation.VisibleForTesting
        internal var deleterFactory: (Context) -> MediaStoreDeleter = { MediaStoreDeleter.forContext(it) }
    }
}
