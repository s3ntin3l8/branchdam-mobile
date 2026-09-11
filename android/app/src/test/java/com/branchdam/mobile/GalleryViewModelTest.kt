package com.branchdam.mobile

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.branchdam.mobile.observer.MediaItem
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
        val item = GalleryItem(localMedia, lineageStatus = "Unpaired", isOffloaded = false)
        viewModel.setItemsForTesting(listOf(item))

        val result = viewModel.getItemById(50L)
        assertNotNull("Item 50 should be retrieved", result)
        assertEquals("PXL_050.jpg", result?.mediaItem?.displayName)

        assertNull("Lookup for non-existent ID should return null", viewModel.getItemById(999L))
    }

    @Test
    fun testSelectAll_excludesOffloadedItems() {
        val viewModel = GalleryViewModel(ApplicationProvider.getApplicationContext())
        val localItem = GalleryItem(
            mediaItem = MediaItem(
                id = 101L, contentUri = "content://images/101",
                filePath = "/sdcard/DCIM/PXL_101.jpg", displayName = "PXL_101.jpg",
                mimeType = "image/jpeg", sizeBytes = 1000L,
                dateTakenUnix = 1724000000L, isRaw = false
            ),
            lineageStatus = "Unpaired",
            isOffloaded = false
        )
        val offloadedItem = GalleryItem(
            mediaItem = MediaItem(
                id = 102L, contentUri = "content://images/102",
                filePath = "/sdcard/DCIM/PXL_102.jpg", displayName = "PXL_102.jpg",
                mimeType = "image/jpeg", sizeBytes = 1000L,
                dateTakenUnix = 1724000000L, isRaw = false
            ),
            lineageStatus = "Unpaired",
            isOffloaded = true
        )

        viewModel.setItemsForTesting(listOf(localItem, offloadedItem))
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
            mediaItem = MediaItem(
                id = 201L, contentUri = "content://images/201",
                filePath = "/sdcard/DCIM/PXL_201.jpg", displayName = "PXL_201.jpg",
                mimeType = "image/jpeg", sizeBytes = 1000L,
                dateTakenUnix = 1724000000L, isRaw = false
            ),
            lineageStatus = "Unpaired",
            isOffloaded = false
        )
        val offloadedItem = GalleryItem(
            mediaItem = MediaItem(
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
}
