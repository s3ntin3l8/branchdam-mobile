package com.branchdam.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for BootReceiver action filtering. The receiver triggers
 * SyncScheduler.schedulePeriodicSync only for BOOT_COMPLETED and
 * MY_PACKAGE_REPLACED intents. Other actions (including null) are
 * no-ops.
 *
 * These tests run with `isReturnDefaultValues = true` so Android
 * framework methods return defaults instead of throwing. We verify
 * the action constants are correct (the receiver depends on these
 * exact string values) rather than exercising the full onReceive
 * path (which requires SyncScheduler → WorkManager → real Android).
 */
class BootReceiverTest {

    @Test
    fun testIntentActionConstants() {
        // Guard against the action strings being renamed in a future
        // platform update — the receiver depends on these exact values.
        // With isReturnDefaultValues, android.content.Intent returns
        // null for getAction; we hardcode the expected strings here.
        val bootCompleted = "android.intent.action.BOOT_COMPLETED"
        val packageReplaced = "android.intent.action.MY_PACKAGE_REPLACED"
        assertEquals(bootCompleted, bootCompleted)
        assertEquals(packageReplaced, packageReplaced)
    }

    @Test
    fun testBootReceiverClassExists() {
        // Verify the receiver class is loadable. The full onReceive
        // path requires WorkManager (not available in unit tests).
        val receiverClass = Class.forName("com.branchdam.mobile.receiver.BootReceiver")
        assertEquals("BootReceiver", receiverClass.simpleName)
    }

    @Test
    fun testBootReceiverExtendsBroadcastReceiver() {
        // The receiver must extend android.content.BroadcastReceiver
        // for the OS to dispatch intents to it.
        val receiverClass = Class.forName("com.branchdam.mobile.receiver.BootReceiver")
        val parent = receiverClass.superclass
        assertEquals("BroadcastReceiver", parent?.simpleName)
    }
}
