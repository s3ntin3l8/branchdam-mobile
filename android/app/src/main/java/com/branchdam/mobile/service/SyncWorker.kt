package com.branchdam.mobile.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.branchdam.mobile.BranchDamKeys
import com.branchdam.mobile.CoreErrorCodes
import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.SyncBatchOutcome
import com.branchdam.mobile.observer.UploadStaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            try {
                setForeground(getForegroundInfo())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "foreground promotion denied, falling back to background: $e")
            }

            val prefs = applicationContext.getSharedPreferences(BranchDamKeys.PREFS_NAME, Context.MODE_PRIVATE)
            val batchSize = prefs.getInt(BranchDamKeys.UPLOAD_BATCH_SIZE, BranchDamKeys.DEFAULT_UPLOAD_BATCH_SIZE)
            val timeoutSecs = prefs.getInt(BranchDamKeys.SYNC_TIMEOUT_SECS, BranchDamKeys.DEFAULT_SYNC_TIMEOUT_SECS)

            SyncLogger.log(applicationContext, "Starting syncBatch (batch=$batchSize, timeout=${timeoutSecs}s)")
            // When WorkManager stops this worker (constraints lost, the
            // work replaced, quota), the coroutine is cancelled:
            // runCancellingEngine then sets the core's cancel flag, which
            // aborts the upload streaming in Go instead of letting it run
            // on unwanted (e.g. metered) network.
            val outcome = runCancellingEngine(onCancel = EngineHolder::setCancelFlag) {
                EngineHolder.syncBatchDetailed(timeoutSecs = timeoutSecs, batchSize = batchSize)
            }
            cleanupStagedUploads()
            val decision = decide(outcome, runAttemptCount, MAX_ATTEMPTS)
            recordOutcome(prefs, decision, System.currentTimeMillis())

            when (decision) {
                Decision.SUCCESS -> {
                    SyncLogger.log(applicationContext, "syncBatch succeeded")
                    Log.i(TAG, "syncBatch complete (batch=$batchSize, timeout=${timeoutSecs}s)")
                    SyncNotificationHelper.cancelRepairRequired(applicationContext)
                    Result.success()
                }
                Decision.AUTH_FAILED -> {
                    // Retrying with the same credentials cannot help: tell
                    // the user to re-pair instead of reporting a network
                    // error.
                    SyncLogger.log(applicationContext, "syncBatch rejected: server refused this device's credentials")
                    Log.w(TAG, "syncBatch AUTH_FAILED; device must be re-paired")
                    SyncNotificationHelper.showRepairRequired(applicationContext)
                    Result.failure()
                }
                Decision.RETRY -> {
                    SyncLogger.log(applicationContext, "syncBatch failed: ${failureText(outcome)}")
                    Log.w(TAG, "syncBatch failed (batch=$batchSize, timeout=${timeoutSecs}s)")
                    Result.retry()
                }
                Decision.FAILURE -> {
                    SyncLogger.log(applicationContext, "syncBatch failed: ${failureText(outcome)}")
                    Log.w(TAG, "syncBatch failed (batch=$batchSize, timeout=${timeoutSecs}s)")
                    Result.failure()
                }
            }
        } catch (e: CancellationException) {
            // Stopped by WorkManager: the result is ignored, and the
            // cancellation must propagate.
            throw e
        } catch (e: Exception) {
            SyncLogger.log(applicationContext, "SyncWorker exception: ${e.message}", e)
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    /**
     * Deletes the staged original copies (see [UploadStaging]) whose
     * upload the batch just COMPLETED. Runs after every batch, successful
     * or not, and never fails the work.
     */
    private fun cleanupStagedUploads() {
        try {
            val removed = UploadStaging.cleanup(applicationContext)
            if (removed > 0) Log.i(TAG, "removed $removed staged upload copies")
        } catch (e: Exception) {
            Log.w(TAG, "staged upload cleanup failed", e)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = SyncNotificationHelper.buildNotification(applicationContext)
        return ForegroundInfo(
            SyncNotificationHelper.NOTIFICATION_ID,
            notification,
            SyncNotificationHelper.foregroundServiceType()
        )
    }

    /** What a finished sync batch means for the worker. */
    enum class Decision { SUCCESS, AUTH_FAILED, RETRY, FAILURE }

    companion object {
        private const val TAG = "SyncWorker"
        const val MAX_ATTEMPTS = 3

        /**
         * Maps a batch outcome to the worker's result. AUTH_FAILED is
         * terminal (the credentials won't fix themselves). A cancelled
         * batch (CONTEXT_CANCELED: the run was stopped, or the cancel
         * flag outlived the run that set it) didn't fail on its own, so it
         * gets one attempt beyond the transient-failure budget; other
         * failures retry until [maxAttempts].
         */
        internal fun decide(outcome: SyncBatchOutcome, runAttemptCount: Int, maxAttempts: Int): Decision =
            when (outcome) {
                is SyncBatchOutcome.Success -> Decision.SUCCESS
                is SyncBatchOutcome.Failed -> when {
                    outcome.code == CoreErrorCodes.AUTH_FAILED -> Decision.AUTH_FAILED
                    outcome.code == CoreErrorCodes.CONTEXT_CANCELED ->
                        if (runAttemptCount <= maxAttempts) Decision.RETRY else Decision.FAILURE
                    runAttemptCount < maxAttempts -> Decision.RETRY
                    else -> Decision.FAILURE
                }
            }

        /**
         * Persists what the Sync screen shows: the time of the last
         * successful sync (written here, when it happens, rather than by
         * the screen when it sees a replayed SUCCEEDED state) and whether
         * the server is rejecting this device.
         */
        internal fun recordOutcome(prefs: SharedPreferences, decision: Decision, nowMs: Long) {
            when (decision) {
                Decision.SUCCESS -> prefs.edit()
                    .putLong(BranchDamKeys.LAST_SYNC_TIME, nowMs)
                    .remove(BranchDamKeys.SYNC_AUTH_FAILED_AT)
                    .apply()
                Decision.AUTH_FAILED -> prefs.edit()
                    .putLong(BranchDamKeys.SYNC_AUTH_FAILED_AT, nowMs)
                    .apply()
                Decision.RETRY, Decision.FAILURE -> Unit
            }
        }

        /**
         * Runs the blocking engine call [block] so that cancelling the
         * calling coroutine interrupts the wait and calls [onCancel]
         * (which tells the Go engine to abort the batch) before the
         * cancellation propagates.
         */
        internal suspend fun <T> runCancellingEngine(onCancel: () -> Unit, block: () -> T): T =
            try {
                runInterruptible { block() }
            } catch (e: CancellationException) {
                onCancel()
                throw e
            }

        private fun failureText(outcome: SyncBatchOutcome): String = when (outcome) {
            is SyncBatchOutcome.Success -> "none"
            is SyncBatchOutcome.Failed -> listOfNotNull(outcome.code, outcome.message).joinToString(": ")
        }
    }
}
