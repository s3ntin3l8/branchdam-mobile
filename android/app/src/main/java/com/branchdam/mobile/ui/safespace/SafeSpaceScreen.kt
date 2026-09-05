package com.branchdam.mobile.ui.safespace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafeSpaceScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SafeSpaceViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val reclaimableMb = uiState.reclaimableBytes / (1024L * 1024L)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Safe Space") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Reclaimable Storage",
                style = MaterialTheme.typography.headlineSmall,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("$reclaimableMb MB", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "${uiState.verifiedCount} verified items ready for reclaim",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (uiState.isReclaiming) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Reclaiming...", style = MaterialTheme.typography.bodySmall)
            }

            uiState.reclaimMessage?.let { msg ->
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.reclaim() },
                enabled = uiState.verifiedCount > 0 && !uiState.isReclaiming,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Free Up $reclaimableMb MB")
            }
        }
    }
}
