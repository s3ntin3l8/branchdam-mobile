package com.branchdam.mobile

import android.content.Context
import androidx.work.WorkerParameters
import com.branchdam.mobile.service.SyncWorker
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for the SyncWorker structural contract. With isReturnDefaultValues=true
 * in testOptions, the test JVM returns default values for Android framework
 * calls — so we focus on the structural and override-based contract:
 *   * SyncWorker extends CoroutineWorker (runtime check guards against a
 *     refactor that drops the base type)
 *   * SyncWorker declares a getForegroundInfo() override so WorkManager's
 *     reflection-based dispatch finds it
 *
 * The actual doWork() lifecycle (setForeground call, engine dispatch, retry
 * logic) and buildNotification() output require a real WorkManager runtime
 * and Notification stack; those are exercised by androidTest/ instrumentation
 * tests.
 */
class SyncWorkerTest {

    @Suppress("USELESS_IS_CHECK")
    @Test
    fun testSyncWorkerIsCoroutineWorker() {
        val context: Context = mock()
        val params: WorkerParameters = mock()
        whenever(params.runAttemptCount).thenReturn(0)
        val worker = SyncWorker(context, params)
        // Runtime check guards against a refactor that drops the
        // CoroutineWorker base class.
        assertEquals(
            androidx.work.CoroutineWorker::class.java,
            worker.javaClass.superclass
        )
    }

    @Test
    fun testGetForegroundInfoOverrideExists() {
        // WorkManager calls ListenableWorker.getForegroundInfoAsync(), which
        // internally invokes the suspend getForegroundInfo() override.
        // Verify the override is declared on the SyncWorker class so the
        // runtime reflection-based dispatch in androidx.work finds it.
        val declared = SyncWorker::class.java.declaredMethods
            .any { it.name == "getForegroundInfo" }
        org.junit.Assert.assertTrue(
            "SyncWorker must declare a getForegroundInfo() override",
            declared
        )
    }
}
