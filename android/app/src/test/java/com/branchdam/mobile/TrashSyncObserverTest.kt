package com.branchdam.mobile

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.branchdam.mobile.triage.ProcessedTrashStore
import com.branchdam.mobile.triage.TrashSyncObserver
import com.branchdam.mobile.triage.TrashedMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrashSyncObserverTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("trash_sync_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun trashed(collection: Uri, id: Long) =
        TrashedMediaItem(id, "$collection/$id", "item_$id", isTrashed = true)

    @Test
    fun testTrashedMediaItemModel() {
        val item = TrashedMediaItem(
            id = 501L,
            contentUri = "content://media/external/images/media/501",
            displayName = "PXL_20260829_TRASH.jpg",
            isTrashed = true
        )

        assertTrue(item.isTrashed)
        assertEquals(501L, item.id)
        assertEquals("PXL_20260829_TRASH.jpg", item.displayName)
    }

    @Test
    fun testOffloadSuppressionLogic() {
        // EngineHolder (without a real engine) returns false by default
        // for unknown IDs — the mock fallback path.
        assertFalse(EngineHolder.isMediaOffloaded("content://media/external/images/media/unknown"))
    }

    @Test
    fun testBothImageAndVideoCollectionsAreSynced() {
        assertEquals(
            listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ),
            TrashSyncObserver.TRASH_COLLECTIONS
        )
    }

    @Test
    fun testQueryBuildsEachLocalIdFromItsOwnCollection() {
        val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val videos = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val resolver: ContentResolver = mock()
        val ctx: Context = mock()
        whenever(ctx.contentResolver).thenReturn(resolver)
        whenever(resolver.query(eq(images), any(), any<Bundle>(), isNull())).thenReturn(trashCursor(11L))
        whenever(resolver.query(eq(videos), any(), any<Bundle>(), isNull())).thenReturn(trashCursor(22L))

        val bin = TrashSyncObserver.queryTrashedBin(ctx)

        assertTrue(bin.complete)
        assertEquals(
            listOf("$images/11", "$videos/22"),
            bin.items.map { it.contentUri }
        )
    }

    @Test
    fun testFailedCollectionMarksTheBinIncomplete() {
        val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val videos = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val resolver: ContentResolver = mock()
        val ctx: Context = mock()
        whenever(ctx.contentResolver).thenReturn(resolver)
        whenever(resolver.query(eq(images), any(), any<Bundle>(), isNull())).thenReturn(trashCursor(11L))
        whenever(resolver.query(eq(videos), any(), any<Bundle>(), isNull()))
            .thenThrow(SecurityException("READ_MEDIA_VIDEO denied"))

        val bin = TrashSyncObserver.queryTrashedBin(ctx)

        assertFalse(bin.complete)
        assertEquals(listOf("$images/11"), bin.items.map { it.contentUri })
    }

    @Test
    fun testProcessedItemsArePersistedAcrossRestarts() {
        val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val videos = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val bin = listOf(trashed(images, 1), trashed(videos, 2))
        val enqueued = mutableListOf<String>()

        val first = TrashSyncObserver.processNewlyTrashed(
            items = bin,
            processed = ProcessedTrashStore(prefs),
            isOffloaded = { false },
            enqueueDelete = { enqueued += it; true },
        )
        assertEquals(2, first)

        // A new process: a fresh store over the same preferences.
        val second = TrashSyncObserver.processNewlyTrashed(
            items = bin,
            processed = ProcessedTrashStore(prefs),
            isOffloaded = { false },
            enqueueDelete = { enqueued += it; true },
        )
        assertEquals("a restart must not re-send deletes for the same bin", 0, second)
        assertEquals(listOf("$images/1", "$videos/2"), enqueued)
    }

    @Test
    fun testOffloadedItemsAreRecordedWithoutADeleteEvent() {
        val store = ProcessedTrashStore(prefs)
        val item = trashed(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 5)

        val count = TrashSyncObserver.processNewlyTrashed(
            items = listOf(item),
            processed = store,
            isOffloaded = { true },
            enqueueDelete = { error("offloaded media must not emit a delete") },
        )

        assertEquals(0, count)
        assertTrue(item.contentUri in store)
    }

    @Test
    fun testStoreDropsEntriesThatLeftTheBin() {
        val store = ProcessedTrashStore(prefs)
        store.add("content://a/1")
        store.add("content://a/2")

        store.retainOnly(setOf("content://a/2"))

        val reloaded = ProcessedTrashStore(prefs)
        assertFalse("content://a/1" in reloaded)
        assertTrue("content://a/2" in reloaded)
    }

    @Test
    fun testStoreIsBounded() {
        val store = ProcessedTrashStore(prefs, maxEntries = 3)
        for (i in 1..5) store.add("content://a/$i")

        val reloaded = ProcessedTrashStore(prefs, maxEntries = 3)
        assertEquals(
            "only the newest entries are kept",
            setOf("content://a/3", "content://a/4", "content://a/5"),
            reloaded.snapshot()
        )
    }

    private fun trashCursor(vararg ids: Long): MatrixCursor {
        val cursor = MatrixCursor(
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.IS_TRASHED,
            )
        )
        for (id in ids) cursor.addRow(arrayOf<Any>(id, "item_$id", 1))
        return cursor
    }
}
