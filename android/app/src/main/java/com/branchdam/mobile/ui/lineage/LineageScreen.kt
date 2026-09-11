package com.branchdam.mobile.ui.lineage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.branchdam.mobile.ui.AuditQueueScreen
import com.branchdam.mobile.ui.theme.BranchDamTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineageScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateToSafeSpace: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LineageViewModel = viewModel(),
) {
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val isExpanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

    Scaffold(
        topBar = {
            if (!isExpanded) {
                CenterAlignedTopAppBar(
                    title = { Text("Lineage Audit") },
                    actions = {
                        IconButton(onClick = { viewModel.loadCandidates() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                        IconButton(onClick = onNavigateToSafeSpace) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Safe Space")
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { padding ->
        if (isExpanded) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Left Panel: Title and Actions
                Column(
                    modifier = Modifier
                        .width(300.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Lineage Audit",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Review and verify lineage pairs detected by the engine.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { viewModel.loadCandidates() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Refresh List")
                    }
                    OutlinedButton(
                        onClick = onNavigateToSafeSpace,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Safe Space")
                    }
                }

                // Right Panel: Audit Queue
                Box(modifier = Modifier.weight(1f).padding(padding)) {
                    LineageContent(
                        isLoading = isLoading,
                        loadError = loadError,
                        candidates = candidates,
                        onConfirm = viewModel::confirm,
                        onReject = viewModel::reject,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        } else {
            LineageContent(
                isLoading = isLoading,
                loadError = loadError,
                candidates = candidates,
                onConfirm = viewModel::confirm,
                onReject = viewModel::reject,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun LineageContent(
    isLoading: Boolean,
    loadError: String?,
    candidates: List<com.branchdam.mobile.ui.AuditCandidate>,
    onConfirm: (com.branchdam.mobile.ui.AuditCandidate) -> Unit,
    onReject: (com.branchdam.mobile.ui.AuditCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        isLoading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        loadError != null -> {
            Box(
                modifier = modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    loadError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        candidates.isEmpty() -> {
            Box(
                modifier = modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No pending lineage candidates",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> {
            AuditQueueScreen(
                candidates = candidates,
                onConfirm = onConfirm,
                onReject = onReject,
                modifier = modifier,
            )
        }
    }
}

@OptIn(androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class)
@Preview(showBackground = true)
@Composable
fun LineageScreenPreview() {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val windowSizeClass = if (activity != null) calculateWindowSizeClass(activity) else {
        WindowSizeClass.calculateFromSize(androidx.compose.ui.unit.DpSize(400.dp, 800.dp))
    }
    BranchDamTheme {
        LineageScreen(
            windowSizeClass = windowSizeClass,
            onNavigateToSafeSpace = {}
        )
    }
}
