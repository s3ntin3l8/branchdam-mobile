package com.branchdam.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sync on Mobile Data")
                    Text(
                        "Allow uploads over cellular connection",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = syncOnMobileData,
                    onCheckedChange = { viewModel.setSyncOnMobileData(it) },
                )
            }

            ExposedDropdownMenuBox(
                expanded = intervalExpanded,
                onExpandedChange = { intervalExpanded = it },
            ) {
                val intervalIndex = INTERVAL_OPTIONS.indexOf(syncIntervalMinutes).coerceAtLeast(0)
                OutlinedTextField(
                    value = INTERVAL_LABELS[intervalIndex],
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Background Sync Interval") },
                    supportingText = { Text("How often to check for new media") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = intervalExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sync on Battery Only")
                    Text(
                        "Allow background sync even when battery is low",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = syncOnBatteryOnly,
                    onCheckedChange = { viewModel.setSyncOnBatteryOnly(it) },
                )
            }
        }
    }
}
