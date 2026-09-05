package com.branchdam.mobile

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import com.branchdam.mobile.ui.theme.BranchDamTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks which permission batch is currently pending. Each batch is
 * requested sequentially: NONE → NOTIFICATIONS → MEDIA → MEDIA (terminal).
 *
 * The original implementation fired two `requestPermissions()` calls
 * synchronously inside `onCreate`, and on Android 13+ the second call
 * dismissed the first dialog without delivering its result. Sequencing
 * through a state machine means the next batch's launcher is only
 * invoked after the previous batch's callback fires — there is no
 * overlap, no dismissed dialog, and no missing `onRequestPermissionsResult`.
 */
enum class PermissionBatch {
    NONE,
    NOTIFICATIONS,
    MEDIA,
}

class PermissionFlowState {
    private val _batch = MutableStateFlow(PermissionBatch.NONE)
    val batch: StateFlow<PermissionBatch> = _batch.asStateFlow()

    fun nextBatch() {
        _batch.value = when (_batch.value) {
            PermissionBatch.NONE -> PermissionBatch.NOTIFICATIONS
            PermissionBatch.NOTIFICATIONS -> PermissionBatch.MEDIA
            PermissionBatch.MEDIA -> PermissionBatch.MEDIA
        }
    }

    fun reset() {
        _batch.value = PermissionBatch.NONE
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
 * Drives the permission request state machine. Lives in a top-level
 * Composable so unit tests can call it directly with a fake
 * [PermissionFlowState] and inspect state transitions without
 * instantiating an Activity.
 *
 * The [LaunchedEffect] re-runs only when [batch] changes; within a
 * batch, it either launches the corresponding dialog (if the batch has
 * permissions and any are still missing) or advances to the next
 * batch. Both launchers' callbacks feed back into the state machine
 * via `nextBatch()` / `reset()`, so the second dialog cannot appear
 * before the first dialog has been resolved.
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
        when (batch) {
            PermissionBatch.NONE -> flow.nextBatch()
            PermissionBatch.NOTIFICATIONS -> {
                if (notificationsBatch.isEmpty()) {
                    flow.nextBatch()
                } else {
                    launchNotifications(notificationsBatch.toTypedArray())
                }
            }
            PermissionBatch.MEDIA -> {
                val missing = mediaBatch.filter { perm -> !hasPermission(perm) }
                if (missing.isNotEmpty()) {
                    launchMedia(missing.toTypedArray())
                }
            }
        }
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val otgManager = OtgIngestManager.getInstance(this)

        setContent {
            BranchDamTheme {
                val permissionFlow = remember { PermissionFlowState() }

                val (notificationsBatch, mediaBatch) = remember { runtimePermissionBatches() }

                val mediaLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) { _ ->
                    permissionFlow.reset()
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

                val showBottomBar = currentRoute in bottomNavRoutes

                Surface(modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        bottomBar = {
                            if (showBottomBar) {
                                BottomNavBar(
                                    currentRoute = currentRoute,
                                    onNavigate = { route: String ->
                                        navController.navigate(route) {
                                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        },
                    ) { padding ->
                        AppNavGraph(
                            navController = navController,
                            modifier = Modifier.padding(padding),
                        )
                    }

                    val otgState by otgManager.state.collectAsState()
                    when (val state = otgState) {
                        is OtgState.AwaitingConfirmation -> {
                            OtgImportConfirmationDialog(
                                scanResult = state.scanResult,
                                onConfirm = { otgManager.confirmImport(state.scanResult) },
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
