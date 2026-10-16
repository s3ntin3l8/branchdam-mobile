package com.branchdam.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BindingListPendingImports returns the core's queue.PendingImport rows
 * as JSON. Runs under Robolectric so org.json is the real implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingImportParsingTest {

    @Test
    fun testParsesTheCoreJsonShape() {
        val json = """
            [
              {"localId":"content://media/external/images/media/1","localPath":"/DCIM/Camera/IMG_1.jpg",
               "filename":"IMG_1.jpg","mimeType":"image/jpeg","sizeBytes":2048,"capturedAtUnix":1700000000,
               "state":"PENDING","createdAtUnix":1700000100,"updatedAtUnix":1700000200},
              {"localId":"content://media/external/video/media/2","localPath":"/DCIM/Camera/VID_2.mp4",
               "filename":"VID_2.mp4","sizeBytes":4096,"capturedAtUnix":1700000001,
               "state":"PENDING","createdAtUnix":1700000101,"updatedAtUnix":1700000201}
            ]
        """.trimIndent()

        val items = parsePendingImports(json)

        assertEquals(2, items.size)
        assertEquals(
            PendingImport(
                localId = "content://media/external/images/media/1",
                localPath = "/DCIM/Camera/IMG_1.jpg",
                filename = "IMG_1.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 2048L,
                capturedAtUnix = 1_700_000_000L,
                createdAtUnix = 1_700_000_100L,
            ),
            items[0]
        )
        assertEquals("", items[1].mimeType) // omitempty in the core
    }

    @Test
    fun testEmptyAndIdlessRows() {
        assertTrue(parsePendingImports("[]").isEmpty())
        assertTrue(parsePendingImports("").isEmpty())
        assertTrue(parsePendingImports(null).isEmpty())
        assertTrue(parsePendingImports("""[{"filename":"x.jpg"}]""").isEmpty())
    }
}
