package com.branchdam.mobile.ui.sync

import android.text.format.DateUtils
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.branchdam.mobile.ui.theme.BranchDamTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusScreen(
    modifier: Modifier = Modifier,
    viewModel: SyncStatusViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Sync Status") }) },
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                ListItem(
                    headlineContent = {
                        Text(
                            if (uiState.isServerReachable) "Connected to server" else "Server unreachable",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    supportingContent = {
                        if (uiState.isConnected && !uiState.isServerReachable) {
                            Text(
                                "Local engine is ready, but server handshake failed.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    leadingContent = {
                        PulsingConnectionDot(isReachable = uiState.isServerReachable)
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large
            ) {
                Column {
                    ListItem(
                        headlineContent = { Text(uiState.workerState) },
                        overlineContent = { Text("Worker State") },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ListItem(
                        headlineContent = {
                            Text(
                                if (uiState.lastSyncTime > 0) {
                                    DateUtils.getRelativeTimeSpanString(
                                        uiState.lastSyncTime,
                                        System.currentTimeMillis(),
                                        DateUtils.MINUTE_IN_MILLIS,
                                    ).toString()
                                } else {
                                    "Never synced"
                                }
                            )
                        },
                        overlineContent = { Text("Last Sync") },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = { viewModel.triggerSync() },
                enabled = uiState.isServerReachable && !uiState.isSyncing,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.large
            ) {
                AnimatedContent(
                    targetState = uiState.isSyncing,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "sync_button_content"
                ) { isSyncing ->
                    if (isSyncing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Syncing...")
                        }
                    } else {
                        Text("Sync Now")
                    }
                }
            }

            OutlinedButton(
                onClick = { viewModel.refresh() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.large
            ) {
                Text("Refresh Status")
            }
        }
    }
}

@Composable
private fun PulsingConnectionDot(isReachable: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dot_scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dot_alpha"
    )

    val color = if (isReachable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp)) {
        Surface(
            modifier = Modifier
                .size(10.dp)
                .scale(scale)
                .alpha(alpha),
            shape = CircleShape,
            color = color
        ) {}
        Surface(
            modifier = Modifier.size(10.dp),
            shape = CircleShape,
            color = color
        ) {}
    }
}

@Preview(showBackground = true)
@Composable
fun SyncStatusPreview() {
    BranchDamTheme {
        SyncStatusScreen()
    }
}
