package com.branchdam.mobile

import android.Manifest
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.branchdam.mobile.otg.OtgIngestManager
import com.branchdam.mobile.otg.OtgState
import com.branchdam.mobile.ui.OtgImportConfirmationDialog
import com.branchdam.mobile.ui.OtgIngestCompletedDialog
import com.branchdam.mobile.ui.OtgIngestErrorDialog
import com.branchdam.mobile.ui.OtgIngestProgressDialog
import com.branchdam.mobile.ui.navigation.AppNavGraph
import com.branchdam.mobile.ui.navigation.BottomNavBar
import com.branchdam.mobile.ui.navigation.Screen
import com.branchdam.mobile.ui.navigation.bottomNavRoutes
import com.branchdam.mobile.ui.settings.SettingsViewModel
import com.branchdam.mobile.ui.theme.BranchDamTheme
import com.branchdam.mobile.ui.theme.ThemeMode
import com.branchdam.mobile.ui.theme.ThemePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks which permission batch is currently pending. The flow is:
 *
 * ```
 * NONE → NOTIFICATIONS → MEDIA → DONE
 * ```
 *
 * `DONE` is the terminal state and short-circuits the
 * [DrivePermissionFlow] `LaunchedEffect`, so a user who permanently
 * denies a permission (which on Android 13+ returns the launcher
 * callback immediately with `false`) cannot cause the state machine to
 * loop forever. The pre-fix design over-loaded `MEDIA` as a de-facto
 * terminal state and reset to `NONE` after every media-batch callback,
 * which meant a user denying everything would re-trigger the launchers
 * on every Compose recomposition. See
 * `PermissionFlowStateTest.testDoneStateIsTerminal` for the
 * regression guard.
 */
enum class PermissionBatch {
    NONE,
    NOTIFICATIONS,
    MEDIA,
    DONE,
}

class PermissionFlowState {
    private val _batch = MutableStateFlow(PermissionBatch.NONE)
    val batch: StateFlow<PermissionBatch> = _batch.asStateFlow()

    fun nextBatch() {
        _batch.value = when (_batch.value) {
            PermissionBatch.NONE -> PermissionBatch.NOTIFICATIONS
            PermissionBatch.NOTIFICATIONS -> PermissionBatch.MEDIA
            PermissionBatch.MEDIA -> PermissionBatch.DONE
            PermissionBatch.DONE -> PermissionBatch.DONE
        }
    }

    fun markDone() {
        _batch.value = PermissionBatch.DONE
    }
}

/**
 * Returns the runtime permissions the app needs, partitioned by API
 * level. Pre-API 33 (Tiramisu) reads media via
 * [Manifest.permission.READ_EXTERNAL_STORAGE]; API 33+ reads media via
 * [Manifest.permission.READ_MEDIA_IMAGES] /
 * [Manifest.permission.READ_MEDIA_VIDEO]. `POST_NOTIFICATIONS` is only
 * meaningful on API 33+; earlier versions grant it implicitly.
 *
 * Pure function so unit tests can exercise the API partitioning logic
 * without instantiating an Activity.
 */
internal fun runtimePermissionBatches(): Pair<List<String>, List<String>> {
    val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyList()
    }
    val media = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return notifications to media
}

/**
 * Pure decision function: given the current state and the runtime
 * batches, decide what action to take next. Extracted from
 * [DrivePermissionFlow] so unit tests can exercise the sequencing
 * logic without instantiating a Compose runtime.
 *
 * Returns the next [PermissionBatch] state the flow should transition
 * to. `null` means "stay where you are" (used when a launcher has
 * fired and is awaiting the user's callback).
 *
 * The "already granted" filter is applied to *both* batches — without
 * it, a user who has already granted `POST_NOTIFICATIONS` would see
 * one no-op dialog fire after the first cycle before settling. (The
 * pre-fix `MEDIA`-only filter meant a `POST_NOTIFICATIONS` denial
 * would cause a re-launch.)
 */
internal fun nextPermissionAction(
    batch: PermissionBatch,
    notificationsBatch: List<String>,
    mediaBatch: List<String>,
    hasPermission: (String) -> Boolean,
): PermissionAction {
    return when (batch) {
        PermissionBatch.NONE -> PermissionAction.Advance
        PermissionBatch.NOTIFICATIONS -> {
            if (notificationsBatch.isEmpty()) {
                PermissionAction.Advance
            } else {
                val missing = notificationsBatch.filter { !hasPermission(it) }
                if (missing.isEmpty()) {
                    PermissionAction.Advance
                } else {
                    PermissionAction.LaunchNotifications(missing)
                }
            }
        }
        PermissionBatch.MEDIA -> {
            val missing = mediaBatch.filter { !hasPermission(it) }
            if (missing.isEmpty()) {
                PermissionAction.MarkDone
            } else {
                PermissionAction.LaunchMedia(missing)
            }
        }
        PermissionBatch.DONE -> PermissionAction.Stay
    }
}

internal sealed class PermissionAction {
    object Advance : PermissionAction()
    object MarkDone : PermissionAction()
    object Stay : PermissionAction()
    data class LaunchNotifications(val perms: List<String>) : PermissionAction()
    data class LaunchMedia(val perms: List<String>) : PermissionAction()
}

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
                val permissionFlow = remember { PermissionFlowState() }

                val (notificationsBatch, mediaBatch) = remember { runtimePermissionBatches() }

                val settingsViewModel = viewModel<SettingsViewModel>()

                val mediaLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) { _ ->
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
                    hasPermission = { perm ->
                        checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    },
                    launchNotifications = notificationsLauncher::launch,
                    launchMedia = mediaLauncher::launch,
                )

                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                val showBottomBar = currentRoute in bottomNavRoutes && currentRoute != Screen.Onboarding.route

                val isOnboardingCompleted = remember {
                    applicationContext.getSharedPreferences(
                        BranchDamKeys.PREFS_NAME,
                        android.content.Context.MODE_PRIVATE,
                    ).getBoolean(BranchDamKeys.ONBOARDING_COMPLETED, false)
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) {
                            AppNavGraph(
                                navController = navController,
                                startDestination = if (isOnboardingCompleted) Screen.Lineage.route else Screen.Onboarding.route,
                                onRequestPermissions = {
                                    // Trigger the permission flow batch manually if needed,
                                    // but DrivePermissionFlow is already active and
                                    // will fire once the Onboarding page asks for it.
                                    // For now, we just advance the batch from NONE.
                                    if (permissionFlow.batch.value == PermissionBatch.NONE) {
                                        permissionFlow.nextBatch()
                                    }
                                }
                            )
                        }
                        if (showBottomBar) {
                            BottomNavBar(
                                currentRoute = currentRoute,
                                onNavigate = { route: String ->
                                    if (route == Screen.Settings.route) {
                                        settingsViewModel.triggerNavigationReset()
                                    }
                                    navController.navigate(route) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                            )
                        }
                    }

                    // Handle onboarding completion persistence
                    LaunchedEffect(currentRoute) {
                        if (currentRoute == Screen.Lineage.route && !isOnboardingCompleted) {
                            applicationContext.getSharedPreferences(
                                BranchDamKeys.PREFS_NAME,
                                android.content.Context.MODE_PRIVATE,
                            ).edit().putBoolean(BranchDamKeys.ONBOARDING_COMPLETED, true).apply()
                        }
                    }

                    val otgState by otgManager.state.collectAsState()
                    when (val state = otgState) {
                        is OtgState.AwaitingConfirmation -> {
                            OtgImportConfirmationDialog(
                                scanResult = state.scanResult,
                                onConfirm = {
                                    otgManager.confirmImport(
                                        scanResult = state.scanResult,
                                        onFileStaged = { file, candidate ->
                                            EngineHolder.enqueueMedia(
                                                localPath = file.absolutePath,
                                                filename = candidate.fileName,
                                                capturedAtUnix = candidate.lastModifiedUnix,
                                                localId = candidate.uri
                                            )
                                        }
                                    )
                                },
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
