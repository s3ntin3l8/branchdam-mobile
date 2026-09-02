package com.branchdam.mobile

import android.content.Context
import android.content.Intent
import com.branchdam.mobile.receiver.BootReceiver
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for BootReceiver.onReceive action filtering. The receiver
 * triggers SyncScheduler.schedulePeriodicSync only for BOOT_COMPLETED
 * and MY_PACKAGE_REPLACED intents. Other actions (including null)
 * are no-ops.
 *
 * With isReturnDefaultValues=true, the Intent constructor does NOT
 * actually set the action string — it returns a stub where .action
 * is null. We mock Intent and stub .action to return the correct
 * constant value.
 *
 * The receiver takes a scheduleSync lambda seam (defaulting to
 * SyncScheduler::schedulePeriodicSync). Tests inject a recording
 * lambda to verify which intents trigger the scheduler.
 */
class BootReceiverTest {

    private fun intentWithAction(action: String?): Intent {
        val intent: Intent = mock()
        whenever(intent.action).thenReturn(action)
        return intent
    }

    @Test
    fun testSchedulesSyncForBootCompleted() {
        val scheduled = mutableListOf<Context>()
        val context: Context = mock()
        val receiver = BootReceiver(
            scheduleSync = { ctx -> scheduled.add(ctx) }
        )
        receiver.onReceive(context, intentWithAction(Intent.ACTION_BOOT_COMPLETED))
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
        receiver.onReceive(context, intentWithAction(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(1, scheduled.size)
        assertEquals(context, scheduled[0])
    }

    @Test
    fun testIgnoresOtherActions() {
        // PACKAGE_ADDED is NOT one of the receiver's target actions.
        val scheduled = mutableListOf<Context>()
        val context: Context = mock()
        val receiver = BootReceiver(
            scheduleSync = { ctx -> scheduled.add(ctx) }
        )
        receiver.onReceive(context, intentWithAction(Intent.ACTION_PACKAGE_ADDED))
        assertEquals(0, scheduled.size)
    }

    @Test
    fun testNullIntentIsNoOp() {
        val scheduled = mutableListOf<Context>()
        val context: Context = mock()
        val receiver = BootReceiver(
            scheduleSync = { ctx -> scheduled.add(ctx) }
        )
        receiver.onReceive(context, null)
        assertEquals(0, scheduled.size)
    }

    @Test
    fun testIntentActionConstants() {
        // Regression guard for the action strings the receiver
        // depends on.
        assertEquals("android.intent.action.BOOT_COMPLETED", Intent.ACTION_BOOT_COMPLETED)
        assertEquals("android.intent.action.MY_PACKAGE_REPLACED", Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
