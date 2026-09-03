package com.branchdam.mobile.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
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

            // Sub-issue B: the gomobile-bound branchdam engine handles
            // the sync. EngineHolder.syncBatch triggers the sync cycle
            // and logs success/failure via the gomobile binding.
            EngineHolder.syncBatch(timeoutSecs = 120, batchSize = 10)
            Log.i(TAG, "syncBatch complete")

            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
        // No notification cancellation here: WorkManager owns the
        // foreground-service notification and calls stopForeground() when
        // doWork returns. Cancelling here would race stopForeground and
        // could leave the FGS momentarily running without its required
        // visible notification (FGS contract violation on newer Android).
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
    }
}
