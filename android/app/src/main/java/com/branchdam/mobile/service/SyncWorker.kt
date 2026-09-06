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
            val batchSize = prefs.getInt(BranchDamKeys.UPLOAD_BATCH_SIZE, DEFAULT_BATCH_SIZE)
            val timeoutSecs = prefs.getInt(BranchDamKeys.SYNC_TIMEOUT_SECS, DEFAULT_TIMEOUT_SECS)

            EngineHolder.syncBatch(timeoutSecs = timeoutSecs, batchSize = batchSize)
            Log.i(TAG, "syncBatch complete (batch=$batchSize, timeout=${timeoutSecs}s)")

            Result.success()
        } catch (_: Exception) {
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
        private const val DEFAULT_BATCH_SIZE = 10
        private const val DEFAULT_TIMEOUT_SECS = 120
    }
}
