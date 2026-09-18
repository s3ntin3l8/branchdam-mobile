package com.branchdam.mobile

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.branchdam.mobile.ui.components.RawPreviewFetcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RawPreviewFetcherTest {

    @Test
    fun testIsRawUri_DngExtension() {
        val context = mock<Context>()
        val uri = Uri.parse("content://media/external/images/media/1001/PXL_20260829_001.dng")
        assertTrue(RawPreviewFetcher.isRawUri(context, uri))
    }

    @Test
    fun testIsRawUri_UppercaseDng() {
        val context = mock<Context>()
        val uri = Uri.parse("file:///storage/emulated/0/DCIM/Camera/IMG_0001.DNG")
        assertTrue(RawPreviewFetcher.isRawUri(context, uri))
    }

    @Test
    fun testIsRawUri_MimeTypeRaw() {
        val context = mock<Context>()
        val resolver = mock<ContentResolver>()
        val uri = Uri.parse("content://media/external/images/media/1002")
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(resolver.getType(uri)).thenReturn("image/x-adobe-dng")

        assertTrue(RawPreviewFetcher.isRawUri(context, uri))
    }

    @Test
    fun testIsRawUri_JpegReturnsFalse() {
        val context = mock<Context>()
        val resolver = mock<ContentResolver>()
        val uri = Uri.parse("content://media/external/images/media/1003/PXL_20260829_001.jpg")
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(resolver.getType(uri)).thenReturn("image/jpeg")

        assertFalse(RawPreviewFetcher.isRawUri(context, uri))
    }
}
