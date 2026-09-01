package com.branchdam.mobile.service

import android.content.Context
import androidx.work.CoroutineWorker
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
            // Sub-issue B: the gomobile-bound branchdam engine handles
            // the sync. EngineHolder.syncBatch triggers the sync cycle
            // and logs success/failure via the gomobile binding.
            EngineHolder.syncBatch(timeoutSecs = 120, batchSize = 10)
            android.util.Log.i(
                "SyncWorker",
                "syncBatch complete"
            )

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
