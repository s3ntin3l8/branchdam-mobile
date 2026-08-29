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

    const val PREFS_NAME = "branchdam_prefs"
    const val KEY_SYNC_ON_MOBILE_DATA = "sync_on_mobile_data"
    const val PERIODIC_WORK_TAG = "branchdam_periodic_sync"
    const val IMMEDIATE_WORK_TAG = "branchdam_immediate_sync"

    fun getSyncOnMobileData(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SYNC_ON_MOBILE_DATA, false)
    }

    fun setSyncOnMobileData(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SYNC_ON_MOBILE_DATA, enabled).apply()
    }

    fun schedulePeriodicSync(context: Context, requireCharging: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi preferred for periodic background
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

    fun resolveImmediateNetworkType(syncOnMobileData: Boolean): NetworkType {
        return if (syncOnMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED
    }

    fun triggerImmediateSync(context: Context) {
        val syncOnMobile = getSyncOnMobileData(context)
        val networkType = resolveImmediateNetworkType(syncOnMobile)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
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
