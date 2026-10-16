package com.branchdam.mobile

import com.branchdam.mobile.observer.MediaItem
import com.branchdam.mobile.service.ImportConfirmationNotifier
import com.branchdam.mobile.service.PendingImportAnnouncer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The import confirmation is driven by the persisted pending_import set,
 * so rows a dead process staged (now below the scan cursor, never staged
 * again) are still announced.
 */
class PendingImportAnnouncerTest {

    @Before
    fun setUp() {
        EngineHolder.mockPendingImports.clear()
    }

    @After
    fun tearDown() {
        EngineHolder.mockPendingImports.clear()
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

    private fun row(id: Int) = PendingImport(
        localId = "content://media/external/images/media/$id",
        localPath = "/storage/emulated/0/DCIM/Camera/IMG_$id.JPG",
        filename = "IMG_$id.JPG",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        capturedAtUnix = 1_700_000_000L + id,
        createdAtUnix = 1_700_000_000L,
    )

    @Test
    fun testRowsStagedByADeadProcessAreAnnouncedAtStartup() {
        // An earlier process staged these, then died before the user
        // answered. The scan cursor is already past them.
        ImportConfirmationNotifier.stagePendingItem(item(1))
        ImportConfirmationNotifier.stagePendingItem(item(2))

        // New process: nothing was staged by this process's scans.
        val posted = mutableListOf<Int>()
        val announcer = PendingImportAnnouncer(post = { posted += it })

        assertTrue(announcer.refresh())
        assertEquals("the persisted set is announced", listOf(2), posted)
    }

    @Test
    fun testUnchangedSetIsNotRepostedOnEveryScan() {
        var pending = listOf(row(1), row(2))
        val posted = mutableListOf<Int>()
        val announcer = PendingImportAnnouncer(readPending = { pending }, post = { posted += it })

        assertTrue(announcer.refresh())
        assertFalse("an unrelated MediaStore change must not re-post", announcer.refresh())

        pending = listOf(row(1), row(2), row(3))
        assertTrue("a grown set is announced with its new count", announcer.refresh())

        assertTrue(
            "items staged by this pass are announced even when the count is unchanged",
            announcer.refresh(stagedThisPass = 1),
        )
        assertEquals(listOf(2, 3, 3), posted)
    }

    @Test
    fun testNothingPendingPostsNothing() {
        val posted = mutableListOf<Int>()
        val announcer = PendingImportAnnouncer(readPending = { emptyList() }, post = { posted += it })

        assertFalse(announcer.refresh())
        assertFalse(announcer.refresh(stagedThisPass = 3))
        assertTrue(posted.isEmpty())
    }

    @Test
    fun testUnreadableSetDoesNotUseUpTheStartupAnnouncement() {
        // The engine is not open yet on the first (startup) refresh.
        var pending: List<PendingImport>? = null
        val posted = mutableListOf<Int>()
        val announcer = PendingImportAnnouncer(readPending = { pending }, post = { posted += it })

        assertFalse(announcer.refresh())
        assertTrue(posted.isEmpty())

        // The next scan's refresh can read it and must still announce.
        pending = listOf(row(1))
        assertTrue(announcer.refresh())
        assertEquals(listOf(1), posted)
    }

    @Test
    fun testAnsweredSetIsAnnouncedAgainWhenRowsReappear() {
        var pending = listOf(row(1))
        val posted = mutableListOf<Int>()
        val announcer = PendingImportAnnouncer(readPending = { pending }, post = { posted += it })

        assertTrue(announcer.refresh())
        pending = emptyList() // the user answered
        assertFalse(announcer.refresh())
        pending = listOf(row(1)) // e.g. an import whose enqueue failed stays pending
        assertTrue(announcer.refresh())
        assertEquals(listOf(1, 1), posted)
    }
}
