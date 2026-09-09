package com.branchdam.mobile.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.branchdam.mobile.ui.PairingConfig
import com.branchdam.mobile.ui.gallery.GalleryScreen
import com.branchdam.mobile.ui.lineage.LineageScreen
import com.branchdam.mobile.ui.onboarding.OnboardingScreen
import com.branchdam.mobile.ui.qrscan.QrScanScreen
import com.branchdam.mobile.ui.safespace.SafeSpaceScreen
import com.branchdam.mobile.ui.settings.SettingsScreen
import com.branchdam.mobile.ui.settings.SettingsViewModel
import com.branchdam.mobile.ui.sync.SyncStatusScreen

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AppNavGraph(
    navController: NavHostController,
    windowSizeClass: WindowSizeClass,
    startDestination: String = Screen.Lineage.route,
    onRequestPermissions: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // SettingsViewModel is scoped to the activity (top-level
    // viewModel() call) so that fields populated by the QR scan
    // flow are visible on the Settings screen after pop.
    val settingsViewModel: SettingsViewModel = viewModel()

    val routes = listOf(
        Screen.Lineage.route,
        Screen.Gallery.route,
        Screen.Sync.route,
        Screen.Settings.route
    )

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier.fillMaxSize(),
        enterTransition = {
            val initialRoute = initialState.destination.route
            val targetRoute = targetState.destination.route
            val initialIndex = routes.indexOf(initialRoute)
            val targetIndex = routes.indexOf(targetRoute)

            if (initialIndex != -1 && targetIndex != -1) {
                if (targetIndex > initialIndex) {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300)
                    ) + fadeIn(animationSpec = tween(300))
                } else {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300)
                    ) + fadeIn(animationSpec = tween(300))
                }
            } else {
                fadeIn(animationSpec = tween(300))
            }
        },
        exitTransition = {
            val initialRoute = initialState.destination.route
            val targetRoute = targetState.destination.route
            val initialIndex = routes.indexOf(initialRoute)
            val targetIndex = routes.indexOf(targetRoute)

            if (initialIndex != -1 && targetIndex != -1) {
                if (targetIndex > initialIndex) {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300)
                    ) + fadeOut(animationSpec = tween(300))
                } else {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300)
                    ) + fadeOut(animationSpec = tween(300))
                }
            } else {
                fadeOut(animationSpec = tween(300))
            }
        },
        popEnterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(300)
            ) + fadeIn(animationSpec = tween(300))
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(300)
            ) + fadeOut(animationSpec = tween(300))
        }
    ) {
        composable(Screen.Lineage.route) {
            LineageScreen(
                windowSizeClass = windowSizeClass,
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
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onOnboardingComplete = {
                    navController.navigate(Screen.Lineage.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                },
                onRequestPermissions = onRequestPermissions
            )
        }
    }
}
