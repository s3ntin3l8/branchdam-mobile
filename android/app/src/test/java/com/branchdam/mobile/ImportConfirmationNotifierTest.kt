package com.branchdam.mobile

import com.branchdam.mobile.observer.MediaItem
import com.branchdam.mobile.service.ImportConfirmationNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImportConfirmationNotifierTest {

    @Before
    fun setUp() {
        ImportConfirmationNotifier.clearSuppressed()
        ImportConfirmationNotifier.clearPendingItems()
    }

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
    fun testItemSuppressionLogic() {
        val testId = "content://media/external/images/media/42"
        assertFalse(ImportConfirmationNotifier.isItemSuppressed(testId))

        ImportConfirmationNotifier.suppressItem(testId)
        assertTrue(ImportConfirmationNotifier.isItemSuppressed(testId))

        ImportConfirmationNotifier.clearSuppressed()
        assertFalse(ImportConfirmationNotifier.isItemSuppressed(testId))
    }

    @Test
    fun testBulkSuppression() {
        val id1 = "content://media/external/images/media/101"
        val id2 = "content://media/external/images/media/102"
        val id3 = "content://media/external/images/media/103"

        ImportConfirmationNotifier.suppressItems(listOf(id1, id2))
        assertTrue(ImportConfirmationNotifier.isItemSuppressed(id1))
        assertTrue(ImportConfirmationNotifier.isItemSuppressed(id2))
        assertFalse(ImportConfirmationNotifier.isItemSuppressed(id3))
    }

    @Test
    fun testStagingPendingItems() {
        val item1 = MediaItem(
            id = 1L,
            contentUri = "content://media/external/images/media/1",
            filePath = "/storage/emulated/0/DCIM/Camera/IMG_0001.JPG",
            displayName = "IMG_0001.JPG",
            mimeType = "image/jpeg",
            sizeBytes = 1024L,
            dateTakenUnix = 1700000000L,
            isRaw = false
        )
        val item2 = MediaItem(
            id = 2L,
            contentUri = "content://media/external/images/media/2",
            filePath = "/storage/emulated/0/DCIM/Camera/IMG_0002.DNG",
            displayName = "IMG_0002.DNG",
            mimeType = "image/x-adobe-dng",
            sizeBytes = 2048L,
            dateTakenUnix = 1700000001L,
            isRaw = true
        )

        ImportConfirmationNotifier.stagePendingItems(listOf(item1, item2))
        val staged = ImportConfirmationNotifier.getPendingItems()
        assertEquals(2, staged.size)

        ImportConfirmationNotifier.clearPendingItems()
        assertTrue(ImportConfirmationNotifier.getPendingItems().isEmpty())
    }

    @Test
    fun testSkipActionSuppressesAndClearsPendingItems() {
        val item1 = MediaItem(
            id = 10L,
            contentUri = "content://media/external/images/media/10",
            filePath = "/storage/emulated/0/DCIM/Camera/IMG_0010.JPG",
            displayName = "IMG_0010.JPG",
            mimeType = "image/jpeg",
            sizeBytes = 1024L,
            dateTakenUnix = 1700000010L,
            isRaw = false
        )
        ImportConfirmationNotifier.stagePendingItems(listOf(item1))
        assertFalse(ImportConfirmationNotifier.isItemSuppressed("content://media/external/images/media/10"))

        ImportConfirmationNotifier.handleAction(
            context = null,
            action = ImportConfirmationNotifier.ACTION_SKIP,
            itemIds = arrayOf("content://media/external/images/media/10")
        )

        assertTrue(ImportConfirmationNotifier.isItemSuppressed("content://media/external/images/media/10"))
        assertTrue(ImportConfirmationNotifier.getPendingItems().isEmpty())
    }
}
