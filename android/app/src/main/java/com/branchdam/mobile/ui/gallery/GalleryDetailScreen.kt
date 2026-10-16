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
import androidx.compose.ui.res.stringResource
import com.branchdam.mobile.R
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
    val pendingDeleteConsent by viewModel.pendingDeleteConsent.collectAsStateWithLifecycle()

    // API 29+: MediaStore deletes camera-roll items this app did not create
    // only after the user confirms in a system dialog.
    com.branchdam.mobile.ui.components.MediaDeleteConsentEffect(
        pending = pendingDeleteConsent,
        onLaunched = viewModel::onDeleteConsentLaunched,
        onResult = viewModel::onDeleteConsentResult,
    )

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showRawPreview by remember { mutableStateOf(false) }

    if (showDeleteConfirmDialog && galleryItem != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text(stringResource(R.string.gallery_detail_delete_title)) },
            text = {
                Text(stringResource(R.string.gallery_detail_delete_body, galleryItem.mediaItem.displayName))
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
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
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
                                snackbarHostState.showSnackbar(context.getString(R.string.gallery_detail_already_backed_up))
                            }
                        } else {
                            viewModel.uploadItem(context, galleryItem.mediaItem) { success ->
                                scope.launch {
                                    if (success) {
                                        snackbarHostState.showSnackbar(context.getString(R.string.gallery_detail_enqueued))
                                    } else {
                                        snackbarHostState.showSnackbar(context.getString(R.string.gallery_detail_enqueue_failed))
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
                    galleryItem.isOffloaded -> stringResource(R.string.gallery_detail_fab_offloaded)
                    galleryItem.isBackedUp -> stringResource(R.string.gallery_detail_fab_backed_up)
                    galleryItem.isPendingUpload -> stringResource(R.string.gallery_detail_fab_uploading)
                    galleryItem.isUploadFailed -> stringResource(R.string.gallery_detail_fab_retry)
                    else -> stringResource(R.string.gallery_detail_fab_upload)
                }
                ExtendedFloatingActionButton(
                    onClick = {
                        if (galleryItem.isBackedUp || galleryItem.isOffloaded) {
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.gallery_detail_already_backed_up))
                            }
                        } else {
                            viewModel.uploadItem(context, galleryItem.mediaItem) { success ->
                                scope.launch {
                                    if (success) {
                                        snackbarHostState.showSnackbar(context.getString(R.string.gallery_detail_enqueued))
                                    } else {
                                        snackbarHostState.showSnackbar(context.getString(R.string.gallery_detail_enqueue_failed))
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
                    text = stringResource(R.string.gallery_detail_not_found),
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

                        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                        DisposableEffect(exoPlayer, lifecycleOwner) {
                            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                                if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                                    exoPlayer.playWhenReady = false
                                }
                            }
                            lifecycleOwner.lifecycle.addObserver(observer)
                            onDispose {
                                lifecycleOwner.lifecycle.removeObserver(observer)
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
                                        contentDescription = stringResource(R.string.gallery_detail_play_video),
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
                            ExifOrientationHelper.applyExifOrientation(builder, context, activeItem.contentUri, activeItem.mimeType)
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
                            label = { Text(stringResource(R.string.gallery_detail_chip_jpeg)) }
                        )
                        FilterChip(
                            selected = showRawPreview,
                            onClick = { showRawPreview = true },
                            label = { Text(stringResource(R.string.gallery_detail_chip_raw)) }
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
                            text = stringResource(R.string.gallery_detail_info_title),
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
                                            galleryItem.mediaItem.isVideo -> stringResource(R.string.gallery_detail_type_video)
                                            galleryItem.mediaItem.isDng -> stringResource(R.string.gallery_detail_chip_raw)
                                            else -> stringResource(R.string.gallery_detail_type_image)
                                        }
                                    )
                                }
                            )
                            val backupChipText = when {
                                galleryItem.isOffloaded -> stringResource(R.string.gallery_badge_offloaded)
                                galleryItem.isBackedUp -> stringResource(R.string.gallery_badge_backed_up)
                                galleryItem.isPendingUpload -> stringResource(R.string.gallery_badge_pending)
                                galleryItem.isUploadFailed -> stringResource(R.string.gallery_badge_failed)
                                else -> stringResource(R.string.gallery_detail_local_only)
                            }
                            SuggestionChip(
                                onClick = {},
                                label = { Text(backupChipText) }
                            )
                        }

                        HorizontalDivider()

                        val backupStatusValue = when {
                            galleryItem.isOffloaded -> stringResource(R.string.gallery_detail_status_offloaded)
                            galleryItem.isBackedUp -> stringResource(R.string.gallery_detail_status_backed_up)
                            galleryItem.isPendingUpload -> stringResource(R.string.gallery_detail_status_pending)
                            galleryItem.isUploadFailed -> stringResource(R.string.gallery_detail_status_failed)
                            else -> stringResource(R.string.gallery_detail_status_local)
                        }
                        DetailRow(label = stringResource(R.string.gallery_detail_backup_status), value = backupStatusValue)
                        DetailRow(label = stringResource(R.string.gallery_detail_primary_name), value = galleryItem.primaryMediaItem.displayName)
                        if (galleryItem.companionMediaItem != null) {
                            DetailRow(label = stringResource(R.string.gallery_detail_raw_name), value = galleryItem.companionMediaItem.displayName)
                            DetailRow(label = stringResource(R.string.gallery_detail_raw_size), value = formatFileSize(galleryItem.companionMediaItem.sizeBytes))
                        }
                        DetailRow(label = stringResource(R.string.gallery_detail_date_taken), value = formatDateTaken(galleryItem.primaryMediaItem.dateTakenUnix))
                        DetailRow(label = stringResource(R.string.gallery_detail_file_size), value = formatFileSize(galleryItem.primaryMediaItem.sizeBytes))
                        DetailRow(label = stringResource(R.string.gallery_detail_mime_type), value = galleryItem.primaryMediaItem.mimeType)
                        if (galleryItem.primaryMediaItem.burstId != null) {
                            DetailRow(label = stringResource(R.string.gallery_detail_burst_id), value = galleryItem.primaryMediaItem.burstId)
                        }
                        DetailRow(label = stringResource(R.string.gallery_detail_local_path), value = galleryItem.primaryMediaItem.filePath.ifEmpty { stringResource(R.string.gallery_detail_not_available) })
                        DetailRow(label = stringResource(R.string.gallery_detail_content_uri), value = galleryItem.primaryMediaItem.contentUri)
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
                text = galleryItem?.mediaItem?.displayName ?: stringResource(R.string.gallery_detail_title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back)
                )
            }
        },
        actions = {
            if (galleryItem != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.gallery_detail_delete_item),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                val icon = when {
                    galleryItem.isBackedUp || galleryItem.isOffloaded -> Icons.Default.CloudDone
                    galleryItem.isPendingUpload -> Icons.Default.CloudSync
                    galleryItem.isUploadFailed -> Icons.Default.Warning
                    else -> Icons.Default.CloudUpload
                }
                val uploadDescription = when {
                    galleryItem.isOffloaded -> stringResource(R.string.gallery_detail_status_offloaded)
                    galleryItem.isBackedUp -> stringResource(R.string.gallery_detail_status_backed_up)
                    galleryItem.isPendingUpload -> stringResource(R.string.gallery_detail_status_pending)
                    galleryItem.isUploadFailed -> stringResource(R.string.gallery_detail_fab_retry)
                    else -> stringResource(R.string.gallery_detail_fab_upload)
                }
                IconButton(onClick = onUpload) {
                    Icon(
                        imageVector = icon,
                        contentDescription = uploadDescription
                    )
                }
            }
        }
    )
}
