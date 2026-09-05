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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class SyncStatusUiState(
    val isConnected: Boolean = false,
    val isServerReachable: Boolean = false,
    val isSyncing: Boolean = false,
    val lastSyncTime: Long = 0L,
    val workerState: String = "Idle",
)

/**
 * Test seam for the reachability check. Production defaults to a call
 * into [EngineHolder.testConnection] (which dispatches through the
 * gomobile binding and can block on an HTTP round-trip). Tests pass a
 * pure lambda to drive success / failure / hang paths without loading
 * the AAR.
 *
 * The blocking call is the original concern from the PR #131 review:
 * `EngineHolder.testConnection` runs on the single-threaded executor
 * shared by every other `EngineHolder.*` binding, so a slow handshake
 * holds up `syncBatch` for the duration of the TCP timeout. The
 * `withTimeoutOrNull` wrapper in [SyncStatusViewModel.checkConnection]
 * bounds the wait to [reachabilityTimeoutMs] and treats a timeout
 * the same as a failure.
 *
 * Marked `suspend` so the test seam can use `delay` (a suspending
 * function) in the synthetic-hang test; production callers don't
 * actually suspend — `EngineHolder.testConnection` returns when
 * the gomobile binding returns.
 */
typealias TestConnectionFn = suspend () -> Boolean

class SyncStatusViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(BranchDamKeys.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    private val workManager = WorkManager.getInstance(application)

    private val _uiState = MutableStateFlow(SyncStatusUiState())
    val uiState: StateFlow<SyncStatusUiState> = _uiState.asStateFlow()

    init {
        observeWorker()
        checkConnection()
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

    fun checkConnection() {
        viewModelScope.launch {
            // Bound the handshake so a misconfigured server's TCP
            // timeout can't lock the UI or the single-threaded
            // EngineHolder executor that backs the gomobile binding.
            // Treat a timeout the same as a failed handshake — the UI
            // shows "Server unreachable" and the Sync Now button
            // stays disabled; the user can hit Refresh to retry.
            val isReachable = withContext(ioDispatcher) {
                withTimeoutOrNull(reachabilityTimeoutMs) {
                    testConnectionFn()
                } ?: false
            }
            _uiState.value = _uiState.value.copy(isServerReachable = isReachable)
        }
    }

    fun refresh() {
        checkConnection()
        _uiState.value = _uiState.value.copy(
            isConnected = EngineHolder.isInitialized(),
            lastSyncTime = prefs.getLong(BranchDamKeys.LAST_SYNC_TIME, 0L),
        )
    }

    fun triggerSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
        workManager.enqueueUniqueWork(SyncScheduler.IMMEDIATE_WORK_TAG, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        /**
         * Default upper bound on the time `checkConnection` will wait
         * for the server handshake before treating it as unreachable.
         * The default HTTP client in `core/client` does not expose a
         * timeout knob (T2-6), so 5s is the in-process guard against
         * the OS TCP default (~75s) hanging the Sync screen.
         *
         * Exposed as a `var` (not `const`) so the timeout test seam
         * can lower it to a sub-second value without spinning a
         * 5s wall-clock wait. Production reads this as the default
         * at every call site so a test override is automatically
         * picked up by the next `checkConnection()` invocation.
         */
        @androidx.annotation.VisibleForTesting
        var reachabilityTimeoutMs: Long = 5_000L

        /**
         * Test seam: defaults to the production [EngineHolder.testConnection]
         * call. Tests pass a lambda to drive success / failure / hang
         * paths without instantiating a real gomobile engine.
         */
        @androidx.annotation.VisibleForTesting
        var testConnectionFn: TestConnectionFn = { EngineHolder.testConnection() }

        /**
         * Test seam: the dispatcher used for the blocking handshake.
         * Defaults to [Dispatchers.IO]; tests substitute the test
         * scheduler so `withTimeoutOrNull` advances on the virtual
         * clock rather than wall-clock time.
         */
        @androidx.annotation.VisibleForTesting
        var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
    }
}
