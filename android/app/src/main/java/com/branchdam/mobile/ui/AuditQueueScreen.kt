package com.branchdam.mobile.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import com.branchdam.mobile.ui.components.ExifOrientationHelper
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.branchdam.mobile.ui.theme.BranchDamTheme

data class AuditCandidate(
    val edgeId: String,
    val masterFilename: String,
    val childFilename: String,
    val confidence: Double,
    val resolver: String,
    val masterUri: String? = null,
    val childUri: String? = null,
    val masterMimeType: String? = null,
    val childMimeType: String? = null,
) {
    val resolvedMasterUri: String?
        get() = masterUri ?: edgeId.split("|", limit = 2).getOrNull(0)?.takeIf { it.isNotBlank() }

    val resolvedChildUri: String?
        get() = childUri ?: edgeId.split("|", limit = 2).getOrNull(1)?.takeIf { it.isNotBlank() }
}

@Composable
fun AuditQueueScreen(
    candidates: List<AuditCandidate>,
    onConfirm: (AuditCandidate) -> Unit,
    onReject: (AuditCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = candidates.firstOrNull(),
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                    slideOutHorizontally { width -> -width } + fadeOut())
            } else {
                fadeIn() togetherWith fadeOut()
            }
        },
        label = "audit_card_transition"
    ) { current ->
        if (current == null) {
            Box(
                modifier = modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "All edge lineage candidates reviewed",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            AuditCard(
                candidate = current,
                onConfirm = { onConfirm(current) },
                onReject = { onReject(current) },
                modifier = modifier
            )
        }
    }
}

@Composable
private fun AuditCard(
    candidate: AuditCandidate,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var inspectUri by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val isWideLayout = maxWidth > 500.dp

            if (isWideLayout) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssetPreviewCard(
                        roleLabel = "MASTER",
                        filename = candidate.masterFilename,
                        contentUri = candidate.resolvedMasterUri,
                        mimeType = candidate.masterMimeType,
                        isMaster = true,
                        onInspect = { inspectUri = candidate.resolvedMasterUri },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )

                    LineageConnector(
                        confidence = candidate.confidence,
                        resolver = candidate.resolver,
                        isHorizontal = true
                    )

                    AssetPreviewCard(
                        roleLabel = "DERIVATIVE",
                        filename = candidate.childFilename,
                        contentUri = candidate.resolvedChildUri,
                        mimeType = candidate.childMimeType,
                        isMaster = false,
                        onInspect = { inspectUri = candidate.resolvedChildUri },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssetPreviewCard(
                        roleLabel = "MASTER",
                        filename = candidate.masterFilename,
                        contentUri = candidate.resolvedMasterUri,
                        mimeType = candidate.masterMimeType,
                        isMaster = true,
                        onInspect = { inspectUri = candidate.resolvedMasterUri },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )

                    LineageConnector(
                        confidence = candidate.confidence,
                        resolver = candidate.resolver,
                        isHorizontal = false
                    )

                    AssetPreviewCard(
                        roleLabel = "DERIVATIVE",
                        filename = candidate.childFilename,
                        contentUri = candidate.resolvedChildUri,
                        mimeType = candidate.childMimeType,
                        isMaster = false,
                        onInspect = { inspectUri = candidate.resolvedChildUri },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = onReject,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Reject")
            }

            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Confirm")
            }
        }
    }

    if (inspectUri != null) {
        FullImageComparisonDialog(
            candidate = candidate,
            initialUri = inspectUri,
            onDismiss = { inspectUri = null }
        )
    }
}

@Composable
private fun AssetPreviewCard(
    roleLabel: String,
    filename: String,
    contentUri: String?,
    mimeType: String?,
    isMaster: Boolean,
    onInspect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatBadge = remember(filename, mimeType) { detectFormatBadge(filename, mimeType) }
    val uri = contentUri?.takeIf { it.isNotBlank() }

    Card(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = uri != null, onClick = onInspect),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = Color.Black
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (uri != null) {
                val auditContext = LocalContext.current
                val auditRotation = remember(uri) {
                    ExifOrientationHelper.getExifRotationDegrees(auditContext, uri)
                }
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(Uri.parse(uri))
                        .crossfade(true)
                        .build(),
                    contentDescription = "$roleLabel: $filename",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().rotate(auditRotation),
                    loading = {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.5.dp
                            )
                        }
                    },
                    error = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = formatBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = formatBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.8f)
                            )
                        )
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = filename,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
                shape = RoundedCornerShape(6.dp),
                color = if (isMaster) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f)
                        else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.95f),
                contentColor = if (isMaster) MaterialTheme.colorScheme.onPrimaryContainer
                               else MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (isMaster) Icons.Default.RawOn else Icons.Default.Photo,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "$roleLabel • $formatBadge",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (uri != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.5f),
                    contentColor = Color.White
                ) {
                    Icon(
                        imageVector = Icons.Default.ZoomIn,
                        contentDescription = "Inspect image",
                        modifier = Modifier
                            .padding(5.dp)
                            .size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LineageConnector(
    confidence: Double,
    resolver: String,
    isHorizontal: Boolean,
    modifier: Modifier = Modifier,
) {
    val confidencePct = (confidence * 100).toInt()
    val isHighConfidence = confidence >= 0.95

    if (isHorizontal) {
        Column(
            modifier = modifier.padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
        ) {
            Surface(
                color = if (isHighConfidence) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "$confidencePct%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isHighConfidence) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Lineage direction",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Text(
                    text = resolver,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Text(
                    text = resolver,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Icon(
                    imageVector = Icons.Default.ArrowDownward,
                    contentDescription = "Lineage direction",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Surface(
                color = if (isHighConfidence) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "$confidencePct% match",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isHighConfidence) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun FullImageComparisonDialog(
    candidate: AuditCandidate,
    initialUri: String?,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(if (initialUri == candidate.resolvedChildUri) 1 else 0) }
    val masterUri = candidate.resolvedMasterUri
    val childUri = candidate.resolvedChildUri

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Visual Asset Comparison",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Master: ${candidate.masterFilename}") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Derivative: ${candidate.childFilename}") }
                    )
                }

                Spacer(Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    val activeUri = if (selectedTab == 0) masterUri else childUri
                    val activeFilename = if (selectedTab == 0) candidate.masterFilename else candidate.childFilename

                    if (activeUri != null) {
                        val dialogContext = LocalContext.current
                        val dialogRotation = remember(activeUri) {
                            ExifOrientationHelper.getExifRotationDegrees(dialogContext, activeUri)
                        }
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(Uri.parse(activeUri))
                                .crossfade(true)
                                .build(),
                            contentDescription = activeFilename,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().rotate(dialogRotation)
                        )
                    } else {
                        Text(
                            text = "No image preview available for $activeFilename",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Match Confidence: ${(candidate.confidence * 100).toInt()}% (${candidate.resolver})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Done")
                    }
                }
            }
        }
    }
}

private fun detectFormatBadge(filename: String, mimeType: String?): String {
    val lowerFile = filename.lowercase()
    val lowerMime = mimeType?.lowercase() ?: ""
    return when {
        lowerFile.endsWith(".dng") || lowerMime.contains("dng") || lowerMime.contains("raw") -> "RAW (DNG)"
        lowerFile.endsWith(".jpg") || lowerFile.endsWith(".jpeg") || lowerMime.contains("jpeg") -> "JPEG"
        lowerFile.endsWith(".heic") || lowerFile.endsWith(".heif") || lowerMime.contains("heic") -> "HEIC"
        lowerFile.endsWith(".png") || lowerMime.contains("png") -> "PNG"
        lowerFile.endsWith(".mp4") || lowerFile.endsWith(".mov") || lowerMime.contains("video") -> "VIDEO"
        else -> filename.substringAfterLast('.').uppercase().takeIf { it.length in 2..5 } ?: "IMAGE"
    }
}

@Preview(showBackground = true)
@Composable
fun AuditCardPreview() {
    BranchDamTheme {
        AuditCard(
            candidate = AuditCandidate(
                edgeId = "1",
                masterFilename = "IMG_001.DNG",
                childFilename = "IMG_001.JPG",
                confidence = 0.95,
                resolver = "android_camera_pair"
            ),
            onConfirm = {},
            onReject = {}
        )
    }
}
