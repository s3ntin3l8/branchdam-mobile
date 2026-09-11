package com.branchdam.mobile.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.branchdam.mobile.ui.gallery.GalleryDetailScreen
import com.branchdam.mobile.ui.gallery.GalleryScreen
import com.branchdam.mobile.ui.gallery.GalleryViewModel
import com.branchdam.mobile.ui.lineage.LineageScreen
import com.branchdam.mobile.ui.onboarding.OnboardingScreen
import com.branchdam.mobile.ui.qrscan.QrScanScreen
import com.branchdam.mobile.ui.safespace.SafeSpaceScreen
import com.branchdam.mobile.ui.settings.SettingsScreen
import com.branchdam.mobile.ui.settings.SettingsViewModel
import com.branchdam.mobile.ui.sync.SyncStatusScreen

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
            GalleryScreen(
                onNavigateToDetail = { mediaId ->
                    navController.navigate(Screen.GalleryDetail.createRoute(mediaId)) {
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(
            route = Screen.GalleryDetail.route,
            arguments = listOf(navArgument("mediaId") { type = NavType.LongType })
        ) { backStackEntry ->
            val mediaId = backStackEntry.arguments?.getLong("mediaId") ?: 0L
            val galleryViewModel: GalleryViewModel = remember(backStackEntry) {
                try {
                    val parentEntry = navController.getBackStackEntry(Screen.Gallery.route)
                    ViewModelProvider(parentEntry)[GalleryViewModel::class.java]
                } catch (_: Exception) {
                    ViewModelProvider(backStackEntry)[GalleryViewModel::class.java]
                }
            }
            GalleryDetailScreen(
                mediaId = mediaId,
                onNavigateBack = { navController.navigateUp() },
                viewModel = galleryViewModel
            )
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
