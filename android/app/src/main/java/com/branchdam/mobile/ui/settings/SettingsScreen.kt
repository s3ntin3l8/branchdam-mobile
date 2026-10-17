package com.branchdam.mobile.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.branchdam.mobile.R
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

    BackHandler(enabled = currentPage != SettingsPage.Categories) {
        currentPage = SettingsPage.Categories
    }

    when (currentPage) {
        SettingsPage.Categories -> {
            val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
            val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
            val repairDeadlineUnix by viewModel.repairDeadlineUnix.collectAsStateWithLifecycle()
            val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

            Scaffold(
                topBar = {
                    CenterAlignedTopAppBar(
                        title = { Text(stringResource(R.string.settings_title)) },
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
                                    text = stringResource(if (isConnected) R.string.settings_connected else R.string.settings_disconnected),
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
                                // Icon shape as well as colour: the status
                                // must not be conveyed by colour alone.
                                Icon(
                                    imageVector = if (isConnected) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = if (isConnected) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }

                    if (SettingsViewModel.shouldShowRepairPrompt(repairDeadlineUnix, System.currentTimeMillis())) {
                        RepairPromptCard(
                            deadlineUnix = repairDeadlineUnix,
                            onClick = { currentPage = SettingsPage.Connection },
                        )
                    }

                    SettingsCategoryHeader(stringResource(R.string.settings_category_general))
                    SettingsGroup {
                        SettingsCategoryRow(
                            title = stringResource(R.string.settings_connection),
                            subtitle = if (serverUrl.isNotBlank()) serverUrl else stringResource(R.string.settings_connection_summary),
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
                            title = stringResource(R.string.settings_sync),
                            subtitle = stringResource(R.string.settings_sync_summary),
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
                            title = stringResource(R.string.settings_import),
                            subtitle = stringResource(R.string.settings_import_summary),
                            icon = Icons.Default.CloudUpload,
                            iconColor = MaterialTheme.colorScheme.tertiary,
                            onClick = { currentPage = SettingsPage.Import },
                        )
                    }

                    SettingsCategoryHeader(stringResource(R.string.settings_category_system))
                    SettingsGroup {
                        SettingsCategoryRow(
                            title = stringResource(R.string.settings_appearance),
                            subtitle = stringResource(R.string.settings_appearance_summary),
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
                            title = stringResource(R.string.settings_advanced),
                            subtitle = stringResource(R.string.settings_advanced_summary),
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
                            Text(stringResource(R.string.settings_share_log), fontWeight = FontWeight.Bold)
                        }
                    }

                    Text(
                        text = stringResource(R.string.settings_version_footer, viewModel.versionName, viewModel.versionCode),
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

/**
 * The server rotated this device's API key but cannot deliver the new one
 * (#218): the user has to pair again before [deadlineUnix] (Unix seconds),
 * shown in the locale's medium date style. Tapping opens Connection, where
 * the QR scan is.
 */
@Composable
private fun RepairPromptCard(deadlineUnix: Long, onClick: () -> Unit) {
    val deadline = remember(deadlineUnix) {
        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale.getDefault())
            .format(java.util.Date(deadlineUnix * 1_000L))
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = stringResource(R.string.settings_repair_before, deadline),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold
                )
            },
            supportingContent = {
                Text(
                    text = stringResource(R.string.settings_repair_before_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            },
            leadingContent = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
            },
            modifier = Modifier.clickable(onClick = onClick),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
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
