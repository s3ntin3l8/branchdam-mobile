package com.branchdam.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.branchdam.mobile.service.SyncNotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SyncNotificationHelperTest {

    @Test
    fun testChannelConstants() {
        assertEquals("branchdam_sync_channel", SyncNotificationHelper.CHANNEL_ID)
        assertEquals("branchDAM Sync", SyncNotificationHelper.CHANNEL_NAME)
        assertEquals("Background sync progress", SyncNotificationHelper.CHANNEL_DESCRIPTION)
    }

    @Test
    fun testNotificationConstants() {
        assertEquals(4201, SyncNotificationHelper.NOTIFICATION_ID)
        assertEquals("Syncing camera roll", SyncNotificationHelper.NOTIFICATION_TITLE)
        assertTrue(
            "notification text must mention 'branchDAM server'",
            SyncNotificationHelper.NOTIFICATION_TEXT.contains("branchDAM server")
        )
    }

    @Test
    fun testNotificationIdsAreDistinctFromImportChannel() {
        assertTrue(
            "sync and import notification ids must differ",
            SyncNotificationHelper.NOTIFICATION_ID != com.branchdam.mobile.service.ImportConfirmationNotifier.NOTIFICATION_ID
        )
    }

    @Test
    fun testForegroundServiceTypeReturnsZeroOnPreUpsideDownCake() {
        // With isReturnDefaultValues=true in testOptions, Build.VERSION.SDK_INT
        // returns 0 on the test JVM, which is below UPSIDE_DOWN_CAKE (34), so
        // the function must return 0 (no foreground-service type). On real
        // Android 14+ devices it returns FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        // that's exercised by instrumented tests, not unit tests.
        assertEquals(0, SyncNotificationHelper.foregroundServiceType())
    }

    @Test
    fun testEnsureChannelDoesNotRecreateExistingChannel() {
        val context: Context = mock()
        val manager: NotificationManager = mock()
        val existing: NotificationChannel = mock()
        whenever(context.getSystemService(Context.NOTIFICATION_SERVICE))
            .thenReturn(manager)
        whenever(manager.getNotificationChannel(SyncNotificationHelper.CHANNEL_ID))
            .thenReturn(existing)

        SyncNotificationHelper.ensureChannel(context)
        verify(manager, never()).createNotificationChannel(any<NotificationChannel>())
    }
}
