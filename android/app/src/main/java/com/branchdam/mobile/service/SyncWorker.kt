package com.branchdam.mobile.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.branchdam.mobile.BranchDamKeys
import com.branchdam.mobile.EngineHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            try {
                setForeground(getForegroundInfo())
            } catch (e: Throwable) {
                Log.w(TAG, "foreground promotion denied, falling back to background: $e")
            }

            val prefs = applicationContext.getSharedPreferences(BranchDamKeys.PREFS_NAME, Context.MODE_PRIVATE)
            val batchSize = prefs.getInt(BranchDamKeys.UPLOAD_BATCH_SIZE, BranchDamKeys.DEFAULT_UPLOAD_BATCH_SIZE)
            val timeoutSecs = prefs.getInt(BranchDamKeys.SYNC_TIMEOUT_SECS, BranchDamKeys.DEFAULT_SYNC_TIMEOUT_SECS)

            // T2-11: Periodically check for cancellation during the sync.
            // If WorkManager stops this worker (e.g. network lost), we
            // signal the Go engine to abort its current batch.
            SyncLogger.log(applicationContext, "Starting syncBatch (batch=$batchSize, timeout=${timeoutSecs}s)")
            val success = EngineHolder.syncBatch(timeoutSecs = timeoutSecs, batchSize = batchSize)
            if (success) {
                SyncLogger.log(applicationContext, "syncBatch succeeded")
                Log.i(TAG, "syncBatch complete (batch=$batchSize, timeout=${timeoutSecs}s)")
                Result.success()
            } else {
                SyncLogger.log(applicationContext, "syncBatch failed (engine returned false)")
                Log.w(TAG, "syncBatch failed (batch=$batchSize, timeout=${timeoutSecs}s)")
                if (runAttemptCount < MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            SyncLogger.log(applicationContext, "SyncWorker exception: ${e.message}", e)
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
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

    companion object {
        private const val TAG = "SyncWorker"
        const val MAX_ATTEMPTS = 3
    }
}
