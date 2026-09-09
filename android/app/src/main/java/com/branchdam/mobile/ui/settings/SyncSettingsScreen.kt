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

private val INTERVAL_OPTIONS = listOf(15, 30, 60, 120)
private val INTERVAL_LABELS = INTERVAL_OPTIONS.map { if (it < 60) "$it min" else "${it / 60} hour" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel,
) {
    val syncOnMobileData by viewModel.syncOnMobileData.collectAsStateWithLifecycle()
    val syncIntervalMinutes by viewModel.syncIntervalMinutes.collectAsStateWithLifecycle()
    val syncOnBatteryOnly by viewModel.syncOnBatteryOnly.collectAsStateWithLifecycle()

    var intervalExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync") },
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
            ListItem(
                headlineContent = { Text("Network") },
                overlineContent = { Text("Connectivity", color = MaterialTheme.colorScheme.primary) }
            )

            ListItem(
                headlineContent = { Text("Sync on Mobile Data") },
                supportingContent = { Text("Allow uploads over cellular connection") },
                trailingContent = {
                    Switch(
                        checked = syncOnMobileData,
                        onCheckedChange = { viewModel.setSyncOnMobileData(it) }
                    )
                },
                onClick = { viewModel.setSyncOnMobileData(!syncOnMobileData) }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            ListItem(
                headlineContent = { Text("Schedule") },
                overlineContent = { Text("Background Work", color = MaterialTheme.colorScheme.primary) }
            )

            Box(modifier = Modifier.padding(16.dp)) {
                ExposedDropdownMenuBox(
                    expanded = intervalExpanded,
                    onExpandedChange = { intervalExpanded = it },
                ) {
                    val intervalIndex = INTERVAL_OPTIONS.indexOf(syncIntervalMinutes).coerceAtLeast(0)
                    OutlinedTextField(
                        value = INTERVAL_LABELS[intervalIndex],
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Sync Interval") },
                        supportingText = { Text("How often to check for new media") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = intervalExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = intervalExpanded,
                        onDismissRequest = { intervalExpanded = false },
                    ) {
                        INTERVAL_LABELS.forEachIndexed { index, label ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    viewModel.setSyncIntervalMinutes(INTERVAL_OPTIONS[index])
                                    intervalExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            ListItem(
                headlineContent = { Text("Battery") },
                overlineContent = { Text("Power Management", color = MaterialTheme.colorScheme.primary) }
            )

            ListItem(
                headlineContent = { Text("Sync on Low Battery") },
                supportingContent = { Text("Allow background sync even when battery is low") },
                trailingContent = {
                    Switch(
                        checked = syncOnBatteryOnly,
                        onCheckedChange = { viewModel.setSyncOnBatteryOnly(it) }
                    )
                },
                onClick = { viewModel.setSyncOnBatteryOnly(!syncOnBatteryOnly) }
            )
        }
    }
}
