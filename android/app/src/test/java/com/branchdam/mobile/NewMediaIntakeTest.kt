package com.branchdam.mobile

import com.branchdam.mobile.observer.MediaItem
import com.branchdam.mobile.observer.NewMediaIntake
import com.branchdam.mobile.observer.NewMediaIntake.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan cursor may only move past an item once that item's intent
 * (upload-queue row or pending_import row) is persisted.
 */
class NewMediaIntakeTest {

    private fun item(id: Long, takenAt: Long, path: String = "/DCIM/Camera/IMG_$id.jpg") = MediaItem(
        id = id,
        contentUri = "content://media/external/images/media/$id",
        filePath = path,
        displayName = "IMG_$id.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 100L,
        dateTakenUnix = takenAt,
        isRaw = false,
    )

    @Test
    fun testEveryItemIsStagedBeforeTheCursorIsSaved() {
        val log = mutableListOf<String>()
        val result = NewMediaIntake.commit(
            startCursor = 1_000L,
            scanned = listOf(item(2, 1_020L), item(1, 1_010L)),
            persist = { log += "stage:${it.id}"; Outcome.STAGED },
            saveCursor = { log += "cursor:$it" },
        )

        assertEquals(listOf("stage:1", "stage:2", "cursor:1020"), log)
        assertEquals(1_020L, result.cursor)
        assertEquals(listOf(1L, 2L), result.staged.map { it.id })
        assertTrue(result.enqueued.isEmpty())
    }

    @Test
    fun testFailedStagingKeepsTheCursorBelowTheItem() {
        val saved = mutableListOf<Long>()
        val result = NewMediaIntake.commit(
            startCursor = 1_000L,
            scanned = listOf(item(1, 1_010L), item(2, 1_020L), item(3, 1_030L)),
            persist = { if (it.id == 2L) Outcome.FAILED else Outcome.STAGED },
            saveCursor = { saved += it },
        )

        // Item 2 (and everything after it) must be offered again.
        assertEquals(listOf(1_010L), saved)
        assertEquals(1_010L, result.cursor)
        assertEquals(listOf(1L), result.staged.map { it.id })
    }

    @Test
    fun testFailureInTheSameSecondAsAPersistedItemRewindsBelowIt() {
        val saved = mutableListOf<Long>()
        val result = NewMediaIntake.commit(
            startCursor = 1_000L,
            scanned = listOf(item(1, 1_010L), item(2, 1_010L)),
            persist = { if (it.id == 2L) Outcome.FAILED else Outcome.STAGED },
            saveCursor = { saved += it },
        )

        // The query is DATE_TAKEN > cursor, so the cursor must stay below
        // 1010 or item 2 would never be scanned again.
        assertEquals(1_009L, result.cursor)
        assertEquals(listOf(1_009L), saved)
    }

    @Test
    fun testNothingPersistedLeavesTheCursorUntouched() {
        val saved = mutableListOf<Long>()
        val result = NewMediaIntake.commit(
            startCursor = 1_000L,
            scanned = listOf(item(1, 1_010L)),
            persist = { Outcome.FAILED },
            saveCursor = { saved += it },
        )

        assertTrue("engine not ready: the cursor must not move", saved.isEmpty())
        assertEquals(1_000L, result.cursor)
    }

    @Test
    fun testSkippedItemsAdvanceTheCursorButAreNotReoffered() {
        val result = NewMediaIntake.commit(
            startCursor = 1_000L,
            scanned = listOf(item(1, 1_010L), item(2, 1_020L)),
            persist = { if (it.id == 1L) Outcome.ALREADY_DECIDED else Outcome.ENQUEUED },
            saveCursor = {},
        )

        assertEquals(1_020L, result.cursor)
        assertTrue(result.staged.isEmpty())
        assertEquals(listOf(2L), result.enqueued.map { it.id })
    }

    @Test
    fun testItemsWithoutAFilePathAreIgnored() {
        val persisted = mutableListOf<Long>()
        NewMediaIntake.commit(
            startCursor = 0L,
            scanned = listOf(item(1, 10L, path = ""), item(2, 20L)),
            persist = { persisted += it.id; Outcome.STAGED },
            saveCursor = {},
        )
        assertEquals(listOf(2L), persisted)
    }

    @Test
    fun testStagedItemsSurviveAProcessDeathBetweenScanAndImport() {
        EngineHolder.mockPendingImports.clear()
        var cursor = 1_000L
        // First process: scan stages via the real wrapper, then dies.
        NewMediaIntake.commit(
            startCursor = cursor,
            scanned = listOf(item(7, 1_070L)),
            persist = {
                when (com.branchdam.mobile.service.ImportConfirmationNotifier.stagePendingItem(it)) {
                    EngineHolder.StageResult.STAGED -> Outcome.STAGED
                    EngineHolder.StageResult.SUPPRESSED -> Outcome.ALREADY_DECIDED
                    EngineHolder.StageResult.FAILED -> Outcome.FAILED
                }
            },
            saveCursor = { cursor = it },
        )
        assertEquals(1_070L, cursor)

        // Second process: the item is still there to import.
        val pending = EngineHolder.listPendingImports()
        assertEquals(listOf(item(7, 1_070L).contentUri), pending?.map { it.localId })
        EngineHolder.mockPendingImports.clear()
    }
}
