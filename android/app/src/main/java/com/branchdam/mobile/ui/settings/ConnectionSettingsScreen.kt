package com.branchdam.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.branchdam.mobile.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(
    onNavigateBack: () -> Unit,
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel,
) {
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val namingTemplate by viewModel.namingTemplate.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val isConnecting by viewModel.isConnecting.collectAsStateWithLifecycle()
    val connectionError by viewModel.connectionError.collectAsStateWithLifecycle()
    val urlError by viewModel.urlError.collectAsStateWithLifecycle()
    val keyStorageError by viewModel.keyStorageError.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.checkConnection()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connection_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                // Icon shape as well as colour: the status must not be
                // conveyed by colour alone. The text says it too.
                Icon(
                    imageVector = if (isConnected) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = if (isConnected) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (isConnected) R.string.connection_connected else R.string.connection_disconnected),
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
                label = { Text(stringResource(R.string.connection_server_url)) },
                isError = urlError != null,
                supportingText = urlError?.let { err -> { Text(err) } },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { viewModel.updateApiKey(it) },
                label = { Text(stringResource(R.string.connection_api_key)) },
                // Only a key that can't be stored is an error; with no key
                // entered the message is just information.
                isError = keyStorageError != null && apiKey.isNotBlank(),
                supportingText = keyStorageError?.let { err -> { Text(err) } },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = namingTemplate,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.connection_naming_template)) },
                supportingText = { Text(stringResource(R.string.connection_naming_template_summary)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = { viewModel.connect() },
                enabled = !isConnecting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (isConnecting) R.string.connection_connecting else R.string.connection_save_connect))
            }

            OutlinedButton(
                onClick = onScanQr,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.connection_scan_qr))
            }
        }
    }
}
