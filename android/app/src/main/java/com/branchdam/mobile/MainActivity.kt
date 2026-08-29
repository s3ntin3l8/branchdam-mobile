package com.branchdam.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.branchdam.mobile.ui.DualPaneScreen
import com.branchdam.mobile.ui.theme.BranchDamTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BranchDamTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Check if width is large enough for dual-pane (unfolded Pixel Fold)
                    val configuration = resources.configuration
                    val isFolded = configuration.screenWidthDp < 600

                    DualPaneScreen(isFolded = isFolded)
                }
            }
        }
    }
}
