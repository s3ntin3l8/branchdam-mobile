package com.branchdam.mobile.ui.gallery

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.branchdam.mobile.ui.components.ExifOrientationHelper
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
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

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showRawPreview by remember { mutableStateOf(false) }

    if (showDeleteConfirmDialog && galleryItem != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Photo Permanently?") },
            text = {
                Text("This will permanently delete ${galleryItem.mediaItem.displayName} from branchDAM and your local device. This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.deleteItem(context, galleryItem) {
                            onNavigateBack()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            DetailTopBar(
                galleryItem = galleryItem,
                onNavigateBack = onNavigateBack,
                onDelete = { showDeleteConfirmDialog = true },
                onUpload = {
                    if (galleryItem != null) {
                        if (galleryItem.isBackedUp || galleryItem.isOffloaded) {
                            scope.launch {
                                snackbarHostState.showSnackbar("Item is backed up to branchDAM")
                            }
                        } else {
                            viewModel.uploadItem(context, galleryItem.mediaItem) { success ->
                                scope.launch {
                                    if (success) {
                                        snackbarHostState.showSnackbar("Enqueued for upload")
                                    } else {
                                        snackbarHostState.showSnackbar("Failed to enqueue item for upload")
                                    }
                                }
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (galleryItem != null) {
                val fabIcon = when {
                    galleryItem.isBackedUp || galleryItem.isOffloaded -> Icons.Default.CloudDone
                    galleryItem.isPendingUpload -> Icons.Default.CloudSync
                    galleryItem.isUploadFailed -> Icons.Default.Warning
                    else -> Icons.Default.CloudUpload
                }
                val fabText = when {
                    galleryItem.isOffloaded -> "Offloaded to branchDAM"
                    galleryItem.isBackedUp -> "Backed Up to branchDAM"
                    galleryItem.isPendingUpload -> "Uploading to branchDAM..."
                    galleryItem.isUploadFailed -> "Retry Upload"
                    else -> "Upload to branchDAM"
                }
                ExtendedFloatingActionButton(
                    onClick = {
                        if (galleryItem.isBackedUp || galleryItem.isOffloaded) {
                            scope.launch {
                                snackbarHostState.showSnackbar("Item is backed up to branchDAM")
                            }
                        } else {
                            viewModel.uploadItem(context, galleryItem.mediaItem) { success ->
                                scope.launch {
                                    if (success) {
                                        snackbarHostState.showSnackbar("Enqueued for upload")
                                    } else {
                                        snackbarHostState.showSnackbar("Failed to enqueue item for upload")
                                    }
                                }
                            }
                        }
                    },
                    icon = { Icon(fabIcon, contentDescription = null) },
                    text = { Text(fabText) }
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
                val activeItem = if (showRawPreview && galleryItem.companionMediaItem != null) {
                    galleryItem.companionMediaItem!!
                } else {
                    galleryItem.primaryMediaItem
                }

                var isPlayingVideo by remember(activeItem.contentUri) { mutableStateOf(false) }

                // Dynamic Aspect Media Preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 280.dp, max = 460.dp)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (activeItem.isVideo && isPlayingVideo) {
                        val exoPlayer = remember(context, activeItem.contentUri) {
                            ExoPlayer.Builder(context).build().apply {
                                setMediaItem(Media3Item.fromUri(Uri.parse(activeItem.contentUri)))
                                prepare()
                                playWhenReady = true
                            }
                        }

                        DisposableEffect(exoPlayer) {
                            onDispose {
                                exoPlayer.release()
                            }
                        }

                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = true
                                    setShowNextButton(false)
                                    setShowPreviousButton(false)
                                    setShowFastForwardButton(false)
                                    setShowRewindButton(false)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (activeItem.isVideo) {
                        val videoRequest = remember(activeItem.contentUri) {
                            ImageRequest.Builder(context)
                                .data(Uri.parse(activeItem.contentUri))
                                .decoderFactory(VideoFrameDecoder.Factory())
                                .crossfade(true)
                                .build()
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { isPlayingVideo = true },
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = videoRequest,
                                contentDescription = activeItem.displayName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.6f),
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play Video",
                                        tint = Color.White,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        val imageRequest = remember(activeItem.contentUri) {
                            val builder = ImageRequest.Builder(context)
                                .data(Uri.parse(activeItem.contentUri))
                                .crossfade(200)
                            ExifOrientationHelper.applyExifOrientation(builder, context, activeItem.contentUri)
                            builder.build()
                        }
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = activeItem.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                if (galleryItem.companionMediaItem != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = !showRawPreview,
                            onClick = { showRawPreview = false },
                            label = { Text("JPEG") }
                        )
                        FilterChip(
                            selected = showRawPreview,
                            onClick = { showRawPreview = true },
                            label = { Text("RAW (DNG)") }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Metadata Card
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                            val backupChipText = when {
                                galleryItem.isOffloaded -> "Offloaded"
                                galleryItem.isBackedUp -> "Backed Up"
                                galleryItem.isPendingUpload -> "Pending Upload"
                                galleryItem.isUploadFailed -> "Upload Failed"
                                else -> "Local Only"
                            }
                            SuggestionChip(
                                onClick = {},
                                label = { Text(backupChipText) }
                            )
                        }

                        HorizontalDivider()

                        val backupStatusValue = when {
                            galleryItem.isOffloaded -> "Offloaded (Stored on branchDAM node)"
                            galleryItem.isBackedUp -> "Backed up to branchDAM"
                            galleryItem.isPendingUpload -> "Pending upload to branchDAM"
                            galleryItem.isUploadFailed -> "Upload failed (Will retry)"
                            else -> "Not backed up (Local only)"
                        }
                        DetailRow(label = "Backup Status", value = backupStatusValue)
                        DetailRow(label = "Primary File Name", value = galleryItem.primaryMediaItem.displayName)
                        if (galleryItem.companionMediaItem != null) {
                            DetailRow(label = "RAW Master File Name", value = galleryItem.companionMediaItem.displayName)
                            DetailRow(label = "RAW Master Size", value = formatFileSize(galleryItem.companionMediaItem.sizeBytes))
                        }
                        DetailRow(label = "Date Taken", value = formatDateTaken(galleryItem.primaryMediaItem.dateTakenUnix))
                        DetailRow(label = "File Size", value = formatFileSize(galleryItem.primaryMediaItem.sizeBytes))
                        DetailRow(label = "MIME Type", value = galleryItem.primaryMediaItem.mimeType)
                        if (galleryItem.primaryMediaItem.burstId != null) {
                            DetailRow(label = "Burst ID", value = galleryItem.primaryMediaItem.burstId)
                        }
                        DetailRow(label = "Local Path", value = galleryItem.primaryMediaItem.filePath.ifEmpty { "N/A" })
                        DetailRow(label = "Content URI", value = galleryItem.primaryMediaItem.contentUri)
                    }
                }

                Spacer(Modifier.height(80.dp))
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

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
private fun DetailTopBar(
    galleryItem: GalleryItem?,
    onNavigateBack: () -> Unit,
    onDelete: () -> Unit,
    onUpload: () -> Unit
) {
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
            if (galleryItem != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete item",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                val icon = when {
                    galleryItem.isBackedUp || galleryItem.isOffloaded -> Icons.Default.CloudDone
                    galleryItem.isPendingUpload -> Icons.Default.CloudSync
                    galleryItem.isUploadFailed -> Icons.Default.Warning
                    else -> Icons.Default.CloudUpload
                }
                IconButton(onClick = onUpload) {
                    Icon(
                        imageVector = icon,
                        contentDescription = "Upload status"
                    )
                }
            }
        }
    )
}

private fun launchVideoPlayback(context: android.content.Context, item: com.branchdam.mobile.observer.MediaItem) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(item.contentUri), item.mimeType.ifEmpty { "video/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No video player application found", Toast.LENGTH_SHORT).show()
    }
}
