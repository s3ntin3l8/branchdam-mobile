package com.branchdam.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.branchdam.mobile.observer.MediaScanner

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
                title = { Text("Import & Folders") },
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
                    headlineContent = { Text("Auto-import Camera Roll") },
                    supportingContent = { Text("Automatically enqueue new photos for upload") },
                    trailingContent = {
                        Switch(
                            checked = autoImportEnabled,
                            onCheckedChange = { viewModel.setAutoImportEnabled(it) },
                        )
                    },
                    modifier = Modifier.clickable { viewModel.setAutoImportEnabled(!autoImportEnabled) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            // Included Gallery Folders Section
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Included Gallery Folders",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
                Text(
                    text = "Select which MediaStore folders appear in your main Gallery view by default.",
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
                                headlineContent = { Text("Camera") },
                                supportingContent = { Text("Default system camera roll") },
                                leadingContent = {
                                    Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                },
                                trailingContent = {
                                    Switch(checked = true, onCheckedChange = {})
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
                                        Text(if (isChecked) "Visible in Gallery" else "Hidden from Gallery")
                                    },
                                    trailingContent = {
                                        Switch(
                                            checked = isChecked,
                                            onCheckedChange = { viewModel.toggleFolderIncluded(folder, discoveredFolders) }
                                        )
                                    },
                                    modifier = Modifier.clickable { viewModel.toggleFolderIncluded(folder, discoveredFolders) },
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
