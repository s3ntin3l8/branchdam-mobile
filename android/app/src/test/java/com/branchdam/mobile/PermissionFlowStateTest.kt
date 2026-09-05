package com.branchdam.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [PermissionFlowState] — the state machine that sequences
 * permission batch requests. The original implementation fired two
 * `requestPermissions()` calls synchronously inside `onCreate`, and on
 * Android 13+ the second call dismissed the first dialog without
 * delivering its result. The state machine replaces that with a
 * NONE → NOTIFICATIONS → MEDIA → DONE transition so the second dialog
 * cannot appear before the first dialog has been resolved and so a
 * user who permanently denies a permission cannot cause the flow to
 * loop forever.
 *
 * `DONE` is the regression guard: pre-fix the flow reset to `NONE`
 * after every callback, which meant a deny-everything user would
 * re-trigger the launchers on every Compose recomposition.
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
    fun testNextBatchFromMediaGoesToDone() {
        val flow = PermissionFlowState()
        flow.nextBatch()
        flow.nextBatch()
        flow.nextBatch()
        assertEquals(PermissionBatch.DONE, flow.batch.value)
    }

    @Test
    fun testNextBatchFromDoneStaysAtDone() {
        val flow = PermissionFlowState()
        flow.nextBatch()
        flow.nextBatch()
        flow.nextBatch()
        flow.nextBatch()
        assertEquals(PermissionBatch.DONE, flow.batch.value)
    }

    @Test
    fun testDoneStateIsTerminal() {
        val flow = PermissionFlowState()
        flow.markDone()
        assertEquals(PermissionBatch.DONE, flow.batch.value)
        flow.nextBatch()
        assertEquals(PermissionBatch.DONE, flow.batch.value)
        flow.markDone()
        assertEquals(PermissionBatch.DONE, flow.batch.value)
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

        // The media launcher's callback calls nextBatch(), advancing
        // to DONE — the terminal state. A user who denies everything
        // will still terminate here, not loop back to NONE.
        flow.nextBatch()
        assertEquals(PermissionBatch.DONE, flow.batch.value)

        // Any further nextBatch / markDone calls are no-ops.
        flow.nextBatch()
        assertEquals(PermissionBatch.DONE, flow.batch.value)
    }
}
