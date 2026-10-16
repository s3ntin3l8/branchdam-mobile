package com.branchdam.mobile

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.branchdam.mobile.observer.MediaItem
import com.branchdam.mobile.receiver.ImportConfirmationReceiver
import com.branchdam.mobile.service.ImportConfirmationNotifier
import com.branchdam.mobile.service.ImportConfirmationWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A notification action is applied by [ImportConfirmationWorker], not
 * inside the receiver's goAsync() window: the receiver only enqueues.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportConfirmationWorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        EngineHolder.mockPendingImports.clear()
    }

    @After
    fun tearDown() {
        EngineHolder.mockPendingImports.clear()
        EngineHolder.mockPendingImports.clock = { System.currentTimeMillis() / 1000L }
    }

    private fun item(id: Long) = MediaItem(
        id = id,
        contentUri = "content://media/external/images/media/$id",
        filePath = "/storage/emulated/0/DCIM/Camera/IMG_$id.JPG",
        displayName = "IMG_$id.JPG",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        dateTakenUnix = 1_700_000_000L + id,
        isRaw = false,
    )

    private fun worker(input: androidx.work.Data, runAttemptCount: Int = 0): ImportConfirmationWorker =
        TestListenableWorkerBuilder.from(context, ImportConfirmationWorker::class.java)
            .setInputData(input)
            .setRunAttemptCount(runAttemptCount)
            .build()

    @Test
    fun testWorkerAppliesTheActionToItemsStagedBeforeTheNotification() = runBlocking {
        EngineHolder.mockPendingImports.clock = { 100L }
        ImportConfirmationNotifier.stagePendingItem(item(30))
        EngineHolder.mockPendingImports.clock = { 200L }
        ImportConfirmationNotifier.stagePendingItem(item(31))

        val result = worker(
            ImportConfirmationWorker.inputFor(ImportConfirmationNotifier.ACTION_SKIP, stagedBeforeUnix = 150L)
        ).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(ImportConfirmationNotifier.isItemSuppressed(item(30).contentUri))
        assertFalse(
            "an item staged after the notification was posted must not be skipped",
            ImportConfirmationNotifier.isItemSuppressed(item(31).contentUri),
        )
        assertEquals(listOf(item(31).contentUri), ImportConfirmationNotifier.getPendingItems().map { it.localId })
    }

    @Test
    fun testActionAppliesOnlyToTheAnnouncedRowsEvenInTheSameSecond() = runBlocking {
        // Both rows are staged in the same second; only 40 was announced.
        EngineHolder.mockPendingImports.clock = { 500L }
        ImportConfirmationNotifier.stagePendingItem(item(40))
        ImportConfirmationNotifier.recordAnnounced(context, listOf(item(40).contentUri))
        ImportConfirmationNotifier.stagePendingItem(item(41))

        val result = worker(
            ImportConfirmationWorker.inputFor(ImportConfirmationNotifier.ACTION_SKIP, stagedBeforeUnix = 500L)
        ).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(ImportConfirmationNotifier.isItemSuppressed(item(40).contentUri))
        assertFalse(
            "a row staged after the announcement, even in the same second, must not be skipped",
            ImportConfirmationNotifier.isItemSuppressed(item(41).contentUri),
        )
        assertEquals(listOf(item(41).contentUri), ImportConfirmationNotifier.getPendingItems().map { it.localId })
    }

    @Test
    fun testWorkWithoutAKnownActionFails() = runBlocking {
        assertEquals(ListenableWorker.Result.failure(), worker(androidx.work.Data.EMPTY).doWork())
        assertEquals(
            ListenableWorker.Result.failure(),
            worker(ImportConfirmationWorker.inputFor("com.example.UNKNOWN", 0L)).doWork(),
        )
    }

    @Test
    fun testUnreadablePendingItemsRetryOnceThenGiveUp() {
        assertEquals(ImportConfirmationWorker.Decision.DONE, ImportConfirmationWorker.decide(true, 0))
        assertEquals(ImportConfirmationWorker.Decision.DONE, ImportConfirmationWorker.decide(true, 5))
        assertEquals(ImportConfirmationWorker.Decision.RETRY, ImportConfirmationWorker.decide(false, 0))
        assertEquals(ImportConfirmationWorker.Decision.GIVE_UP, ImportConfirmationWorker.decide(false, 1))
    }

    @Test
    fun testInputCarriesTheActionAndTheStagedBeforeCutoff() {
        val data = ImportConfirmationWorker.inputFor(ImportConfirmationNotifier.ACTION_IMPORT_NOW, 1_234L)
        assertEquals(
            ImportConfirmationNotifier.ACTION_IMPORT_NOW,
            data.getString(ImportConfirmationWorker.KEY_ACTION),
        )
        assertEquals(1_234L, data.getLong(ImportConfirmationWorker.KEY_STAGED_BEFORE_UNIX, 0L))
    }

    @Test
    fun testWorkerDeclaresForegroundInfoForExpeditedRunsBelowApi31() {
        val declared = ImportConfirmationWorker::class.java.declaredMethods
            .any { it.name == "getForegroundInfo" }
        assertTrue("expedited work below API 31 needs getForegroundInfo()", declared)
    }

    @Test
    fun testReceiverOnlyEnqueuesTheUniqueWork() {
        val intent = Intent(ImportConfirmationNotifier.ACTION_SKIP)
            .putExtra(ImportConfirmationNotifier.EXTRA_STAGED_BEFORE_UNIX, 150L)

        ImportConfirmationReceiver().onReceive(context, intent)

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ImportConfirmationWorker.UNIQUE_WORK_NAME)
            .get()
        assertEquals(1, infos.size)
    }

    @Test
    fun testReceiverIgnoresUnknownActions() {
        ImportConfirmationReceiver().onReceive(context, Intent("com.example.UNKNOWN"))
        ImportConfirmationReceiver().onReceive(context, null)

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ImportConfirmationWorker.UNIQUE_WORK_NAME)
            .get()
        assertTrue(infos.isEmpty())
    }
}
