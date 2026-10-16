package com.branchdam.mobile.ui.sync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.branchdam.mobile.ActiveUploadProgress
import com.branchdam.mobile.BranchDamKeys
import com.branchdam.mobile.CoreErrorCodes
import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.coreErrorCode
import com.branchdam.mobile.service.SyncScheduler
import com.branchdam.mobile.service.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class SyncStatusUiState(
    val isConnected: Boolean = false,
    val isServerReachable: Boolean = false,
    val connectionError: String? = null,
    val isSyncing: Boolean = false,
    val lastSyncTime: Long = 0L,
    val workerState: String = "Idle",
    val pendingUploadsCount: Long = 0L,
    val activeUploadProgress: ActiveUploadProgress? = null,
    /**
     * The last sync was refused because the server rejected this
     * device's credentials (core AUTH_FAILED): the user must re-pair.
     */
    val needsRepair: Boolean = false,
)

/** Shown instead of a generic failure when the device must be re-paired. */
const val REPAIR_REQUIRED_MESSAGE = "Device not authorised — re-pair it in Settings"

typealias TestConnectionFn = suspend () -> Boolean
typealias TestConnectionDetailedFn = suspend () -> String?

class SyncStatusViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(BranchDamKeys.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    private val workManager = WorkManager.getInstance(application)

    private val _uiState = MutableStateFlow(SyncStatusUiState())
    val uiState: StateFlow<SyncStatusUiState> = _uiState.asStateFlow()

    private var pollJob: kotlinx.coroutines.Job? = null

    init {
        observeWorker()
        checkConnection()
        pollActiveUploadProgress()
    }

    fun pollActiveUploadProgress() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                val activeProgress = withContext(ioDispatcher) {
                    EngineHolder.getActiveUploadProgress()
                }
                val pendingCount = if (activeProgress != null || _uiState.value.isSyncing) {
                    withContext(ioDispatcher) { EngineHolder.countPendingUploads() }
                } else {
                    _uiState.value.pendingUploadsCount
                }

                _uiState.update { current ->
                    current.copy(
                        activeUploadProgress = activeProgress,
                        pendingUploadsCount = pendingCount
                    )
                }

                if (!_uiState.value.isSyncing && activeProgress == null) {
                    break
                }
                kotlinx.coroutines.delay(500L)
            }
        }
    }

    private fun observeWorker() {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.IMMEDIATE_WORK_TAG).collect { infos ->
                val latest = infos.maxByOrNull { it.runAttemptCount }
                val engineReady = EngineHolder.isInitialized()
                // SyncWorker records the last sync time and the re-pair
                // flag when a sync actually happens; this collector only
                // reads them. (It used to stamp "now" whenever it saw a
                // SUCCEEDED state, which WorkManager replays every time
                // the screen opens.)
                val snapshot = workerSnapshot(
                    state = latest?.state,
                    runAttemptCount = latest?.runAttemptCount ?: 0,
                    lastSyncTime = prefs.getLong(BranchDamKeys.LAST_SYNC_TIME, 0L),
                    authFailedAt = prefs.getLong(BranchDamKeys.SYNC_AUTH_FAILED_AT, 0L),
                    engineReady = engineReady,
                )
                val isSyncing = snapshot.isSyncing
                val pendingUploads = withContext(ioDispatcher) { EngineHolder.countPendingUploads() }
                _uiState.update { current ->
                    current.copy(
                        isConnected = engineReady,
                        isSyncing = isSyncing,
                        lastSyncTime = snapshot.lastSyncTime,
                        workerState = snapshot.workerState,
                        pendingUploadsCount = pendingUploads,
                        needsRepair = snapshot.needsRepair,
                    )
                }
                if (isSyncing) {
                    pollActiveUploadProgress()
                }
            }
        }
    }

    fun checkConnection() {
        viewModelScope.launch {
            val isReachable = withContext(ioDispatcher) {
                withTimeoutOrNull(reachabilityTimeoutMs) {
                    testConnectionFn()
                } ?: false
            }
            val formattedError = if (!isReachable) {
                val rawError = withContext(ioDispatcher) {
                    withTimeoutOrNull(reachabilityTimeoutMs) {
                        testConnectionDetailedFn()
                    } ?: "Connection timed out"
                } ?: "Server unreachable"

                when {
                    coreErrorCode(rawError) == CoreErrorCodes.AUTH_FAILED -> REPAIR_REQUIRED_MESSAGE
                    rawError.contains("401") -> "Authentication Failed (HTTP 401) — Check API Key in Settings"
                    rawError.contains("403") -> "Access Denied (HTTP 403) — Forbidden by server"
                    rawError.contains("404") -> "Endpoint Not Found (HTTP 404) — Check Server URL"
                    else -> rawError
                }
            } else {
                null
            }

            _uiState.update { current ->
                current.copy(
                    isServerReachable = isReachable,
                    connectionError = formattedError
                )
            }
        }
    }

    fun refresh() {
        checkConnection()
        pollActiveUploadProgress()
        viewModelScope.launch {
            val pendingUploads = withContext(ioDispatcher) { EngineHolder.countPendingUploads() }
            _uiState.update { current ->
                current.copy(
                    isConnected = EngineHolder.isInitialized(),
                    lastSyncTime = prefs.getLong(BranchDamKeys.LAST_SYNC_TIME, 0L),
                    pendingUploadsCount = pendingUploads,
                    needsRepair = prefs.getLong(BranchDamKeys.SYNC_AUTH_FAILED_AT, 0L) > 0L,
                )
            }
        }
    }

    /**
     * "Sync Now": retries FAILED uploads and starts an immediate sync
     * through [SyncScheduler.triggerImmediateSync], so it honours the
     * "sync on mobile data" setting (Wi-Fi only unless the user opted
     * in) like every other immediate sync. It used to enqueue an
     * unconstrained request that uploaded over cellular regardless.
     */
    fun triggerSync() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            withContext(ioDispatcher) { EngineHolder.resetFailedUploads() }
            SyncScheduler.triggerImmediateSync(app)
            pollActiveUploadProgress()
        }
    }

    /** What the worker-status card shows for one WorkInfo emission. */
    internal data class WorkerSnapshot(
        val isSyncing: Boolean,
        val workerState: String,
        val lastSyncTime: Long,
        val needsRepair: Boolean,
    )

    companion object {
        /**
         * Maps the immediate-sync WorkInfo and the persisted sync
         * records to the status card. Pure: the last sync time is
         * whatever SyncWorker persisted, whatever state is replayed.
         */
        internal fun workerSnapshot(
            state: WorkInfo.State?,
            runAttemptCount: Int,
            lastSyncTime: Long,
            authFailedAt: Long,
            engineReady: Boolean,
        ): WorkerSnapshot {
            val isSyncing = state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING
            val needsRepair = authFailedAt > 0L
            val workerState = if (needsRepair && !isSyncing) {
                REPAIR_REQUIRED_MESSAGE
            } else {
                when (state) {
                    null -> if (engineReady) "Idle" else "Engine not initialized"
                    WorkInfo.State.ENQUEUED -> "Enqueued"
                    WorkInfo.State.RUNNING -> "Running"
                    WorkInfo.State.SUCCEEDED -> "Last sync succeeded"
                    WorkInfo.State.FAILED -> "Failed (attempt $runAttemptCount/${SyncWorker.MAX_ATTEMPTS})"
                    WorkInfo.State.BLOCKED -> "Blocked"
                    WorkInfo.State.CANCELLED -> "Cancelled"
                }
            }
            return WorkerSnapshot(isSyncing, workerState, lastSyncTime, needsRepair)
        }

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
        var testConnectionFn: TestConnectionFn = { testConnectionDetailedFn() == null }

        @androidx.annotation.VisibleForTesting
        var testConnectionDetailedFn: TestConnectionDetailedFn = { EngineHolder.testConnectionDetailed() }

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
