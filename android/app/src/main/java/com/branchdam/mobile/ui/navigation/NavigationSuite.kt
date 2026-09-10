package com.branchdam.mobile.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun NavigationSuiteScaffold(
    windowSizeClass: WindowSizeClass,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val layoutType = when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Compact -> NavigationLayoutType.BottomBar
        WindowWidthSizeClass.Medium -> NavigationLayoutType.Rail
        WindowWidthSizeClass.Expanded -> NavigationLayoutType.Drawer
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
                        navigationItems.forEach { item ->
                            val isSelected = currentRoute == item.route
                            NavigationBarItem(
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
                },
                contentWindowInsets = WindowInsets(0, 0, 0, 0)
            ) { padding ->
                Box(modifier = Modifier.padding(padding)) {
                    content()
                }
            }
        }
        NavigationLayoutType.Rail -> {
            Row(
                modifier = modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical))
            ) {
                NavigationRail {
                    Spacer(Modifier.weight(1f))
                    navigationItems.forEach { item ->
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
                    Spacer(Modifier.weight(1f))
                }
                Box(modifier = Modifier.weight(1f)) {
                    content()
                }
            }
        }
        NavigationLayoutType.Drawer -> {
            PermanentNavigationDrawer(
                modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical)),
                drawerContent = {
                    PermanentDrawerSheet(modifier = Modifier.width(240.dp)) {
                        Spacer(Modifier.height(12.dp))
                        navigationItems.forEach { item ->
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
                Box(modifier = Modifier.fillMaxSize()) {
                    content()
                }
            }
        }
    }
}

private enum class NavigationLayoutType {
    BottomBar,
    Rail,
    Drawer
}
