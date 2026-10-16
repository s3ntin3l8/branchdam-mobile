package com.branchdam.mobile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.branchdam.mobile.R
import com.branchdam.mobile.ui.components.quantityStringResource

private val INTERVAL_OPTIONS = listOf(15, 30, 60, 120)

/** "15 min", "1 hour", "2 hours" for a sync interval in minutes. */
@Composable
private fun intervalLabel(minutes: Int): String =
    if (minutes < 60) stringResource(R.string.sync_settings_interval_minutes, minutes)
    else quantityStringResource(R.plurals.sync_settings_interval_hours, minutes / 60, minutes / 60)

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
                title = { Text(stringResource(R.string.settings_sync)) },
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

            SettingsSubHeader(stringResource(R.string.sync_settings_network))
            SettingsCard {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.sync_settings_mobile_data)) },
                    supportingContent = { Text(stringResource(R.string.sync_settings_mobile_data_summary)) },
                    trailingContent = {
                        // The whole row is the toggle (one TalkBack element
                        // announced with its label and on/off state).
                        Switch(
                            checked = syncOnMobileData,
                            onCheckedChange = null
                        )
                    },
                    modifier = Modifier.toggleable(
                        value = syncOnMobileData,
                        role = Role.Switch,
                        onValueChange = { viewModel.setSyncOnMobileData(it) },
                    ),
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                )
            }

            Spacer(Modifier.height(24.dp))

            SettingsSubHeader(stringResource(R.string.sync_settings_schedule))
            SettingsCard {
                Box(modifier = Modifier.padding(16.dp)) {
                    ExposedDropdownMenuBox(
                        expanded = intervalExpanded,
                        onExpandedChange = { intervalExpanded = it },
                    ) {
                        val intervalIndex = INTERVAL_OPTIONS.indexOf(syncIntervalMinutes).coerceAtLeast(0)
                        OutlinedTextField(
                            value = intervalLabel(INTERVAL_OPTIONS[intervalIndex]),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.sync_settings_interval)) },
                            supportingText = { Text(stringResource(R.string.sync_settings_interval_summary)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = intervalExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = intervalExpanded,
                            onDismissRequest = { intervalExpanded = false },
                        ) {
                            INTERVAL_OPTIONS.forEachIndexed { index, minutes ->
                                DropdownMenuItem(
                                    text = { Text(intervalLabel(minutes)) },
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

            SettingsSubHeader(stringResource(R.string.sync_settings_battery))
            SettingsCard {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.sync_settings_low_battery)) },
                    supportingContent = { Text(stringResource(R.string.sync_settings_low_battery_summary)) },
                    trailingContent = {
                        Switch(
                            checked = syncOnBatteryOnly,
                            onCheckedChange = null
                        )
                    },
                    modifier = Modifier.toggleable(
                        value = syncOnBatteryOnly,
                        role = Role.Switch,
                        onValueChange = { viewModel.setSyncOnBatteryOnly(it) },
                    ),
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
