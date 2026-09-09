package com.branchdam.mobile.ui.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.WindowSizeClass
import androidx.compose.material3.adaptive.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class BranchDamNavigationItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val route: String,
)

private val NavigationItems = listOf(
    BranchDamNavigationItem("Lineage", Icons.Filled.AccountTree, Icons.Outlined.AccountTree, Screen.Lineage.route),
    BranchDamNavigationItem("Gallery", Icons.Filled.PhotoLibrary, Icons.Outlined.PhotoLibrary, Screen.Gallery.route),
    BranchDamNavigationItem("Sync", Icons.Filled.Sync, Icons.Outlined.Sync, Screen.Sync.route),
    BranchDamNavigationItem("Settings", Icons.Filled.Settings, Icons.Outlined.Settings, Screen.Settings.route),
)

@Composable
fun NavigationSuiteScaffold(
    windowSizeClass: WindowSizeClass,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val layoutType = when (windowSizeClass.windowWidthSizeClass) {
        WindowWidthSizeClass.COMPACT -> NavigationLayoutType.BottomBar
        WindowWidthSizeClass.MEDIUM -> NavigationLayoutType.Rail
        WindowWidthSizeClass.EXPANDED -> NavigationLayoutType.Drawer
        else -> NavigationLayoutType.BottomBar
    }

    // Only show navigation for bottom nav routes
    val showNavigation = currentRoute in bottomNavRoutes && currentRoute != Screen.Onboarding.route

    if (!showNavigation) {
        content()
        return
    }

    when (layoutType) {
        NavigationLayoutType.BottomBar -> {
            Scaffold(
                modifier = modifier,
                bottomBar = {
                    NavigationBar {
                        NavigationItems.forEach { item ->
                            val isSelected = currentRoute == item.route
                            NavigationBarItem(
                                icon = {
                                    Icon(
                                        imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = null // Label is provided
                                    )
                                },
                                label = { Text(item.label) },
                                selected = isSelected,
                                onClick = { if (!isSelected) onNavigate(item.route) },
                                alwaysShowLabel = true
                            )
                        }
                    }
                }
            ) { padding ->
                Surface(modifier = Modifier.padding(padding)) { content() }
            }
        }
        NavigationLayoutType.Rail -> {
            Row(modifier = modifier.fillMaxSize()) {
                NavigationRail(
                    header = {
                        // Optional header: branding or FAB could go here
                    }
                ) {
                    NavigationItems.forEach { item ->
                        val isSelected = currentRoute == item.route
                        NavigationRailItem(
                            icon = {
                                Icon(
                                    imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = null
                                )
                            },
                            label = { Text(item.label) },
                            selected = isSelected,
                            onClick = { if (!isSelected) onNavigate(item.route) },
                            alwaysShowLabel = true
                        )
                    }
                }
                Surface(modifier = Modifier.weight(1f)) { content() }
            }
        }
        NavigationLayoutType.Drawer -> {
            PermanentNavigationDrawer(
                modifier = modifier,
                drawerContent = {
                    PermanentDrawerSheet(modifier = Modifier.width(240.dp)) {
                        Spacer(Modifier.height(12.dp))
                        NavigationItems.forEach { item ->
                            val isSelected = currentRoute == item.route
                            NavigationDrawerItem(
                                label = { Text(item.label) },
                                icon = {
                                    Icon(
                                        imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = null
                                    )
                                },
                                selected = isSelected,
                                onClick = { if (!isSelected) onNavigate(item.route) },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                            )
                        }
                    }
                }
            ) {
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
    }
}

private enum class NavigationLayoutType {
    BottomBar,
    Rail,
    Drawer
}
