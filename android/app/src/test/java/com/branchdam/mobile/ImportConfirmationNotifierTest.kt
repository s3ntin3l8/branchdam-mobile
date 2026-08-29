package com.branchdam.mobile

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
    }

    @Test
    fun testConstants() {
        assertEquals("branchdam_import_channel", ImportConfirmationNotifier.CHANNEL_ID)
        assertEquals(4101, ImportConfirmationNotifier.NOTIFICATION_ID)
        assertEquals("auto_import_camera_roll", ImportConfirmationNotifier.KEY_AUTO_IMPORT)
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
}
