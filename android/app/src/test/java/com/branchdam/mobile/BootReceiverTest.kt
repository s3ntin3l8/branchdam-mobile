package com.branchdam.mobile

import android.content.Context
import android.content.Intent
import com.branchdam.mobile.receiver.BootReceiver
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Tests for BootReceiver.onReceive action filtering. The receiver
 * triggers SyncScheduler.schedulePeriodicSync only for BOOT_COMPLETED
 * and MY_PACKAGE_REPLACED intents. Other actions (including null)
 * are no-ops.
 *
 * The receiver takes a scheduleSync lambda seam (defaulting to
 * SyncScheduler::schedulePeriodicSync). Tests inject a recording
 * lambda to verify which intents trigger the scheduler.
 */
class BootReceiverTest {

    @Test
    fun testSchedulesSyncForBootCompleted() {
        val scheduled = mutableListOf<Context>()
        val context: Context = mock()
        val receiver = BootReceiver(
            scheduleSync = { ctx -> scheduled.add(ctx) }
        )
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1, scheduled.size)
        assertEquals(context, scheduled[0])
    }

    @Test
    fun testSchedulesSyncForPackageReplaced() {
        val scheduled = mutableListOf<Context>()
        val context: Context = mock()
        val receiver = BootReceiver(
            scheduleSync = { ctx -> scheduled.add(ctx) }
        )
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(1, scheduled.size)
        assertEquals(context, scheduled[0])
    }

    @Test
    fun testIgnoresOtherActions() {
        // PACKAGE_ADDED is NOT one of the receiver's target actions.
        // The receiver must not call scheduleSync.
        val scheduled = mutableListOf<Context>()
        val context: Context = mock()
        val receiver = BootReceiver(
            scheduleSync = { ctx -> scheduled.add(ctx) }
        )
        receiver.onReceive(context, Intent(Intent.ACTION_PACKAGE_ADDED))
        assertEquals(0, scheduled.size)
    }

    @Test
    fun testNullIntentIsNoOp() {
        val scheduled = mutableListOf<Context>()
        val context: Context = mock()
        val receiver = BootReceiver(
            scheduleSync = { ctx -> scheduled.add(ctx) }
        )
        // The receiver reads intent?.action — null intent must be
        // a no-op (the scheduleSync seam should not be called).
        receiver.onReceive(context, null)
        assertEquals(0, scheduled.size)
    }

    @Test
    fun testIntentActionConstants() {
        // Regression guard for the action strings the receiver
        // depends on. If these constants are renamed in a future
        // platform update, the receiver's action filter breaks.
        assertEquals("android.intent.action.BOOT_COMPLETED", Intent.ACTION_BOOT_COMPLETED)
        assertEquals("android.intent.action.MY_PACKAGE_REPLACED", Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
