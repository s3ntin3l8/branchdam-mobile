package com.branchdam.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
            CenterAlignedTopAppBar(
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
            Spacer(Modifier.height(16.dp))

            SettingsSubHeader("Network")
            SettingsCard {
                ListItem(
                    headlineContent = { Text("Sync on Mobile Data") },
                    supportingContent = { Text("Allow uploads over cellular connection") },
                    trailingContent = {
                        Switch(
                            checked = syncOnMobileData,
                            onCheckedChange = { viewModel.setSyncOnMobileData(it) }
                        )
                    },
                    modifier = Modifier.clickable { viewModel.setSyncOnMobileData(!syncOnMobileData) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                )
            }

            Spacer(Modifier.height(24.dp))

            SettingsSubHeader("Schedule")
            SettingsCard {
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
            }

            Spacer(Modifier.height(24.dp))

            SettingsSubHeader("Battery")
            SettingsCard {
                ListItem(
                    headlineContent = { Text("Sync on Low Battery") },
                    supportingContent = { Text("Allow background sync even when battery is low") },
                    trailingContent = {
                        Switch(
                            checked = syncOnBatteryOnly,
                            onCheckedChange = { viewModel.setSyncOnBatteryOnly(it) }
                        )
                    },
                    modifier = Modifier.clickable { viewModel.setSyncOnBatteryOnly(!syncOnBatteryOnly) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsSubHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
    )
}
