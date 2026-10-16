package com.branchdam.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.branchdam.mobile.R
import com.branchdam.mobile.observer.MediaScanner
import com.branchdam.mobile.otg.OtgIngestManager
import com.branchdam.mobile.ui.rememberOtgCardFolderPicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel,
) {
    val autoImportEnabled by viewModel.autoImportEnabled.collectAsStateWithLifecycle()
    val includedFolders by viewModel.includedFolders.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val discoveredFolders = remember(context) {
        MediaScanner.queryAvailableFolders(context)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings_import)) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // Auto-Import Section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                shape = MaterialTheme.shapes.large
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.import_auto_import)) },
                    supportingContent = { Text(stringResource(R.string.import_auto_import_summary)) },
                    trailingContent = {
                        Switch(
                            checked = autoImportEnabled,
                            onCheckedChange = null,
                        )
                    },
                    modifier = Modifier.toggleable(
                        value = autoImportEnabled,
                        role = androidx.compose.ui.semantics.Role.Switch,
                        onValueChange = { viewModel.setAutoImportEnabled(it) },
                    ),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            // Manual card import: opens the system folder picker. The
            // scan, confirmation and progress dialogs are hosted by
            // MainActivity, which observes the same OtgIngestManager.
            val pickCardFolder = rememberOtgCardFolderPicker { uri ->
                OtgIngestManager.getInstance(context).onDocumentTreeSelected(uri)
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                shape = MaterialTheme.shapes.large
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.otg_import_from_card_title)) },
                    supportingContent = { Text(stringResource(R.string.otg_import_from_card_summary)) },
                    leadingContent = {
                        Icon(Icons.Default.SdCard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable(onClick = pickCardFolder),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            // Included Gallery Folders Section
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.import_included_folders),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
                Text(
                    text = stringResource(R.string.import_included_folders_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )

                Spacer(Modifier.height(8.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column {
                        if (discoveredFolders.isEmpty()) {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.import_camera_folder)) },
                                supportingContent = { Text(stringResource(R.string.import_camera_folder_summary)) },
                                leadingContent = {
                                    Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                },
                                trailingContent = {
                                    Switch(checked = true, onCheckedChange = null, enabled = false)
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        } else {
                            discoveredFolders.forEachIndexed { index, folder ->
                                val isChecked = includedFolders.isEmpty() || includedFolders.contains(folder)
                                ListItem(
                                    headlineContent = { Text(folder, fontWeight = FontWeight.Medium) },
                                    leadingContent = {
                                        Icon(
                                            Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                        )
                                    },
                                    supportingContent = {
                                        Text(stringResource(if (isChecked) R.string.import_folder_visible else R.string.import_folder_hidden))
                                    },
                                    trailingContent = {
                                        Switch(
                                            checked = isChecked,
                                            onCheckedChange = null
                                        )
                                    },
                                    modifier = Modifier.toggleable(
                                        value = isChecked,
                                        role = androidx.compose.ui.semantics.Role.Switch,
                                        onValueChange = { viewModel.toggleFolderIncluded(folder, discoveredFolders) },
                                    ),
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )
                                if (index < discoveredFolders.size - 1) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
