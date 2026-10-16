package com.branchdam.mobile

import android.content.Context
import androidx.work.WorkerParameters
import com.branchdam.mobile.service.SyncWorker
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

/**
 * M6: how a sync batch's outcome is turned into a worker result and the
 * state the Sync screen reads, and that a stopped worker cancels Go.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class SyncWorkerOutcomeTest {

    private lateinit var prefs: android.content.SharedPreferences

    @org.junit.Before
    fun setUp() {
        prefs = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(BranchDamKeys.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun testAuthFailedIsTerminalAndDistinctFromNetworkErrors() {
        val auth = SyncBatchOutcome.Failed(CoreErrorCodes.AUTH_FAILED, "branchdam: AUTH_FAILED: 401")
        val network = SyncBatchOutcome.Failed("NETWORK_ERROR", "branchdam: NETWORK_ERROR: dial tcp")

        assertEquals(SyncWorker.Decision.AUTH_FAILED, SyncWorker.decide(auth, 0, SyncWorker.MAX_ATTEMPTS))
        assertEquals(SyncWorker.Decision.RETRY, SyncWorker.decide(network, 0, SyncWorker.MAX_ATTEMPTS))
        assertEquals(
            SyncWorker.Decision.FAILURE,
            SyncWorker.decide(network, SyncWorker.MAX_ATTEMPTS, SyncWorker.MAX_ATTEMPTS)
        )
        assertEquals(
            SyncWorker.Decision.SUCCESS,
            SyncWorker.decide(SyncBatchOutcome.Success, 2, SyncWorker.MAX_ATTEMPTS)
        )
    }

    @Test
    fun testSuccessPersistsLastSyncTimeAndClearsRepairFlag() {
        SyncWorker.recordOutcome(prefs, SyncWorker.Decision.AUTH_FAILED, 1_000L)
        assertEquals(1_000L, prefs.getLong(BranchDamKeys.SYNC_AUTH_FAILED_AT, 0L))
        assertEquals("a failed sync is not a sync", 0L, prefs.getLong(BranchDamKeys.LAST_SYNC_TIME, 0L))

        SyncWorker.recordOutcome(prefs, SyncWorker.Decision.SUCCESS, 2_000L)
        assertEquals(2_000L, prefs.getLong(BranchDamKeys.LAST_SYNC_TIME, 0L))
        assertEquals(0L, prefs.getLong(BranchDamKeys.SYNC_AUTH_FAILED_AT, 0L))

        SyncWorker.recordOutcome(prefs, SyncWorker.Decision.RETRY, 3_000L)
        assertEquals(2_000L, prefs.getLong(BranchDamKeys.LAST_SYNC_TIME, 0L))
    }

    @Test
    fun testStoppingTheWorkerSetsTheEngineCancelFlag() = kotlinx.coroutines.runBlocking {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val started = java.util.concurrent.CountDownLatch(1)
        val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)

        val job = launch(kotlinx.coroutines.Dispatchers.IO) {
            SyncWorker.runCancellingEngine(onCancel = { cancelled.set(true) }) {
                started.countDown()
                try {
                    Thread.sleep(30_000L) // a long, blocking syncBatch
                } catch (e: InterruptedException) {
                    interrupted.set(true)
                    throw e
                }
            }
        }
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        job.cancel() // WorkManager stops the worker
        job.join()

        assertTrue("the blocked engine wait must be interrupted", interrupted.get())
        assertTrue("stopping must ask Go to abort the batch", cancelled.get())
    }

    @Test
    fun testCompletedBatchDoesNotSetTheCancelFlag() = kotlinx.coroutines.runBlocking {
        var cancelled = false
        val result = SyncWorker.runCancellingEngine(onCancel = { cancelled = true }) { 42 }
        assertEquals(42, result)
        org.junit.Assert.assertFalse(cancelled)
    }

    @Test
    fun testCoreErrorCodeParsing() {
        assertEquals("AUTH_FAILED", coreErrorCode("branchdam: AUTH_FAILED: server rejected credentials (401)"))
        assertEquals("CONTEXT_CANCELED", coreErrorCode("go.Error: branchdam: CONTEXT_CANCELED: sync uploads cancelled"))
        assertEquals(null, coreErrorCode("connection refused"))
        assertEquals(null, coreErrorCode(null))
    }
}
