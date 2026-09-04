package com.branchdam.mobile.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.branchdam.mobile.ui.lineage.LineageScreen

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
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Gallery \u2014 coming soon")
            }
        }
        composable(Screen.Sync.route) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Sync Status \u2014 coming soon")
            }
        }
        composable(Screen.Settings.route) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Settings \u2014 coming soon")
            }
        }
        composable(Screen.SafeSpace.route) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Safe Space \u2014 coming soon")
            }
        }
    }
}
