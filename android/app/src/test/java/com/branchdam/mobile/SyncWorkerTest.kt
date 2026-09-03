package com.branchdam.mobile

import android.content.Context
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.branchdam.mobile.service.SyncNotificationHelper
import com.branchdam.mobile.service.SyncWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for the SyncWorker structural contract. With isReturnDefaultValues=true
 * in testOptions, the test JVM returns default values for Android framework
 * calls — so we focus on the structural and override-based contract:
 *   * SyncWorker overrides CoroutineWorker's foreground-info hook
 *   * The returned ForegroundInfo carries the sync notification id and the
 *     foreground-service type produced by SyncNotificationHelper
 *   * The notification is non-null
 *
 * The actual doWork() lifecycle (setForeground call, engine dispatch, retry
 * logic) requires a real WorkManager runtime and the gomobile-bound engine;
 * that's exercised by androidTest/ instrumentation tests.
 */
class SyncWorkerTest {

    @Test
    fun testSyncWorkerIsCoroutineWorker() {
        val context: Context = mock()
        val params: WorkerParameters = mock()
        whenever(params.runAttemptCount).thenReturn(0)
        val worker = SyncWorker(context, params)
        assertTrue(
            "SyncWorker must extend androidx.work.CoroutineWorker",
            worker is androidx.work.CoroutineWorker
        )
    }

    @Test
    fun testGetForegroundInfoUsesHelperContract() {
        val context: Context = mock()
        val params: WorkerParameters = mock()
        whenever(params.runAttemptCount).thenReturn(0)

        val worker = SyncWorker(context, params)
        val info: ForegroundInfo = runBlocking { worker.getForegroundInfo() }

        assertNotNull("ForegroundInfo.notification must be set", info.notification)
        assertEquals(
            "ForegroundInfo.notificationId must come from SyncNotificationHelper",
            SyncNotificationHelper.NOTIFICATION_ID,
            info.notificationId
        )
        assertEquals(
            "ForegroundInfo.foregroundServiceType must match helper's platform gate",
            SyncNotificationHelper.foregroundServiceType(),
            info.foregroundServiceType
        )
    }
}