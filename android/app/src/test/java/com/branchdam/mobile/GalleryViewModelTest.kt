import android.content.ContentResolver
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.branchdam.mobile.BranchDamKeys
import com.branchdam.mobile.observer.MediaItem
import com.branchdam.mobile.triage.MediaStoreDeleter
import com.branchdam.mobile.ui.gallery.GalleryItem
import com.branchdam.mobile.ui.gallery.GalleryViewModel
import com.branchdam.mobile.ui.gallery.formatDateTaken
import com.branchdam.mobile.ui.gallery.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        GalleryViewModel.ioDispatcher = testDispatcher
        GalleryViewModel.defaultDispatcher = testDispatcher

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        GalleryViewModel.ioDispatcher = Dispatchers.IO
        GalleryViewModel.defaultDispatcher = Dispatchers.Default
        GalleryViewModel.deleterFactory = { MediaStoreDeleter.forContext(it) }
    }

    @Test
    fun testSelectionStateAndToggle() {
        val viewModel = GalleryViewModel(ApplicationProvider.getApplicationContext())

        assertTrue("Initially selectedItemIds should be empty", viewModel.selectedItemIds.value.isEmpty())

        viewModel.toggleSelection(101L)
        assertTrue("Item 101 should be selected", viewModel.selectedItemIds.value.contains(101L))
        assertEquals(1, viewModel.selectedItemIds.value.size)

        viewModel.toggleSelection(102L)
        assertTrue("Item 102 should be selected", viewModel.selectedItemIds.value.contains(102L))
        assertEquals(2, viewModel.selectedItemIds.value.size)

        viewModel.toggleSelection(101L)
        assertFalse("Item 101 should be unselected", viewModel.selectedItemIds.value.contains(101L))
        assertEquals(1, viewModel.selectedItemIds.value.size)

        viewModel.clearSelection()
        assertTrue("Selection should be cleared", viewModel.selectedItemIds.value.isEmpty())
    }

    @Test
    fun testGetItemById() {
        val viewModel = GalleryViewModel(ApplicationProvider.getApplicationContext())
        val localMedia = MediaItem(
            id = 50L, contentUri = "content://images/50",
            filePath = "/sdcard/DCIM/PXL_050.jpg", displayName = "PXL_050.jpg",
            mimeType = "image/jpeg", sizeBytes = 4_000_000L,
            dateTakenUnix = 1724000000L, isRaw = false,
        )
        val item = GalleryItem(primaryMediaItem = localMedia, lineageStatus = "Unpaired", isOffloaded = false)
        viewModel.setItemsForTesting(listOf(item))

        val result = viewModel.getItemById(50L)
        assertNotNull("Item 50 should be retrieved", result)
        assertEquals("PXL_050.jpg", result?.mediaItem?.displayName)

        assertNull("Lookup for non-existent ID should return null", viewModel.getItemById(999L))
    }

    @Test
    fun testSelectAll_excludesOffloadedItems() = runTest(testDispatcher) {
        val viewModel = GalleryViewModel(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()

        val localItem = GalleryItem(
            primaryMediaItem = MediaItem(
                id = 101L, contentUri = "content://images/101",
                filePath = "/sdcard/DCIM/PXL_101.jpg", displayName = "PXL_101.jpg",
                mimeType = "image/jpeg", sizeBytes = 1000L,
                dateTakenUnix = 1724000000L, isRaw = false
            ),
            lineageStatus = "Unpaired",
            isOffloaded = false
        )
        val offloadedItem = GalleryItem(
            primaryMediaItem = MediaItem(
                id = 102L, contentUri = "content://images/102",
                filePath = "/sdcard/DCIM/PXL_102.jpg", displayName = "PXL_102.jpg",
                mimeType = "image/jpeg", sizeBytes = 1000L,
                dateTakenUnix = 1724000000L, isRaw = false
            ),
            lineageStatus = "Unpaired",
            isOffloaded = true
        )

        viewModel.setItemsForTesting(listOf(localItem, offloadedItem))
        advanceUntilIdle()

        viewModel.selectAll()

        val selected = viewModel.selectedItemIds.value
        assertTrue("Local item 101 must be selected by selectAll()", selected.contains(101L))
        assertFalse("Offloaded item 102 must NOT be selected by selectAll()", selected.contains(102L))
        assertEquals("Only 1 non-offloaded item should be selected", 1, selected.size)
    }

    @Test
    fun testUploadSelectedItems_filtersOutOffloadedItems() = runTest(testDispatcher) {
        val viewModel = GalleryViewModel(ApplicationProvider.getApplicationContext())
        val localItem = GalleryItem(
            primaryMediaItem = MediaItem(
                id = 201L, contentUri = "content://images/201",
                filePath = "/sdcard/DCIM/PXL_201.jpg", displayName = "PXL_201.jpg",
                mimeType = "image/jpeg", sizeBytes = 1000L,
                dateTakenUnix = 1724000000L, isRaw = false
            ),
            lineageStatus = "Unpaired",
            isOffloaded = false
        )
        val offloadedItem = GalleryItem(
            primaryMediaItem = MediaItem(
                id = 202L, contentUri = "content://images/202",
                filePath = "/sdcard/DCIM/PXL_202.jpg", displayName = "PXL_202.jpg",
                mimeType = "image/jpeg", sizeBytes = 1000L,
                dateTakenUnix = 1724000000L, isRaw = false
            ),
            lineageStatus = "Unpaired",
            isOffloaded = true
        )

        viewModel.setItemsForTesting(listOf(localItem, offloadedItem))
        viewModel.toggleSelection(201L)
        viewModel.toggleSelection(202L)
        assertEquals(2, viewModel.selectedItemIds.value.size)

        var enqueuedCount = -1
        viewModel.uploadSelectedItems(ApplicationProvider.getApplicationContext()) { count ->
            enqueuedCount = count
        }
        advanceUntilIdle()

        assertEquals("Only non-offloaded item should be enqueued for upload", 1, enqueuedCount)
        assertTrue("Selection should be cleared after upload", viewModel.selectedItemIds.value.isEmpty())
    }

    @Test
    fun testUploadSelectedItems_emptySelectionDoesNothing() = runTest(testDispatcher) {
        val viewModel = GalleryViewModel(ApplicationProvider.getApplicationContext())
        var callbackCount = -1
        viewModel.uploadSelectedItems(ApplicationProvider.getApplicationContext()) { count ->
            callbackCount = count
        }
        advanceUntilIdle()

        assertEquals("Empty selection should return 0", 0, callbackCount)
        assertTrue("Selection should remain empty", viewModel.selectedItemIds.value.isEmpty())
    }

    @Test
    fun testGalleryItem_backupStatusProperties() {
        val notEnqueued = GalleryItem(
            primaryMediaItem = MediaItem(1L, "uri1", "path1", "name1", "image/jpeg", 100L, 1000L, false),
            lineageStatus = "Unpaired",
            backupStatus = "NOT_ENQUEUED"
        )
        assertFalse(notEnqueued.isBackedUp)
        assertFalse(notEnqueued.isPendingUpload)
        assertFalse(notEnqueued.isUploadFailed)

        val pending = notEnqueued.copy(backupStatus = "PENDING")
        assertFalse(pending.isBackedUp)
        assertTrue(pending.isPendingUpload)
        assertFalse(pending.isUploadFailed)

        val completed = notEnqueued.copy(backupStatus = "COMPLETED")
        assertTrue(completed.isBackedUp)
        assertFalse(completed.isPendingUpload)
        assertFalse(completed.isUploadFailed)

        val offloaded = notEnqueued.copy(isOffloaded = true, backupStatus = "OFFLOADED")
        assertTrue(offloaded.isBackedUp)
        assertFalse(offloaded.isPendingUpload)
        assertFalse(offloaded.isUploadFailed)

        val failed = notEnqueued.copy(backupStatus = "FAILED")
        assertFalse(failed.isBackedUp)
        assertFalse(failed.isPendingUpload)
        assertTrue(failed.isUploadFailed)
    }

    @Test
    fun testFormatFileSize() {
        assertEquals("0 B", formatFileSize(0L))
        assertEquals("0 B", formatFileSize(-10L))
        assertEquals("500.0 B", formatFileSize(500L))
        assertEquals("1.5 KB", formatFileSize(1536L))
        assertEquals("10.0 MB", formatFileSize(10_485_760L))
        assertEquals("2.5 GB", formatFileSize(2_684_354_560L))
    }

    @Test
    fun testFormatDateTaken() {
        assertEquals("Unknown", formatDateTaken(0L))
        assertEquals("Unknown", formatDateTaken(-1L))

        val formatted = formatDateTaken(1724000000L)
        assertNotNull(formatted)
        assertTrue("Date should start with 2024", formatted.startsWith("2024"))
    }

    @Test
    fun testFolderNamedAllFoldersIsAnOrdinaryFolder() = runTest(testDispatcher) {
        val viewModel = GalleryViewModel(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()

        fun item(id: Long, folder: String) = GalleryItem(
            primaryMediaItem = MediaItem(
                id = id, contentUri = "content://images/$id",
                filePath = "/sdcard/$folder/IMG_$id.jpg", displayName = "IMG_$id.jpg",
                mimeType = "image/jpeg", sizeBytes = 1_000L,
                dateTakenUnix = id, isRaw = false, folderName = folder
            )
        )
        viewModel.setItemsForTesting(listOf(item(1L, "All Folders"), item(2L, "Camera")))
        advanceUntilIdle()

        // A real folder with that name filters to itself, not to everything.
        viewModel.setFolder("All Folders")
        advanceUntilIdle()
        assertEquals(listOf("IMG_1.jpg"), viewModel.displayedItems.value.map { it.mediaItem.displayName })

        viewModel.setFolder(com.branchdam.mobile.ui.gallery.ALL_FOLDERS)
        advanceUntilIdle()
        assertEquals(2, viewModel.displayedItems.value.size)
    }

    private fun folderItem(id: Long, folder: String) = GalleryItem(
        primaryMediaItem = MediaItem(
            id = id, contentUri = "content://images/$id",
            filePath = "/sdcard/$folder/IMG_$id.jpg", displayName = "IMG_$id.jpg",
            mimeType = "image/jpeg", sizeBytes = 1_000L,
            dateTakenUnix = id, isRaw = false, folderName = folder
        )
    )

    private fun galleryPrefs() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences(BranchDamKeys.PREFS_NAME, Context.MODE_PRIVATE)

    @Test
    fun testFolderNamedNoneIsShownWhenIncluded() = runTest(testDispatcher) {
        galleryPrefs().edit()
            .putStringSet(BranchDamKeys.INCLUDED_GALLERY_FOLDERS, setOf("__NONE__"))
            .putBoolean(BranchDamKeys.NO_GALLERY_FOLDERS_SELECTED, false)
            .commit()

        val viewModel = GalleryViewModel(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()
        viewModel.setItemsForTesting(listOf(folderItem(1L, "__NONE__"), folderItem(2L, "Camera")))
        advanceUntilIdle()

        assertFalse(viewModel.noFoldersSelected.value)
        assertEquals(listOf("IMG_1.jpg"), viewModel.displayedItems.value.map { it.mediaItem.displayName })
    }

    @Test
    fun testNoFoldersSelectedShowsNothing() = runTest(testDispatcher) {
        galleryPrefs().edit()
            .putStringSet(BranchDamKeys.INCLUDED_GALLERY_FOLDERS, emptySet())
            .putBoolean(BranchDamKeys.NO_GALLERY_FOLDERS_SELECTED, true)
            .commit()

        val viewModel = GalleryViewModel(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()
        viewModel.setItemsForTesting(listOf(folderItem(1L, "__NONE__"), folderItem(2L, "Camera")))
        advanceUntilIdle()

        assertTrue(viewModel.noFoldersSelected.value)
        assertTrue(viewModel.displayedItems.value.isEmpty())
    }

    @Test
    fun testLegacyNoneSentinelMigratesOnLoad() = runTest(testDispatcher) {
        val prefs = galleryPrefs()
        prefs.edit().putStringSet(BranchDamKeys.INCLUDED_GALLERY_FOLDERS, setOf("__NONE__")).commit()

        val viewModel = GalleryViewModel(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()
        viewModel.setItemsForTesting(listOf(folderItem(1L, "__NONE__"), folderItem(2L, "Camera")))
        advanceUntilIdle()

        assertTrue(viewModel.noFoldersSelected.value)
        assertEquals(emptySet<String>(), viewModel.includedFolders.value)
        assertTrue(viewModel.displayedItems.value.isEmpty())
        assertTrue(prefs.getBoolean(BranchDamKeys.NO_GALLERY_FOLDERS_SELECTED, false))
        assertEquals(emptySet<String>(), prefs.getStringSet(BranchDamKeys.INCLUDED_GALLERY_FOLDERS, null))
    }

    @Test
    fun testFilterAndSortAndFolder() = runTest(testDispatcher) {
        val viewModel = GalleryViewModel(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()

        val photo1 = GalleryItem(
            primaryMediaItem = MediaItem(
                id = 1L, contentUri = "content://images/1",
                filePath = "/sdcard/DCIM/PXL_A.jpg", displayName = "PXL_A.jpg",
                mimeType = "image/jpeg", sizeBytes = 2_000_000L,
                dateTakenUnix = 1000L, isRaw = false, folderName = "Camera"
            )
        )
        val video1 = GalleryItem(
            primaryMediaItem = MediaItem(
                id = 2L, contentUri = "content://videos/2",
                filePath = "/sdcard/Movies/PXL_B.mp4", displayName = "PXL_B.mp4",
                mimeType = "video/mp4", sizeBytes = 10_000_000L,
                dateTakenUnix = 2000L, isRaw = false, folderName = "Movies"
            )
        )
        val rawItem = GalleryItem(
            primaryMediaItem = MediaItem(
                id = 3L, contentUri = "content://images/3",
                filePath = "/sdcard/DCIM/PXL_C.dng", displayName = "PXL_C.dng",
                mimeType = "image/x-adobe-dng", sizeBytes = 25_000_000L,
                dateTakenUnix = 1500L, isRaw = true, folderName = "Camera"
            ),
            lineageStatus = "RAW"
        )

        viewModel.setItemsForTesting(listOf(photo1, video1, rawItem))
        advanceUntilIdle()

        // Default: ALL, ALL_FOLDERS, DATE DESC
        var displayed = viewModel.displayedItems.value
        assertEquals(3, displayed.size)
        assertEquals("PXL_B.mp4", displayed[0].mediaItem.displayName) // 2000L
        assertEquals("PXL_C.dng", displayed[1].mediaItem.displayName) // 1500L
        assertEquals("PXL_A.jpg", displayed[2].mediaItem.displayName) // 1000L

        // Filter: VIDEOS
        viewModel.setFilter(com.branchdam.mobile.ui.gallery.GalleryFilter.VIDEOS)
        advanceUntilIdle()
        displayed = viewModel.displayedItems.value
        assertEquals(1, displayed.size)
        assertEquals("PXL_B.mp4", displayed[0].mediaItem.displayName)

        // Filter: RAW
        viewModel.setFilter(com.branchdam.mobile.ui.gallery.GalleryFilter.RAW)
        advanceUntilIdle()
        displayed = viewModel.displayedItems.value
        assertEquals(1, displayed.size)
        assertEquals("PXL_C.dng", displayed[0].mediaItem.displayName)

        // Filter: ALL, Folder: Movies
        viewModel.setFilter(com.branchdam.mobile.ui.gallery.GalleryFilter.ALL)
        viewModel.setFolder("Movies")
        advanceUntilIdle()
        displayed = viewModel.displayedItems.value
        assertEquals(1, displayed.size)
        assertEquals("PXL_B.mp4", displayed[0].mediaItem.displayName)

        // Sort by SIZE DESC
        viewModel.setFolder(com.branchdam.mobile.ui.gallery.ALL_FOLDERS)
        viewModel.setSort(com.branchdam.mobile.ui.gallery.GallerySortProperty.SIZE, com.branchdam.mobile.ui.gallery.GallerySortDirection.DESC)
        advanceUntilIdle()
        displayed = viewModel.displayedItems.value
        assertEquals("PXL_C.dng", displayed[0].mediaItem.displayName) // 25MB
        assertEquals("PXL_B.mp4", displayed[1].mediaItem.displayName) // 10MB
        assertEquals("PXL_A.jpg", displayed[2].mediaItem.displayName) // 2MB
    }

    @Test
    fun testDeleteItemAndSelectedItems() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = GalleryViewModel(app)
        advanceUntilIdle()

        val item1 = GalleryItem(
            primaryMediaItem = MediaItem(
                id = 10L, contentUri = "content://images/10",
                filePath = "/sdcard/DCIM/PXL_10.jpg", displayName = "PXL_10.jpg",
                mimeType = "image/jpeg", sizeBytes = 1000L,
                dateTakenUnix = 1000L, isRaw = false
            )
        )
        val item2 = GalleryItem(
            primaryMediaItem = MediaItem(
                id = 20L, contentUri = "content://images/20",
                filePath = "/sdcard/DCIM/PXL_20.jpg", displayName = "PXL_20.jpg",
                mimeType = "image/jpeg", sizeBytes = 1000L,
                dateTakenUnix = 2000L, isRaw = false
            )
        )

        viewModel.setItemsForTesting(listOf(item1, item2))
        advanceUntilIdle()
        assertEquals(2, viewModel.items.value.size)

        // API 28 path: legacy storage deletes directly through the
        // ContentResolver, no system confirmation.
        GalleryViewModel.deleterFactory = { ctx -> MediaStoreDeleter.forContext(ctx, sdkInt = 28) }

        // Test failed delete: contentResolver returns 0
        val failResolver = mock<ContentResolver>()
        whenever(failResolver.delete(eq(Uri.parse("content://images/10")), anyOrNull(), anyOrNull())).thenReturn(0)
        val failContext = mock<Context>()
        whenever(failContext.contentResolver).thenReturn(failResolver)

        var deleteSuccess = true
        viewModel.deleteItem(failContext, item1) { success ->
            deleteSuccess = success
        }
        advanceUntilIdle()

        assertFalse("Delete should fail when contentResolver returns 0 rows", deleteSuccess)
        assertEquals("Item should be restored to items list when delete fails", 2, viewModel.items.value.size)

        // Test successful delete: contentResolver returns 1
        val successResolver = mock<ContentResolver>()
        whenever(successResolver.delete(eq(Uri.parse("content://images/10")), anyOrNull(), anyOrNull())).thenReturn(1)
        val successContext = mock<Context>()
        whenever(successContext.contentResolver).thenReturn(successResolver)

        viewModel.deleteItem(successContext, item1) { success ->
            deleteSuccess = success
        }
        advanceUntilIdle()

        assertTrue(deleteSuccess)
        val remaining = viewModel.items.value
        assertEquals(1, remaining.size)
        assertEquals(20L, remaining[0].primaryMediaItem.id)
    }

    @Test
    fun testDeleteOnApi30WaitsForTheSystemConfirmation() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val sender: IntentSender = PendingIntent.getActivity(
            app, 0, Intent("test.CONFIRM_DELETE"), PendingIntent.FLAG_IMMUTABLE,
        ).intentSender
        val requested = mutableListOf<List<String>>()
        GalleryViewModel.deleterFactory = {
            MediaStoreDeleter(
                sdkInt = 30,
                directDelete = { error("API 30+ must not call ContentResolver.delete") },
                createDeleteRequest = { uris ->
                    requested += uris
                    sender
                },
            )
        }
        val viewModel = GalleryViewModel(app)
        advanceUntilIdle()

        val raw = MediaItem(
            id = 30L, contentUri = "content://images/30",
            filePath = "/sdcard/DCIM/PXL_30.dng", displayName = "PXL_30.dng",
            mimeType = "image/x-adobe-dng", sizeBytes = 1000L,
            dateTakenUnix = 1000L, isRaw = true
        )
        val pair = GalleryItem(
            primaryMediaItem = MediaItem(
                id = 31L, contentUri = "content://images/31",
                filePath = "/sdcard/DCIM/PXL_30.jpg", displayName = "PXL_30.jpg",
                mimeType = "image/jpeg", sizeBytes = 1000L,
                dateTakenUnix = 1000L, isRaw = false
            ),
            companionMediaItem = raw,
        )
        viewModel.setItemsForTesting(listOf(pair))
        advanceUntilIdle()

        // 1. The user declines the system dialog: nothing is deleted and
        //    the item comes back.
        var result: Boolean? = null
        viewModel.deleteItem(app, pair) { result = it }
        advanceUntilIdle()

        assertEquals(listOf(listOf("content://images/31", "content://images/30")), requested)
        val pending = viewModel.pendingDeleteConsent.value
        assertNotNull("API 30+ deletes must wait for the user", pending)
        assertNull("the outcome is unknown until the user answers", result)
        assertTrue(viewModel.items.value.isEmpty())

        assertTrue(viewModel.onDeleteConsentLaunched(pending!!.id))
        viewModel.onDeleteConsentResult(approved = false)
        advanceUntilIdle()

        assertEquals(false, result)
        assertNull(viewModel.pendingDeleteConsent.value)
        assertEquals(listOf(pair), viewModel.items.value)

        // 2. The user confirms: the system deleted both files.
        viewModel.deleteItem(app, pair) { result = it }
        advanceUntilIdle()
        viewModel.onDeleteConsentResult(approved = true)
        advanceUntilIdle()

        assertEquals(true, result)
        assertTrue(viewModel.items.value.isEmpty())
    }
}
