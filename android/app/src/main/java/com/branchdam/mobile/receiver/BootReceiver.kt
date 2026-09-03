package com.branchdam.mobile.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.branchdam.mobile.service.SyncScheduler

class BootReceiver(
    // Test seam: defaults to the real SyncScheduler call. Tests
    // inject a lambda that records the call so the action-filter
    // logic can be verified (the test would pass identically if
    // the filter were deleted otherwise).
    private val scheduleSync: (Context) -> Unit = SyncScheduler::schedulePeriodicSync,
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            scheduleSync(context)
        }
    }
}
