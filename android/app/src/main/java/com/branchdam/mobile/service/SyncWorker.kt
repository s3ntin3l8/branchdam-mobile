package com.branchdam.mobile.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.branchdam.mobile.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Invokes Go core engine: SyncBatch(timeoutSecs = 120, batchSize = 10)
            val (uploaded, eventsSent) = NativeBridge.syncBatch(timeoutSecs = 120, batchSize = 10)

            // If tasks were transferred, return success; if more items remain, trigger next batch
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
