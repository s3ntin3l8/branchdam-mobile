package com.branchdam.mobile.ui.navigation

sealed class Screen(val route: String) {
    data object Lineage : Screen("lineage")
    data object Gallery : Screen("gallery")
    data object Sync : Screen("sync")
    data object Settings : Screen("settings")
    data object SafeSpace : Screen("safespace")
}
