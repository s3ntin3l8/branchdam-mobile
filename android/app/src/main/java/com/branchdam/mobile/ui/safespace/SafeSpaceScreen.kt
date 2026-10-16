package com.branchdam.mobile.ui.safespace

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.branchdam.mobile.R
import com.branchdam.mobile.ui.components.MediaDeleteConsentEffect
import com.branchdam.mobile.ui.components.quantityStringResource
import com.branchdam.mobile.ui.theme.BranchDamTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafeSpaceScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SafeSpaceViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingDeleteConsent by viewModel.pendingDeleteConsent.collectAsStateWithLifecycle()

    // API 29+: MediaStore only deletes camera-roll items this app did not
    // create after the user confirms in a system dialog.
    MediaDeleteConsentEffect(
        pending = pendingDeleteConsent,
        onLaunched = viewModel::onDeleteConsentLaunched,
        onResult = viewModel::onDeleteConsentResult,
    )

    val reclaimableMb = uiState.reclaimableBytes / (1024L * 1024L)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.safespace_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
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
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Storage Gauge Card
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.safespace_megabytes, reclaimableMb),
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (uiState.isLoading) {
                                stringResource(R.string.safespace_checking)
                            } else {
                                quantityStringResource(R.plurals.safespace_verified_ready, uiState.verifiedCount, uiState.verifiedCount)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!uiState.isLoading && uiState.notYetVerifiedCount > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = quantityStringResource(R.plurals.safespace_not_yet_verified, uiState.notYetVerifiedCount, uiState.notYetVerifiedCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                uiState.loadError?.let { error ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                AnimatedContent(
                    targetState = uiState.isReclaiming,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "reclaim_status"
                ) { isReclaiming ->
                    if (isReclaiming) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp),
                                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                            Text(
                                text = stringResource(R.string.safespace_optimizing),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        uiState.reclaimMessage?.let { msg ->
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = msg,
                                    modifier = Modifier.padding(14.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Fixed Action Button
            Button(
                onClick = { viewModel.reclaim() },
                enabled = uiState.verifiedCount > 0 && !uiState.isReclaiming && !uiState.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.safespace_free_up, reclaimableMb),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SafeSpacePreview() {
    BranchDamTheme {
        SafeSpaceScreen(onNavigateBack = {})
    }
}
