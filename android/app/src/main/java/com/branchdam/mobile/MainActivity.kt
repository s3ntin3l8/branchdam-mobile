package com.branchdam.mobile

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.branchdam.mobile.otg.OtgIngestManager
import com.branchdam.mobile.otg.OtgState
import com.branchdam.mobile.ui.OtgCardAccessDialog
import com.branchdam.mobile.ui.OtgImportConfirmationDialog
import com.branchdam.mobile.ui.OtgIngestCompletedDialog
import com.branchdam.mobile.ui.OtgIngestErrorDialog
import com.branchdam.mobile.ui.OtgIngestProgressDialog
import com.branchdam.mobile.ui.rememberOtgCardFolderPicker
import com.branchdam.mobile.ui.navigation.AppNavGraph
import com.branchdam.mobile.ui.navigation.NavigationSuiteScaffold
import com.branchdam.mobile.ui.navigation.Screen
import com.branchdam.mobile.ui.navigation.bottomNavRoutes
import com.branchdam.mobile.ui.settings.SettingsViewModel
import com.branchdam.mobile.ui.theme.BranchDamTheme
import com.branchdam.mobile.ui.theme.ThemeMode
import com.branchdam.mobile.ui.theme.ThemePreferences

/**
 * Drives the permission request state machine. Lives in a top-level
 * Composable so unit tests can call [nextPermissionAction] directly
 * with the same parameters and inspect state transitions without
 * instantiating a Compose runtime.
 *
 * The [LaunchedEffect] re-runs only when [batch] changes. The state
 * machine reaches [PermissionBatch.DONE] on its own once every
 * permission is granted (or after the media-batch callback fires),
 * so the loop is bounded regardless of how many times the user denies.
 */
@Composable
internal fun DrivePermissionFlow(
    flow: PermissionFlowState,
    notificationsBatch: List<String>,
    mediaBatch: List<String>,
    hasPermission: (String) -> Boolean,
    launchNotifications: (Array<String>) -> Unit,
    launchMedia: (Array<String>) -> Unit,
) {
    val batch by flow.batch.collectAsState()
    LaunchedEffect(batch) {
        when (
            val action = nextPermissionAction(
                batch,
                notificationsBatch,
                mediaBatch,
                hasPermission,
            )
        ) {
            PermissionAction.Advance -> flow.nextBatch()
            PermissionAction.MarkDone -> flow.markDone()
            PermissionAction.Stay -> Unit
            is PermissionAction.LaunchNotifications ->
                launchNotifications(action.perms.toTypedArray())
            is PermissionAction.LaunchMedia ->
                launchMedia(action.perms.toTypedArray())
        }
    }
}

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val otgManager = OtgIngestManager.getInstance(this)

        setContent {
            val themePrefs = remember {
                ThemePreferences(
                    applicationContext.getSharedPreferences(
                        BranchDamKeys.PREFS_NAME,
                        android.content.Context.MODE_PRIVATE,
                    ),
                )
            }
            val themeMode by themePrefs.mode.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
            }
            BranchDamTheme(themeMode = themeMode) {
                val windowSizeClass = calculateWindowSizeClass(this)
                val permissionFlow = remember { PermissionFlowState() }

                val (notificationsBatch, mediaBatch) = remember { runtimePermissionBatches() }

                val settingsViewModel = viewModel<SettingsViewModel>()

                val hasPermission: (String) -> Boolean = { perm ->
                    checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                var mediaAccess by remember {
                    mutableStateOf(mediaAccessLevel(Build.VERSION.SDK_INT, hasPermission))
                }
                // Bumped whenever a permission dialog returns, so the gallery
                // reloads even when the level stays LIMITED but the user
                // picked a different set of photos.
                var mediaAccessGeneration by remember { mutableIntStateOf(0) }
                // The user can change a "Select photos" grant from system
                // settings at any time; re-read it whenever we come back.
                LifecycleResumeEffect(Unit) {
                    mediaAccess = mediaAccessLevel(Build.VERSION.SDK_INT, hasPermission)
                    onPauseOrDispose { }
                }

                // Re-request launched from the "Select more photos" banner.
                // On API 34+ this re-opens the system picker for a partial
                // grant (or lets the user allow everything).
                val reselectMediaLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) { _ ->
                    mediaAccess = mediaAccessLevel(Build.VERSION.SDK_INT, hasPermission)
                    mediaAccessGeneration++
                }

                val mediaLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) { _ ->
                    mediaAccess = mediaAccessLevel(Build.VERSION.SDK_INT, hasPermission)
                    mediaAccessGeneration++
                    // Advance to the next batch even on denial. The
                    // next LaunchedEffect pass will short-circuit
                    // to MarkDone if the user has now granted or
                    // permanently refused everything. This is the
                    // fix for the "infinite no-op loop" the previous
                    // design introduced when both launchers reset
                    // back to NONE on every callback.
                    permissionFlow.nextBatch()
                }

                val notificationsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) { _ ->
                    permissionFlow.nextBatch()
                }

                DrivePermissionFlow(
                    flow = permissionFlow,
                    notificationsBatch = notificationsBatch,
                    mediaBatch = mediaBatch,
                    hasPermission = hasPermission,
                    launchNotifications = notificationsLauncher::launch,
                    launchMedia = mediaLauncher::launch,
                )

                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                val sharedPrefs = applicationContext.getSharedPreferences(
                    BranchDamKeys.PREFS_NAME,
                    android.content.Context.MODE_PRIVATE,
                )
                val isOnboardingCompleted = remember {
                    val serverUrlSet = sharedPrefs.getString(BranchDamApplication.KEY_SERVER_URL, null) != null
                    sharedPrefs.getBoolean(BranchDamKeys.ONBOARDING_COMPLETED, serverUrlSet)
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    NavigationSuiteScaffold(
                        windowSizeClass = windowSizeClass,
                        currentRoute = currentRoute,
                        onNavigate = { route ->
                            if (route == Screen.Settings.route) {
                                settingsViewModel.triggerNavigationReset()
                            }
                            navController.navigate(route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    ) {
                        AppNavGraph(
                            navController = navController,
                            windowSizeClass = windowSizeClass,
                            startDestination = if (isOnboardingCompleted) Screen.Lineage.route else Screen.Onboarding.route,
                            mediaAccess = mediaAccess,
                            mediaAccessGeneration = mediaAccessGeneration,
                            onSelectMorePhotos = {
                                reselectMediaLauncher.launch(
                                    mediaReselectPermissions(mediaBatch).toTypedArray(),
                                )
                            },
                            onRequestPermissions = {
                                if (permissionFlow.batch.value == PermissionBatch.NONE) {
                                    permissionFlow.nextBatch()
                                }
                            }
                        )
                    }

                    // Handle onboarding completion persistence
                    LaunchedEffect(currentRoute) {
                        if (currentRoute == Screen.Lineage.route && !isOnboardingCompleted) {
                            sharedPrefs.edit().putBoolean(BranchDamKeys.ONBOARDING_COMPLETED, true).apply()
                        }
                    }

                    val otgState by otgManager.state.collectAsState()
                    val pickCardFolder = rememberOtgCardFolderPicker { uri ->
                        val label = (otgState as? OtgState.NeedsCardAccess)?.deviceLabel
                        if (label != null) {
                            otgManager.onDocumentTreeSelected(uri, label)
                        } else {
                            otgManager.onDocumentTreeSelected(uri)
                        }
                    }
                    when (val state = otgState) {
                        is OtgState.NeedsCardAccess -> {
                            OtgCardAccessDialog(
                                deviceLabel = state.deviceLabel,
                                onChooseFolder = pickCardFolder,
                                onDismiss = { otgManager.reset() },
                            )
                        }
                        is OtgState.AwaitingConfirmation -> {
                            OtgImportConfirmationDialog(
                                scanResult = state.scanResult,
                                // The manager copies, verifies and enqueues
                                // each file itself (see OtgIngestManager).
                                onConfirm = { otgManager.confirmImport(scanResult = state.scanResult) },
                                onDismiss = { otgManager.cancelImport() },
                            )
                        }
                        is OtgState.Ingesting -> {
                            OtgIngestProgressDialog(
                                progress = state.progress,
                                onCancel = { otgManager.cancelImport() },
                            )
                        }
                        is OtgState.Completed -> {
                            OtgIngestCompletedDialog(
                                importedCount = state.importedCount,
                                totalBytes = state.totalBytes,
                                fileErrors = state.fileErrors,
                                onDismiss = { otgManager.reset() },
                            )
                        }
                        is OtgState.Error -> {
                            OtgIngestErrorDialog(
                                errorMessage = state.message,
                                onDismiss = { otgManager.reset() },
                            )
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}
