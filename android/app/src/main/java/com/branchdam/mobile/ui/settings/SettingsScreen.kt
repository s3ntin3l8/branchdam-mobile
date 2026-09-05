package com.branchdam.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val syncOnMobileData by viewModel.syncOnMobileData.collectAsStateWithLifecycle()
    val autoImportEnabled by viewModel.autoImportEnabled.collectAsStateWithLifecycle()
    val namingTemplate by viewModel.namingTemplate.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val isConnecting by viewModel.isConnecting.collectAsStateWithLifecycle()
    val connectionError by viewModel.connectionError.collectAsStateWithLifecycle()
    val urlError by viewModel.urlError.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(12.dp),
                    shape = MaterialTheme.shapes.small,
                    color = if (isConnected) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.error,
                ) {}
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isConnected) "Connected" else "Disconnected",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            connectionError?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { viewModel.updateServerUrl(it) },
                label = { Text("Server URL") },
                isError = urlError != null,
                supportingText = urlError?.let { err -> { Text(err) } },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { viewModel.updateApiKey(it) },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = namingTemplate,
                onValueChange = {},
                readOnly = true,
                label = { Text("Naming Template") },
                supportingText = { Text("Synchronized via server handshake (POST /api/v1/agent/upload)") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sync on Mobile Data")
                Switch(
                    checked = syncOnMobileData,
                    onCheckedChange = { viewModel.setSyncOnMobileData(it) },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto-import Camera Roll")
                Switch(
                    checked = autoImportEnabled,
                    onCheckedChange = { viewModel.setAutoImportEnabled(it) },
                )
            }

            Button(
                onClick = { viewModel.connect() },
                enabled = !isConnecting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isConnecting) "Connecting..." else "Save and Connect")
            }

            // Footer: shows the running build's identity, derived from
            // BuildConfig so it tracks the APK/AAB's actual versionName
            // and versionCode rather than a hardcoded literal.
            Text(
                text = "branchDAM Mobile ${viewModel.versionName} (build ${viewModel.versionCode})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
