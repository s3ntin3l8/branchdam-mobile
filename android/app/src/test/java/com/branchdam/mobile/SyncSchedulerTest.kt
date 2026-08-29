package com.branchdam.mobile

import com.branchdam.mobile.service.SyncScheduler
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncSchedulerTest {

    @Test
    fun testSchedulerConstants() {
        assertEquals("branchdam_periodic_sync", SyncScheduler.PERIODIC_WORK_TAG)
        assertEquals("branchdam_immediate_sync", SyncScheduler.IMMEDIATE_WORK_TAG)
    }
}
