package com.branchdam.mobile

import com.branchdam.mobile.triage.TrashedMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashSyncObserverTest {

    @Test
    fun testTrashedMediaItemModel() {
        val item = TrashedMediaItem(
            id = 501L,
            contentUri = "content://media/external/images/media/501",
            displayName = "PXL_20260829_TRASH.jpg",
            isTrashed = true
        )

        assertTrue(item.isTrashed)
        assertEquals(501L, item.id)
        assertEquals("PXL_20260829_TRASH.jpg", item.displayName)
    }

    @Test
    fun testOffloadSuppressionLogic() {
        val offloadedUri = "content://media/external/images/media/offloaded_1"
        EngineHolder.setMediaOffloaded(offloadedUri, true)

        // EngineHolder (without a real engine) returns false by default
        // for unknown IDs — the mock fallback path.
        assertFalse(EngineHolder.isMediaOffloaded("content://media/external/images/media/unknown"))
    }
}
