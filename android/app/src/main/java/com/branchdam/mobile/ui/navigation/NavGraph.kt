package com.branchdam.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.branchdam.mobile.ui.gallery.GalleryScreen
import com.branchdam.mobile.ui.lineage.LineageScreen
import com.branchdam.mobile.ui.safespace.SafeSpaceScreen
import com.branchdam.mobile.ui.settings.SettingsScreen
import com.branchdam.mobile.ui.sync.SyncStatusScreen

@Composable
fun AppNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Lineage.route,
        modifier = modifier,
    ) {
        composable(Screen.Lineage.route) {
            LineageScreen(
                onNavigateToSafeSpace = {
                    navController.navigate(Screen.SafeSpace.route) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Screen.Gallery.route) {
            GalleryScreen()
        }
        composable(Screen.Sync.route) {
            SyncStatusScreen()
        }
        composable(Screen.Settings.route) {
            SettingsScreen()
        }
        composable(Screen.SafeSpace.route) {
            SafeSpaceScreen(
                onNavigateBack = { navController.navigateUp() },
            )
        }
    }
}
