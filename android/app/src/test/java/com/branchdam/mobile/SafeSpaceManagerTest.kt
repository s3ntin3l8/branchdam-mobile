package com.branchdam.mobile

import com.branchdam.mobile.triage.SafeSpaceResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SafeSpaceManagerTest {

    @Test
    fun testSafeSpaceResultModel() {
        val result = SafeSpaceResult(
            totalChecked = 10,
            eligibleCount = 8,
            reclaimedCount = 8,
            freedBytesEstimate = 120_000_000L
        )

        assertEquals(10, result.totalChecked)
        assertEquals(8, result.eligibleCount)
        assertEquals(8, result.reclaimedCount)
        assertEquals(120_000_000L, result.freedBytesEstimate)
    }
}
