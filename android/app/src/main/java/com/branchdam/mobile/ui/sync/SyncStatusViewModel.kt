package com.branchdam.mobile.ui.sync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.branchdam.mobile.BranchDamKeys
import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.service.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SyncStatusUiState(
    val isConnected: Boolean = false,
    val isSyncing: Boolean = false,
    val lastSyncTime: Long = 0L,
    val workerState: String = "Idle",
)

class SyncStatusViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(BranchDamKeys.PREFS_NAME, android.content.Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(SyncStatusUiState())
    val uiState: StateFlow<SyncStatusUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = SyncStatusUiState(
                isConnected = EngineHolder.isInitialized(),
                lastSyncTime = prefs.getLong(KEY_LAST_SYNC, 0L),
                workerState = if (EngineHolder.isInitialized()) "Idle" else "Engine not initialized",
            )
        }
    }

    fun triggerSync() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isSyncing = true, workerState = "Syncing...")
            SyncScheduler.triggerImmediateSync(getApplication())
            _uiState.value = _uiState.value.copy(
                isSyncing = false,
                workerState = "Idle",
                lastSyncTime = System.currentTimeMillis(),
            )
            prefs.edit().putLong(KEY_LAST_SYNC, _uiState.value.lastSyncTime).apply()
        }
    }

    companion object {
        private const val KEY_LAST_SYNC = "branchdam_last_sync_time"
    }
}
