package com.branchdam.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.branchdam.mobile.ui.theme.BranchDamTheme

private enum class SettingsPage {
    Categories,
    Connection,
    Sync,
    Import,
    Appearance,
    Advanced,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onScanQr: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel,
) {
    var currentPage by rememberSaveable { mutableStateOf(SettingsPage.Categories) }

    val resetTrigger by viewModel.navigationResetTrigger.collectAsStateWithLifecycle()
    var lastProcessedResetTrigger by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(resetTrigger) {
        if (resetTrigger > lastProcessedResetTrigger) {
            currentPage = SettingsPage.Categories
            lastProcessedResetTrigger = resetTrigger
        }
    }

    when (currentPage) {
        SettingsPage.Categories -> {
            val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
            val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
            val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

            Scaffold(
                topBar = {
                    CenterAlignedTopAppBar(
                        title = { Text("Settings") },
                        scrollBehavior = scrollBehavior
                    )
                },
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp),
                ) {
                    Spacer(Modifier.height(8.dp))

                    // Status Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = if (isConnected) "Connected to server" else "Disconnected",
                                    color = if (isConnected) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            supportingContent = {
                                if (serverUrl.isNotBlank()) {
                                    Text(
                                        text = serverUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            leadingContent = {
                                Surface(
                                    modifier = Modifier.size(10.dp),
                                    shape = CircleShape,
                                    color = if (isConnected) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.error,
                                ) {}
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }

                    SettingsCategoryHeader("General")
                    SettingsGroup {
                        SettingsCategoryRow(
                            title = "Connection",
                            subtitle = if (serverUrl.isNotBlank()) serverUrl else "Server URL, API key, and pairing",
                            icon = Icons.Default.Link,
                            iconColor = MaterialTheme.colorScheme.primary,
                            onClick = { currentPage = SettingsPage.Connection },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsCategoryRow(
                            title = "Sync",
                            subtitle = "Network, schedule, and battery",
                            icon = Icons.Default.Sync,
                            iconColor = MaterialTheme.colorScheme.secondary,
                            onClick = { currentPage = SettingsPage.Sync },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsCategoryRow(
                            title = "Import",
                            subtitle = "Camera roll auto-import",
                            icon = Icons.Default.CloudUpload,
                            iconColor = MaterialTheme.colorScheme.tertiary,
                            onClick = { currentPage = SettingsPage.Import },
                        )
                    }

                    SettingsCategoryHeader("System")
                    SettingsGroup {
                        SettingsCategoryRow(
                            title = "Appearance",
                            subtitle = "Theme and dynamic color",
                            icon = Icons.Default.Palette,
                            iconColor = MaterialTheme.colorScheme.secondary,
                            onClick = { currentPage = SettingsPage.Appearance },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsCategoryRow(
                            title = "Advanced",
                            subtitle = "Batch size, timeout, debounce",
                            icon = Icons.Default.Tune,
                            iconColor = MaterialTheme.colorScheme.primary,
                            onClick = { currentPage = SettingsPage.Advanced },
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    val context = androidx.compose.ui.platform.LocalContext.current
                    val hasLog = viewModel.hasDiagnosticLog()

                    Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                        Button(
                            onClick = { viewModel.shareDiagnosticLog(context) },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            enabled = hasLog,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Share Diagnostic Log", fontWeight = FontWeight.Bold)
                        }
                    }

                    Text(
                        text = "branchDAM Mobile ${viewModel.versionName} • Build ${viewModel.versionCode}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp, bottom = 8.dp),
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        SettingsPage.Connection -> {
            ConnectionSettingsScreen(
                onNavigateBack = { currentPage = SettingsPage.Categories },
                onScanQr = onScanQr,
                viewModel = viewModel,
            )
        }

        SettingsPage.Sync -> {
            SyncSettingsScreen(
                onNavigateBack = { currentPage = SettingsPage.Categories },
                viewModel = viewModel,
            )
        }

        SettingsPage.Import -> {
            ImportSettingsScreen(
                onNavigateBack = { currentPage = SettingsPage.Categories },
                viewModel = viewModel,
            )
        }

        SettingsPage.Appearance -> {
            AppearanceSettingsScreen(
                onNavigateBack = { currentPage = SettingsPage.Categories },
                viewModel = viewModel,
            )
        }

        SettingsPage.Advanced -> {
            AdvancedSettingsScreen(
                onNavigateBack = { currentPage = SettingsPage.Categories },
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsCategoryHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

@Composable
private fun SettingsCategoryRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle, maxLines = 1) },
        leadingContent = {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = iconColor.copy(alpha = 0.12f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = iconColor
                    )
                }
            }
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp)
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    BranchDamTheme {
        SettingsScreen(viewModel = viewModel())
    }
}
