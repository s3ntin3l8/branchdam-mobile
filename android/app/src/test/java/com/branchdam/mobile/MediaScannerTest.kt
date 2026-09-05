package com.branchdam.mobile

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.branchdam.mobile.observer.MediaItem
import com.branchdam.mobile.observer.MediaScanner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for the MediaScanner changes in PR #130.
 *
 * The pre-PR shape of `queryMediaUri` interpolated the limit directly
 * into the `sortOrder` string (`... DESC LIMIT $limit`) and passed it
 * to the legacy `ContentResolver.query(uri, projection, selection,
 * selectionArgs, sortOrder)` overload. On Android 14+ that overload
 * is deprecated and the system rejects the unencoded token in the
 * sortOrder string with an `IllegalArgumentException` that surfaced
 * verbatim as the "invalid token limit" red error on Lineage Audit
 * and Gallery. The fix moved the limit into a `Bundle` via
 * `QUERY_ARG_LIMIT` and switched to the API 26+ `query(uri, projection,
 * bundle, cancellationSignal)` overload.
 *
 * Runs under `@RunWith(RobolectricTestRunner::class)` so `Bundle`,
 * `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`, and the
 * `ContentResolver.QUERY_ARG_*` constants are real (not the
 * default-value stubs that the rest of the suite sees via
 * `testOptions.unitTests.isReturnDefaultValues = true`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaScannerTest {

    @Test
    fun testBuildQueryBundle_carriesAllQueryArgKeys() {
        val selection = "date_taken > ?"
        val selectionArgs = arrayOf("12345")
        val sortOrder = "date_taken DESC"
        val limit = 42

        val bundle = MediaScanner.buildQueryBundle(selection, selectionArgs, sortOrder, limit)

        assertEquals(
            "selection must be passed via QUERY_ARG_SQL_SELECTION",
            selection,
            bundle.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
        )
        assertArrayEquals(
            "selection args must be passed via QUERY_ARG_SQL_SELECTION_ARGS",
            selectionArgs,
            bundle.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS),
        )
        assertEquals(
            "sort order must NOT include the limit clause; the limit lives " +
                "in its own QUERY_ARG_LIMIT key",
            "date_taken DESC",
            bundle.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER),
        )
        assertEquals(
            "limit must be passed via QUERY_ARG_LIMIT, not interpolated " +
                "into the sortOrder string",
            limit,
            bundle.getInt(ContentResolver.QUERY_ARG_LIMIT),
        )
    }

    @Test
    fun testBuildQueryBundle_sortOrderHasNoLimitClause() {
        val bundle = MediaScanner.buildQueryBundle(
            selection = "date_taken > ?",
            selectionArgs = arrayOf("0"),
            sortOrder = "date_taken DESC",
            limit = 100,
        )

        val sortOrder = bundle.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER)
        assertNotNull("sort order key must be set", sortOrder)
        assertTrue(
            "sort order must not contain a LIMIT clause; the limit is " +
                "carried in QUERY_ARG_LIMIT. Got: $sortOrder",
            !sortOrder!!.contains("LIMIT", ignoreCase = true),
        )
    }

    @Test
    fun testQueryRecentImages_usesBundleOverload() {
        val resolver: ContentResolver = mock()
        val ctx: Context = mock()
        whenever(ctx.contentResolver).thenReturn(resolver)
        whenever(resolver.query(any<Uri>(), any(), any<Bundle>(), isNull())).thenReturn(null)

        MediaScanner.queryRecentImages(ctx, minDateTakenUnix = 100L, limit = 7)

        val captor = argumentCaptor<Bundle>()
        verify(resolver).query(any<Uri>(), any(), captor.capture(), isNull())
        val bundle = captor.firstValue
        assertEquals(
            "queryRecentImages must request exactly 7 rows",
            7,
            bundle.getInt(ContentResolver.QUERY_ARG_LIMIT),
        )
        assertEquals(
            "queryRecentImages must filter by DATE_TAKEN > 100s",
            "100",
            bundle.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)?.get(0),
        )
    }

    @Test
    fun testQueryRecentVideos_usesBundleOverload() {
        val resolver: ContentResolver = mock()
        val ctx: Context = mock()
        whenever(ctx.contentResolver).thenReturn(resolver)
        whenever(resolver.query(any<Uri>(), any(), any<Bundle>(), isNull())).thenReturn(null)

        MediaScanner.queryRecentVideos(ctx, minDateTakenUnix = 0L, limit = 25)

        val captor = argumentCaptor<Bundle>()
        verify(resolver).query(any<Uri>(), any(), captor.capture(), isNull())
        val bundle = captor.firstValue
        assertEquals(
            "queryRecentVideos must request exactly 25 rows",
            25,
            bundle.getInt(ContentResolver.QUERY_ARG_LIMIT),
        )
    }

    @Test
    fun testQueryRecentImages_returnsEmptyListOnSecurityException() {
        // Cold-launch race: the ViewModel's `init` fires the query
        // before the user has tapped "Allow" on the permission dialog.
        // The pre-PR shape surfaced this as a red "Failed to load media"
        // error. The fix catches SecurityException and returns an empty
        // list so the UI shows the empty-state affordance and the user
        // can refresh once the permission is granted.
        val resolver: ContentResolver = mock()
        val ctx: Context = mock()
        whenever(ctx.contentResolver).thenReturn(resolver)
        whenever(resolver.query(any<Uri>(), any(), any<Bundle>(), isNull()))
            .thenThrow(SecurityException("READ_MEDIA_IMAGES denied"))

        val items = MediaScanner.queryRecentImages(ctx)
        assertEquals(
            "SecurityException must be swallowed and an empty list returned",
            emptyList<MediaItem>(),
            items,
        )
    }

    @Test
    fun testQueryRecentVideos_returnsEmptyListOnSecurityException() {
        val resolver: ContentResolver = mock()
        val ctx: Context = mock()
        whenever(ctx.contentResolver).thenReturn(resolver)
        whenever(resolver.query(any<Uri>(), any(), any<Bundle>(), isNull()))
            .thenThrow(SecurityException("READ_MEDIA_VIDEO denied"))

        val items = MediaScanner.queryRecentVideos(ctx)
        assertEquals(
            "SecurityException must be swallowed and an empty list returned",
            emptyList<MediaItem>(),
            items,
        )
    }
}
