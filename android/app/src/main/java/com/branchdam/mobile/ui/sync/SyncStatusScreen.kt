package com.branchdam.mobile.ui.sync

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusScreen(
    modifier: Modifier = Modifier,
    viewModel: SyncStatusViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = { TopAppBar(title = { Text("Sync Status") }) },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(12.dp),
                        shape = MaterialTheme.shapes.small,
                        color = if (uiState.isConnected) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.error,
                    ) {}
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (uiState.isConnected) "Connected to server" else "Disconnected",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Worker State", style = MaterialTheme.typography.labelMedium)
                    Text(uiState.workerState, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("Last Sync", style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (uiState.lastSyncTime > 0) {
                            DateUtils.getRelativeTimeSpanString(
                                uiState.lastSyncTime,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                            ).toString()
                        } else {
                            "Never synced"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            Button(
                onClick = { viewModel.triggerSync() },
                enabled = uiState.isConnected && !uiState.isSyncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (uiState.isSyncing) "Syncing..." else "Sync Now")
            }

            OutlinedButton(
                onClick = { viewModel.refresh() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Refresh")
            }
        }
    }
}
