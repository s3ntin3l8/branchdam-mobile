package com.branchdam.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.branchdam.mobile.otg.OtgIngestManager
import com.branchdam.mobile.otg.OtgState
import com.branchdam.mobile.ui.DualPaneScreen
import com.branchdam.mobile.ui.OtgImportConfirmationDialog
import com.branchdam.mobile.ui.OtgIngestCompletedDialog
import com.branchdam.mobile.ui.OtgIngestProgressDialog
import com.branchdam.mobile.ui.theme.BranchDamTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val otgManager = OtgIngestManager.getInstance(this)

        setContent {
            BranchDamTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Check if width is large enough for dual-pane (unfolded Pixel Fold)
                    val configuration = resources.configuration
                    val isFolded = configuration.screenWidthDp < 600

                    DualPaneScreen(isFolded = isFolded)

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
