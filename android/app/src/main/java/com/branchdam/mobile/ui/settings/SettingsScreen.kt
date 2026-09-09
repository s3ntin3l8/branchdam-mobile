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
                        .padding(bottom = 24.dp),
                ) {
                    // Status Row
                    ListItem(
                        headlineContent = {
                            Text(
                                if (isConnected) "Connected to server" else "Disconnected",
                                color = if (isConnected) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.error
                            )
                        },
                        leadingContent = {
                            Surface(
                                modifier = Modifier.size(12.dp),
                                shape = CircleShape,
                                color = if (isConnected) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.error,
                            ) {}
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

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

                    Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                        Button(
                            onClick = { viewModel.shareDiagnosticLog(context) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            enabled = hasLog,
                            shape = MaterialTheme.shapes.extraLarge,
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
            .padding(horizontal = 16.dp, vertical = 16.dp)
    )
}

@Composable
private fun SettingsCategoryRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    )
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    BranchDamTheme {
        SettingsScreen(viewModel = viewModel())
    }
}
