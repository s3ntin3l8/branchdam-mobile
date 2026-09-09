package com.branchdam.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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

            Scaffold(
                topBar = { TopAppBar(title = { Text("Settings") }) },
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(12.dp),
                            shape = MaterialTheme.shapes.small,
                            color = if (isConnected) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.error,
                        ) {}
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (isConnected) "Connected" else "Disconnected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsCategoryRow(
                            title = "Connection",
                            subtitle = "Server URL, API key, and pairing",
                            icon = Icons.Default.Link,
                            onClick = { currentPage = SettingsPage.Connection },
                        )
                        SettingsCategoryRow(
                            title = "Sync",
                            subtitle = "Network, schedule, and battery",
                            icon = Icons.Default.Sync,
                            onClick = { currentPage = SettingsPage.Sync },
                        )
                        SettingsCategoryRow(
                            title = "Import",
                            subtitle = "Camera roll auto-import",
                            icon = Icons.Default.CloudUpload,
                            onClick = { currentPage = SettingsPage.Import },
                        )
                        SettingsCategoryRow(
                            title = "Appearance",
                            subtitle = "Theme and dynamic color",
                            icon = Icons.Default.Palette,
                            onClick = { currentPage = SettingsPage.Appearance },
                        )
                        SettingsCategoryRow(
                            title = "Advanced",
                            subtitle = "Batch size, timeout, debounce",
                            icon = Icons.Default.Tune,
                            onClick = { currentPage = SettingsPage.Advanced },
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    val context = androidx.compose.ui.platform.LocalContext.current
                    val hasLog = viewModel.hasDiagnosticLog()
                    Button(
                        onClick = { viewModel.shareDiagnosticLog(context) },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        enabled = hasLog,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text("Share Diagnostic Log")
                    }

                    Spacer(Modifier.weight(1f))

                    Text(
                        text = "branchDAM Mobile ${viewModel.versionName} (build ${viewModel.versionCode})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
private fun SettingsCategoryRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
