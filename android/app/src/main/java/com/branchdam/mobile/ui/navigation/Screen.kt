package com.branchdam.mobile.ui.navigation

sealed class Screen(val route: String) {
    data object Lineage : Screen("lineage")
    data object Gallery : Screen("gallery")
    data object GalleryDetail : Screen("gallery_detail/{mediaId}") {
        fun createRoute(mediaId: Long): String = "gallery_detail/$mediaId"
    }
    data object Sync : Screen("sync")
    data object Settings : Screen("settings")
    data object SafeSpace : Screen("safespace")
    data object QrScan : Screen("qrcode")
    data object Onboarding : Screen("onboarding")
}
