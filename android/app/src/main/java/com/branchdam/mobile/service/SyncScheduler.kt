package com.branchdam.mobile.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {

    const val PERIODIC_WORK_TAG = "branchdam_periodic_sync"
    const val IMMEDIATE_WORK_TAG = "branchdam_immediate_sync"

    fun schedulePeriodicSync(context: Context, requireCharging: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi preferred
            .setRequiresBatteryNotLow(true)
            .apply {
                if (requireCharging) {
                    setRequiresCharging(true)
                }
            }
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(PERIODIC_WORK_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }

    fun triggerImmediateSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val immediateRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .addTag(IMMEDIATE_WORK_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_TAG,
            ExistingWorkPolicy.REPLACE,
            immediateRequest
        )
    }
}
