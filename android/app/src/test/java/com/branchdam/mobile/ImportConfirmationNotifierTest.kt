package com.branchdam.mobile

import com.branchdam.mobile.observer.MediaItem
import com.branchdam.mobile.service.ImportConfirmationNotifier
import com.branchdam.mobile.service.toMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The confirmation flow's pending and skipped items live in the core's
 * pending_import table. Without the native library EngineHolder serves
 * the same contract from [MockPendingImportStore], which these tests
 * drive.
 */
class ImportConfirmationNotifierTest {

    @Before
    fun setUp() {
        EngineHolder.mockPendingImports.clear()
        EngineHolder.mockPendingImports.clock = { 1_700_000_000L }
    }

    private fun item(id: Long, takenAt: Long = 1_700_000_000L + id) = MediaItem(
        id = id,
        contentUri = "content://media/external/images/media/$id",
        filePath = "/storage/emulated/0/DCIM/Camera/IMG_$id.JPG",
        displayName = "IMG_$id.JPG",
        mimeType = "image/jpeg",
        sizeBytes = 1024L * id,
        dateTakenUnix = takenAt,
        isRaw = false
    )

    @Test
    fun testConstants() {
        assertEquals("branchdam_import_channel", ImportConfirmationNotifier.CHANNEL_ID)
        assertEquals(4101, ImportConfirmationNotifier.NOTIFICATION_ID)
        assertEquals("branchdam_auto_import_camera_roll", ImportConfirmationNotifier.KEY_AUTO_IMPORT)
        assertEquals("com.branchdam.mobile.action.IMPORT_NOW", ImportConfirmationNotifier.ACTION_IMPORT_NOW)
        assertEquals("com.branchdam.mobile.action.LATER", ImportConfirmationNotifier.ACTION_LATER)
        assertEquals("com.branchdam.mobile.action.SKIP", ImportConfirmationNotifier.ACTION_SKIP)
    }

    @Test
    fun testStagedItemsAreReadBackWithTheirMetadata() {
        assertEquals(EngineHolder.StageResult.STAGED, ImportConfirmationNotifier.stagePendingItem(item(2)))
        assertEquals(EngineHolder.StageResult.STAGED, ImportConfirmationNotifier.stagePendingItem(item(1)))

        val pending = ImportConfirmationNotifier.getPendingItems()
        assertEquals(listOf(item(1).contentUri, item(2).contentUri), pending.map { it.localId })
        val first = pending.first()
        assertEquals(item(1).filePath, first.localPath)
        assertEquals("IMG_1.JPG", first.filename)
        assertEquals("image/jpeg", first.mimeType)
        assertEquals(1024L, first.sizeBytes)
        assertEquals(item(1).dateTakenUnix, first.capturedAtUnix)
    }

    @Test
    fun testImportNowReadsPersistedItemsAndForgetsThemOnceQueued() {
        // Staged by an earlier process: nothing in the notifier holds them.
        ImportConfirmationNotifier.stagePendingItem(item(10))
        ImportConfirmationNotifier.stagePendingItem(item(11))

        val handled = ImportConfirmationNotifier.handleAction(
            context = null,
            action = ImportConfirmationNotifier.ACTION_IMPORT_NOW,
        )

        assertTrue(handled)
        assertTrue(
            "imported items must leave pending_import",
            ImportConfirmationNotifier.getPendingItems().isEmpty()
        )
        assertFalse(ImportConfirmationNotifier.isItemSuppressed(item(10).contentUri))
    }

    @Test
    fun testSkipSuppressesAndScansDoNotReofferTheItem() {
        ImportConfirmationNotifier.stagePendingItem(item(20))

        ImportConfirmationNotifier.handleAction(
            context = null,
            action = ImportConfirmationNotifier.ACTION_SKIP,
        )

        assertTrue(ImportConfirmationNotifier.isItemSuppressed(item(20).contentUri))
        assertTrue(ImportConfirmationNotifier.getPendingItems().isEmpty())
        assertEquals(
            "a skipped item must not be staged again by a later scan",
            EngineHolder.StageResult.SUPPRESSED,
            ImportConfirmationNotifier.stagePendingItem(item(20))
        )
        assertTrue(ImportConfirmationNotifier.getPendingItems().isEmpty())
    }

    @Test
    fun testActionOnlyAppliesToItemsStagedBeforeTheNotification() {
        EngineHolder.mockPendingImports.clock = { 100L }
        ImportConfirmationNotifier.stagePendingItem(item(30))
        EngineHolder.mockPendingImports.clock = { 200L }
        ImportConfirmationNotifier.stagePendingItem(item(31))

        // The user tapped Skip on a notification posted at t=150.
        ImportConfirmationNotifier.handleAction(
            context = null,
            action = ImportConfirmationNotifier.ACTION_SKIP,
            stagedBeforeUnix = 150L,
        )

        assertTrue(ImportConfirmationNotifier.isItemSuppressed(item(30).contentUri))
        assertFalse(
            "an item staged after the notification was posted must not be skipped",
            ImportConfirmationNotifier.isItemSuppressed(item(31).contentUri)
        )
        assertEquals(listOf(item(31).contentUri), ImportConfirmationNotifier.getPendingItems().map { it.localId })
    }

    @Test
    fun testManualSuppression() {
        val id = "content://media/external/images/media/42"
        assertFalse(ImportConfirmationNotifier.isItemSuppressed(id))
        assertTrue(ImportConfirmationNotifier.suppressItem(id))
        assertTrue(ImportConfirmationNotifier.isItemSuppressed(id))
    }

    @Test
    fun testPendingImportRebuildsTheMediaItemUploadStagingNeeds() {
        ImportConfirmationNotifier.stagePendingItem(item(77).copy(mimeType = "image/x-adobe-dng", displayName = "PXL_77.DNG"))
        val rebuilt = ImportConfirmationNotifier.getPendingItems().single().toMediaItem()

        // UploadStaging names the staged copy after the MediaStore row ID.
        assertEquals(77L, rebuilt.id)
        assertEquals(item(77).contentUri, rebuilt.contentUri)
        assertEquals(item(77).filePath, rebuilt.filePath)
        assertEquals("PXL_77.DNG", rebuilt.displayName)
        assertEquals(item(77).dateTakenUnix, rebuilt.dateTakenUnix)
        assertTrue(rebuilt.isRaw)
    }
}
