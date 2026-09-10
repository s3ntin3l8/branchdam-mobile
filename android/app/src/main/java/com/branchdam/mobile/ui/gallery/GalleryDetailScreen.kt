package com.branchdam.mobile.ui.gallery

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryDetailScreen(
    mediaId: Long,
    onNavigateBack: () -> Unit,
    viewModel: GalleryViewModel = viewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val galleryItem = remember(items, mediaId) { viewModel.getItemById(mediaId) }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = galleryItem?.mediaItem?.displayName ?: "Asset Details",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (galleryItem != null && !galleryItem.isOffloaded) {
                        IconButton(onClick = {
                            viewModel.uploadItem(context, galleryItem.mediaItem) {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Enqueued for upload")
                                }
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = "Upload item"
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (galleryItem != null && !galleryItem.isOffloaded) {
                ExtendedFloatingActionButton(
                    onClick = {
                        viewModel.uploadItem(context, galleryItem.mediaItem) {
                            scope.launch {
                                snackbarHostState.showSnackbar("Enqueued for upload")
                            }
                        }
                    },
                    icon = { Icon(Icons.Default.CloudUpload, contentDescription = null) },
                    text = { Text("Upload to branchDAM") }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        if (galleryItem == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Asset not found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                // Media Preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (galleryItem.mediaItem.isVideo) {
                        // Intentional placeholder: video assets display a centered play affordance
                        // against a dark backdrop without decoding video frames inline.
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            color = Color.Black.copy(alpha = 0.6f),
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Video",
                                    tint = Color.White,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                    } else {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(Uri.parse(galleryItem.mediaItem.contentUri))
                                .crossfade(300)
                                .build(),
                            contentDescription = galleryItem.mediaItem.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Metadata Card
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Asset Information",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text(galleryItem.lineageStatus) }
                            )
                            SuggestionChip(
                                onClick = {},
                                label = {
                                    Text(
                                        when {
                                            galleryItem.mediaItem.isVideo -> "Video"
                                            galleryItem.mediaItem.isDng -> "RAW (DNG)"
                                            else -> "Image"
                                        }
                                    )
                                }
                            )
                            SuggestionChip(
                                onClick = {},
                                label = {
                                    Text(if (galleryItem.isOffloaded) "Offloaded" else "Local Only")
                                }
                            )
                        }

                        HorizontalDivider()

                        DetailRow(label = "File Name", value = galleryItem.mediaItem.displayName)
                        DetailRow(label = "Date Taken", value = formatDateTaken(galleryItem.mediaItem.dateTakenUnix))
                        DetailRow(label = "File Size", value = formatFileSize(galleryItem.mediaItem.sizeBytes))
                        DetailRow(label = "MIME Type", value = galleryItem.mediaItem.mimeType)
                        if (galleryItem.mediaItem.burstId != null) {
                            DetailRow(label = "Burst ID", value = galleryItem.mediaItem.burstId)
                        }
                        DetailRow(label = "Local Path", value = galleryItem.mediaItem.filePath.ifEmpty { "N/A" })
                        DetailRow(label = "Content URI", value = galleryItem.mediaItem.contentUri)
                    }
                }

                Spacer(Modifier.height(80.dp)) // Padding for FAB
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[digitGroups.coerceAtMost(units.size - 1)])
}

internal fun formatDateTaken(unixTimestampSecs: Long): String {
    if (unixTimestampSecs <= 0) return "Unknown"
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(unixTimestampSecs * 1000L))
}
