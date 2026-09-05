package com.branchdam.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.branchdam.mobile.ui.PairingConfig
import com.branchdam.mobile.ui.gallery.GalleryScreen
import com.branchdam.mobile.ui.lineage.LineageScreen
import com.branchdam.mobile.ui.qrscan.QrScanScreen
import com.branchdam.mobile.ui.safespace.SafeSpaceScreen
import com.branchdam.mobile.ui.settings.SettingsScreen
import com.branchdam.mobile.ui.settings.SettingsViewModel
import com.branchdam.mobile.ui.sync.SyncStatusScreen

@Composable
fun AppNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    // SettingsViewModel is scoped to the activity (top-level
    // viewModel() call) so that fields populated by the QR scan
    // flow are visible on the Settings screen after pop.
    val settingsViewModel: SettingsViewModel = viewModel()
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
            SettingsScreen(
                onScanQr = {
                    navController.navigate(Screen.QrScan.route) {
                        launchSingleTop = true
                    }
                },
                viewModel = settingsViewModel,
            )
        }
        composable(Screen.SafeSpace.route) {
            SafeSpaceScreen(
                onNavigateBack = { navController.navigateUp() },
            )
        }
        composable(Screen.QrScan.route) {
            QrScanScreen(
                onNavigateBack = { navController.navigateUp() },
                onConfigApplied = { config ->
                    settingsViewModel.applyPairingConfig(config)
                    navController.navigateUp()
                },
            )
        }
    }
}
