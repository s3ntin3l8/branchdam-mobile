package com.branchdam.mobile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
            CenterAlignedTopAppBar(
                title = { Text("Advanced") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(16.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
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
                        OutlinedTextField(
                            value = "${syncTimeoutSecs}s",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Sync Timeout") },
                            supportingText = { Text("Max seconds to wait for a sync batch") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = timeoutExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
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
                        OutlinedTextField(
                            value = "${observerDebounceMs}ms",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Observer Debounce") },
                            supportingText = { Text("Delay before processing camera roll changes") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = debounceExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
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
    }
}
