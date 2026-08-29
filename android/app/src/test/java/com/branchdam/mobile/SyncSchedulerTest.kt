package com.branchdam.mobile

import androidx.work.NetworkType
import com.branchdam.mobile.service.SyncScheduler
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncSchedulerTest {

    @Test
    fun testSchedulerConstants() {
        assertEquals("branchdam_periodic_sync", SyncScheduler.PERIODIC_WORK_TAG)
        assertEquals("branchdam_immediate_sync", SyncScheduler.IMMEDIATE_WORK_TAG)
        assertEquals("branchdam_prefs", SyncScheduler.PREFS_NAME)
        assertEquals("sync_on_mobile_data", SyncScheduler.KEY_SYNC_ON_MOBILE_DATA)
    }

    @Test
    fun testNetworkTypeResolution() {
        // Default: syncOnMobileData = false -> UNMETERED (Wi-Fi only)
        assertEquals(NetworkType.UNMETERED, SyncScheduler.resolveImmediateNetworkType(false))

        // Opted in: syncOnMobileData = true -> CONNECTED (Any connection including cellular)
        assertEquals(NetworkType.CONNECTED, SyncScheduler.resolveImmediateNetworkType(true))
    }
}
