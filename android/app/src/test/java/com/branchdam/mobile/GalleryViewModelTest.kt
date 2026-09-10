package com.branchdam.mobile

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import com.branchdam.mobile.observer.MediaItem
import com.branchdam.mobile.ui.gallery.GalleryItem
import com.branchdam.mobile.ui.gallery.GalleryViewModel
import com.branchdam.mobile.ui.gallery.formatDateTaken
import com.branchdam.mobile.ui.gallery.formatFileSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

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
