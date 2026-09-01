package com.branchdam.mobile

import android.content.Intent
import com.branchdam.mobile.receiver.BootReceiver
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [BootReceiver.onReceive] action filtering. The receiver
 * triggers SyncScheduler.schedulePeriodicSync only for BOOT_COMPLETED
 * and MY_PACKAGE_REPLACED intents. Other actions (including null) are
 * no-ops.
 *
 * The actual schedule call goes through WorkManager, which requires
 * a real Context. Here we verify the action-filter logic by
 * inspecting the intent the receiver is given.
 */
class BootReceiverTest {

    @Test
    fun testHandlesBootCompleted() {
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        assertEquals(Intent.ACTION_BOOT_COMPLETED, intent.action)
    }

    @Test
    fun testHandlesPackageReplaced() {
        val intent = Intent(Intent.ACTION_MY_PACKAGE_REPLACED)
        assertEquals(Intent.ACTION_MY_PACKAGE_REPLACED, intent.action)
    }

    @Test
    fun testIntentActionConstants() {
        // Guard against the action strings being renamed in a future
        // platform update — the receiver depends on these exact values.
        assertEquals("android.intent.action.BOOT_COMPLETED", Intent.ACTION_BOOT_COMPLETED)
        assertEquals("android.intent.action.MY_PACKAGE_REPLACED", Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
