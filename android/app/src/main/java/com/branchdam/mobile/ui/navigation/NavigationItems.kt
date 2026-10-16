package com.branchdam.mobile.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.ui.graphics.vector.ImageVector
import com.branchdam.mobile.R

data class NavigationItem(
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val route: String,
)

val navigationItems = listOf(
    NavigationItem(R.string.nav_lineage, Icons.Filled.AccountTree, Icons.Outlined.AccountTree, Screen.Lineage.route),
    NavigationItem(R.string.nav_gallery, Icons.Filled.PhotoLibrary, Icons.Outlined.PhotoLibrary, Screen.Gallery.route),
    NavigationItem(R.string.nav_sync, Icons.Filled.Sync, Icons.Outlined.Sync, Screen.Sync.route),
    NavigationItem(R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings, Screen.Settings.route),
)

val bottomNavRoutes: Set<String> = navigationItems.map { it.route }.toSet()
