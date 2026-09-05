package com.branchdam.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [PermissionFlowState] — the state machine that sequences
 * permission batch requests. The original implementation fired two
 * `requestPermissions()` calls synchronously inside `onCreate`, and on
 * Android 13+ the second call dismissed the first dialog without
 * delivering its result. The state machine replaces that with a
 * NONE → NOTIFICATIONS → MEDIA transition so the second dialog cannot
 * appear before the first dialog has been resolved.
 *
 * The state machine is the contract that eliminates the bug; these
 * tests pin its transitions so a future "optimization" cannot
 * regress back to back-to-back `requestPermissions()` calls.
 */
class PermissionFlowStateTest {

    @Test
    fun testInitialBatchIsNone() {
        val flow = PermissionFlowState()
        assertEquals(PermissionBatch.NONE, flow.batch.value)
    }

    @Test
    fun testNextBatchFromNoneGoesToNotifications() {
        val flow = PermissionFlowState()
        flow.nextBatch()
        assertEquals(PermissionBatch.NOTIFICATIONS, flow.batch.value)
    }

    @Test
    fun testNextBatchFromNotificationsGoesToMedia() {
        val flow = PermissionFlowState()
        flow.nextBatch()
        flow.nextBatch()
        assertEquals(PermissionBatch.MEDIA, flow.batch.value)
    }

    @Test
    fun testNextBatchFromMediaStaysAtMedia() {
        val flow = PermissionFlowState()
        flow.nextBatch()
        flow.nextBatch()
        flow.nextBatch()
        assertEquals(PermissionBatch.MEDIA, flow.batch.value)
    }

    @Test
    fun testResetReturnsToNone() {
        val flow = PermissionFlowState()
        flow.nextBatch()
        flow.nextBatch()
        flow.reset()
        assertEquals(PermissionBatch.NONE, flow.batch.value)
    }

    @Test
    fun testFullCycle() {
        val flow = PermissionFlowState()
        assertEquals(PermissionBatch.NONE, flow.batch.value)

        flow.nextBatch()
        assertEquals(PermissionBatch.NOTIFICATIONS, flow.batch.value)

        // The notifications launcher's callback calls nextBatch(),
        // advancing to MEDIA.
        flow.nextBatch()
        assertEquals(PermissionBatch.MEDIA, flow.batch.value)

        // The media launcher's callback calls reset(), returning to
        // NONE — at which point the LaunchedEffect re-fires and the
        // cycle restarts (with all permissions already granted this
        // time, so no launcher actually launches).
        flow.reset()
        assertEquals(PermissionBatch.NONE, flow.batch.value)

        flow.nextBatch()
        assertEquals(PermissionBatch.NOTIFICATIONS, flow.batch.value)
    }
}
