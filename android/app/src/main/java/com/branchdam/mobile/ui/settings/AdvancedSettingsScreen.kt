package com.branchdam.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val BATCH_SIZE_OPTIONS = listOf(5, 10, 20, 50)
private val TIMEOUT_OPTIONS = listOf(60, 120, 300)
private val TIMEOUT_LABELS = TIMEOUT_OPTIONS.map { "${it}s" }
private val DEBOUNCE_OPTIONS = listOf(250L, 500L, 1000L)
private val DEBOUNCE_LABELS = DEBOUNCE_OPTIONS.map { "${it}ms" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel,
) {
    val uploadBatchSize by viewModel.uploadBatchSize.collectAsStateWithLifecycle()
    val syncTimeoutSecs by viewModel.syncTimeoutSecs.collectAsStateWithLifecycle()
    val observerDebounceMs by viewModel.observerDebounceMs.collectAsStateWithLifecycle()

    var batchExpanded by remember { mutableStateOf(false) }
    var timeoutExpanded by remember { mutableStateOf(false) }
    var debounceExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Advanced") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ExposedDropdownMenuBox(
                expanded = batchExpanded,
                onExpandedChange = { batchExpanded = it },
            ) {
                OutlinedTextField(
                    value = "$uploadBatchSize items",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Upload Batch Size") },
                    supportingText = { Text("Items processed per sync cycle") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = batchExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = batchExpanded,
                    onDismissRequest = { batchExpanded = false },
                ) {
                    BATCH_SIZE_OPTIONS.forEach { size ->
                        DropdownMenuItem(
                            text = { Text("$size items") },
                            onClick = {
                                viewModel.setUploadBatchSize(size)
                                batchExpanded = false
                            },
                        )
                    }
                }
            }

            ExposedDropdownMenuBox(
                expanded = timeoutExpanded,
                onExpandedChange = { timeoutExpanded = it },
            ) {
                val timeoutIndex = TIMEOUT_OPTIONS.indexOf(syncTimeoutSecs).coerceAtLeast(0)
                OutlinedTextField(
                    value = TIMEOUT_LABELS[timeoutIndex],
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Sync Timeout") },
                    supportingText = { Text("Max seconds to wait for a sync batch") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = timeoutExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = timeoutExpanded,
                    onDismissRequest = { timeoutExpanded = false },
                ) {
                    TIMEOUT_LABELS.forEachIndexed { index, label ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                viewModel.setSyncTimeoutSecs(TIMEOUT_OPTIONS[index])
                                timeoutExpanded = false
                            },
                        )
                    }
                }
            }

            ExposedDropdownMenuBox(
                expanded = debounceExpanded,
                onExpandedChange = { debounceExpanded = it },
            ) {
                val debounceIndex = DEBOUNCE_OPTIONS.indexOf(observerDebounceMs).coerceAtLeast(0)
                OutlinedTextField(
                    value = DEBOUNCE_LABELS[debounceIndex],
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Observer Debounce") },
                    supportingText = { Text("Delay before processing camera roll changes") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = debounceExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = debounceExpanded,
                    onDismissRequest = { debounceExpanded = false },
                ) {
                    DEBOUNCE_LABELS.forEachIndexed { index, label ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                viewModel.setObserverDebounceMs(DEBOUNCE_OPTIONS[index])
                                debounceExpanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}
