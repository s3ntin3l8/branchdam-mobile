package com.branchdam.mobile.ui.sync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.branchdam.mobile.BranchDamKeys
import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.service.SyncScheduler
import com.branchdam.mobile.service.SyncWorker
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
    private val workManager = WorkManager.getInstance(application)

    private val _uiState = MutableStateFlow(SyncStatusUiState())
    val uiState: StateFlow<SyncStatusUiState> = _uiState.asStateFlow()

    init {
        observeWorker()
    }

    private fun observeWorker() {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.IMMEDIATE_WORK_TAG).collect { infos ->
                val latest = infos.maxByOrNull { it.runAttemptCount }
                val state = latest?.state
                val isSyncing = state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING
                val workerState = when (state) {
                    null -> if (EngineHolder.isInitialized()) "Idle" else "Engine not initialized"
                    WorkInfo.State.ENQUEUED -> "Enqueued"
                    WorkInfo.State.RUNNING -> "Running"
                    WorkInfo.State.SUCCEEDED -> "Last sync succeeded"
                    WorkInfo.State.FAILED -> "Failed (attempt ${latest.runAttemptCount}/${SyncWorker.MAX_ATTEMPTS})"
                    WorkInfo.State.BLOCKED -> "Blocked"
                    WorkInfo.State.CANCELLED -> "Cancelled"
                }
                val lastSyncTime = if (state == WorkInfo.State.SUCCEEDED) {
                    System.currentTimeMillis()
                } else {
                    prefs.getLong(BranchDamKeys.LAST_SYNC_TIME, 0L)
                }
                if (state == WorkInfo.State.SUCCEEDED) {
                    prefs.edit().putLong(BranchDamKeys.LAST_SYNC_TIME, lastSyncTime).apply()
                }
                _uiState.value = _uiState.value.copy(
                    isConnected = EngineHolder.isInitialized(),
                    isSyncing = isSyncing,
                    lastSyncTime = lastSyncTime,
                    workerState = workerState,
                )
            }
        }
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(
            isConnected = EngineHolder.isInitialized(),
            lastSyncTime = prefs.getLong(BranchDamKeys.LAST_SYNC_TIME, 0L),
        )
    }

    fun triggerSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
        workManager.enqueueUniqueWork(SyncScheduler.IMMEDIATE_WORK_TAG, ExistingWorkPolicy.REPLACE, request)
    }
}
