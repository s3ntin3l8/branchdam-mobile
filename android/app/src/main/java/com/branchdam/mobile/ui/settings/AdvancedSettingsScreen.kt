package com.branchdam.mobile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.branchdam.mobile.R
import com.branchdam.mobile.ui.components.quantityStringResource

private val BATCH_SIZE_OPTIONS = listOf(5, 10, 20, 50)
private val TIMEOUT_OPTIONS = listOf(60, 120, 300)
private val DEBOUNCE_OPTIONS = listOf(250L, 500L, 1000L)

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
                title = { Text(stringResource(R.string.settings_advanced)) },
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
                            value = quantityStringResource(R.plurals.advanced_items, uploadBatchSize, uploadBatchSize),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.advanced_batch_size)) },
                            supportingText = { Text(stringResource(R.string.advanced_batch_size_summary)) },
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
                                    text = { Text(quantityStringResource(R.plurals.advanced_items, size, size)) },
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
                            value = stringResource(R.string.advanced_seconds, syncTimeoutSecs),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.advanced_sync_timeout)) },
                            supportingText = { Text(stringResource(R.string.advanced_sync_timeout_summary)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = timeoutExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = timeoutExpanded,
                            onDismissRequest = { timeoutExpanded = false },
                        ) {
                            TIMEOUT_OPTIONS.forEachIndexed { index, seconds ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.advanced_seconds, seconds)) },
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
                            value = stringResource(R.string.advanced_milliseconds, observerDebounceMs),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.advanced_debounce)) },
                            supportingText = { Text(stringResource(R.string.advanced_debounce_summary)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = debounceExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = debounceExpanded,
                            onDismissRequest = { debounceExpanded = false },
                        ) {
                            DEBOUNCE_OPTIONS.forEachIndexed { index, millis ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.advanced_milliseconds, millis)) },
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
