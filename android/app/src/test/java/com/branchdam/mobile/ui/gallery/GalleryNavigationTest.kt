package com.branchdam.mobile.ui.gallery

import com.branchdam.mobile.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryNavigationTest {

    @Test
    fun testGalleryDetailRouteFormatting() {
        assertEquals("gallery_detail/{mediaId}", Screen.GalleryDetail.route)
        assertEquals("gallery_detail/12345", Screen.GalleryDetail.createRoute(12345L))
        assertEquals("gallery_detail/0", Screen.GalleryDetail.createRoute(0L))
    }
}
