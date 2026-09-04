package com.branchdam.mobile.ui.lineage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.branchdam.mobile.ui.AuditQueueScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineageScreen(
    onNavigateToSafeSpace: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LineageViewModel = viewModel(),
) {
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lineage Audit") },
                actions = {
                    IconButton(onClick = onNavigateToSafeSpace) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Safe Space")
                    }
                },
            )
        },
        modifier = modifier,
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (candidates.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No pending lineage candidates",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            AuditQueueScreen(
                candidates = candidates,
                onConfirm = { viewModel.confirm(it) },
                onReject = { viewModel.reject(it) },
                modifier = Modifier.padding(padding),
            )
        }
    }
}
