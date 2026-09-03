package com.branchdam.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.WindowInfoTracker
import com.branchdam.mobile.otg.OtgIngestManager
import com.branchdam.mobile.otg.OtgState
import com.branchdam.mobile.ui.DevicePosture
import com.branchdam.mobile.ui.DualPaneScreen
import com.branchdam.mobile.ui.OtgImportConfirmationDialog
import com.branchdam.mobile.ui.OtgIngestCompletedDialog
import com.branchdam.mobile.ui.OtgIngestProgressDialog
import com.branchdam.mobile.ui.theme.BranchDamTheme
import com.branchdam.mobile.ui.toDevicePosture
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var devicePosture by mutableStateOf<DevicePosture>(DevicePosture.Flat)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        val otgManager = OtgIngestManager.getInstance(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity)
                    .windowLayoutInfo(this@MainActivity)
                    .map { it.toDevicePosture() }
                    .collect { devicePosture = it }
            }
        }

        setContent {
            BranchDamTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val configuration = resources.configuration
                    val posture = if (devicePosture != DevicePosture.Flat) {
                        devicePosture
                    } else {
                        val screenWidthDp = configuration.screenWidthDp
                        if (screenWidthDp < 600) DevicePosture.Flat else DevicePosture.Book
                    }

                    DualPaneScreen(posture = posture)

                    val otgState by otgManager.state.collectAsState()
                    when (val state = otgState) {
                        is OtgState.AwaitingConfirmation -> {
                            OtgImportConfirmationDialog(
                                scanResult = state.scanResult,
                                onConfirm = { otgManager.confirmImport(state.scanResult) },
                                onDismiss = { otgManager.cancelImport() }
                            )
                        }
                        is OtgState.Ingesting -> {
                            OtgIngestProgressDialog(
                                progress = state.progress,
                                onCancel = { otgManager.cancelImport() }
                            )
                        }
                        is OtgState.Completed -> {
                            OtgIngestCompletedDialog(
                                importedCount = state.importedCount,
                                totalBytes = state.totalBytes,
                                fileErrors = state.fileErrors,
                                onDismiss = { otgManager.reset() }
                            )
                        }
                        is OtgState.Error -> {
                            com.branchdam.mobile.ui.OtgIngestErrorDialog(
                                errorMessage = state.message,
                                onDismiss = { otgManager.reset() }
                            )
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}
