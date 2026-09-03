package com.branchdam.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.branchdam.mobile.otg.OtgIngestFileError
import com.branchdam.mobile.otg.OtgIngestProgress
import com.branchdam.mobile.otg.OtgScanResult

@Composable
fun OtgImportConfirmationDialog(
    scanResult: OtgScanResult,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Column {
                Text(
                    text = "USB-C SD Card Detected",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = scanResult.deviceLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Found ${scanResult.totalCount} media items (${scanResult.formattedTotalSize}):",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (scanResult.rawCount > 0) {
                            Text(
                                text = "• RAW Photos: ${scanResult.rawCount}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (scanResult.jpegCount > 0) {
                            Text(
                                text = "• JPEGs / HEICs: ${scanResult.jpegCount}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (scanResult.videoCount > 0) {
                            Text(
                                text = "• Videos: ${scanResult.videoCount}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Confirm import to copy full-resolution masters into branchDAM local queue for sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Import to branchDAM")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Skip / Cancel")
            }
        }
    )
}

@Composable
fun OtgIngestProgressDialog(
    progress: OtgIngestProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = {},
        modifier = modifier,
        title = {
            Text(
                text = "Importing Media (${progress.currentFileIndex}/${progress.totalFiles})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { progress.percentage },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = progress.currentFileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun OtgIngestCompletedDialog(
    importedCount: Int,
    totalBytes: Long,
    onDismiss: () -> Unit,
    fileErrors: List<OtgIngestFileError> = emptyList(),
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                text = if (fileErrors.isEmpty()) "Import Complete" else "Import Finished With Errors",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (fileErrors.isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    text = "Successfully staged $importedCount items (${com.branchdam.mobile.otg.OtgMediaCandidate.formatBytes(totalBytes)}) to branchDAM queue.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (fileErrors.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "${fileErrors.size} item(s) skipped:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            fileErrors.forEach { err ->
                                Text(
                                    text = "• ${err.candidate.fileName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = err.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
fun OtgIngestErrorDialog(
    errorMessage: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                text = "Import Failed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
        },
        text = {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}
