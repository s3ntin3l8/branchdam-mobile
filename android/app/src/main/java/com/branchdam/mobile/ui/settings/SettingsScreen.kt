package com.branchdam.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

            Scaffold(
                topBar = {
                    LargeTopAppBar(
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
                        .padding(bottom = 24.dp),
                ) {
                    // Status Row (Pixel style: simple, no card)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(10.dp),
                            shape = CircleShape,
                            color = if (isConnected) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.error,
                        ) {}
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (isConnected) "Connected to server" else "Disconnected",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isConnected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                        )
                    }

                    SettingsCategoryHeader("General")
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

                    SettingsCategoryHeader("System")
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

                    Spacer(Modifier.height(16.dp))

                    val context = androidx.compose.ui.platform.LocalContext.current
                    val hasLog = viewModel.hasDiagnosticLog()

                    // Standard Pixel-style button
                    Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                        Button(
                            onClick = { viewModel.shareDiagnosticLog(context) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            enabled = hasLog,
                            shape = RoundedCornerShape(28.dp), // Pill shape
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Text("Share Diagnostic Log")
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    Text(
                        text = "branchDAM Mobile ${viewModel.versionName}\nBuild ${viewModel.versionCode}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 32.dp, bottom = 16.dp),
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
private fun SettingsCategoryHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    )
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
        color = Color.Transparent // Pixel style: no background for list items
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
