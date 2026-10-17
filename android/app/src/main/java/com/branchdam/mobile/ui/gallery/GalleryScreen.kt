package com.branchdam.mobile.ui.gallery

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.branchdam.mobile.MediaAccessLevel
import com.branchdam.mobile.R
import com.branchdam.mobile.ui.components.*
import com.branchdam.mobile.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    modifier: Modifier = Modifier,
    viewModel: GalleryViewModel = viewModel(),
    onNavigateToDetail: (Long) -> Unit = {},
    onNavigateToSafeSpace: () -> Unit = {},
    mediaAccess: MediaAccessLevel = MediaAccessLevel.FULL,
    mediaAccessGeneration: Int = 0,
    onSelectMorePhotos: () -> Unit = {},
) {
    val rawItems by viewModel.items.collectAsStateWithLifecycle()
    val displayedItems by viewModel.displayedItems.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val selectedItemIds by viewModel.selectedItemIds.collectAsStateWithLifecycle()
    val pendingDeleteConsent by viewModel.pendingDeleteConsent.collectAsStateWithLifecycle()

    // API 29+: MediaStore deletes camera-roll items this app did not create
    // only after the user confirms in a system dialog.
    com.branchdam.mobile.ui.components.MediaDeleteConsentEffect(
        pending = pendingDeleteConsent,
        onLaunched = viewModel::onDeleteConsentLaunched,
        onResult = viewModel::onDeleteConsentResult,
    )

    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val selectedFolder by viewModel.selectedFolder.collectAsStateWithLifecycle()
    val noFoldersSelected by viewModel.noFoldersSelected.collectAsStateWithLifecycle()
    val availableFolders by viewModel.availableFolders.collectAsStateWithLifecycle()
    val selectedSortProperty by viewModel.selectedSortProperty.collectAsStateWithLifecycle()
    val selectedSortDirection by viewModel.selectedSortDirection.collectAsStateWithLifecycle()

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val state = rememberPullToRefreshState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val isSelectionMode = selectedItemIds.isNotEmpty()

    // A permission dialog returned (e.g. the user picked a different set
    // of photos): reload once. Saved across navigation so coming back to
    // the gallery does not reload for an already-handled change.
    var handledAccessGeneration by rememberSaveable { mutableIntStateOf(mediaAccessGeneration) }
    LaunchedEffect(mediaAccessGeneration) {
        if (mediaAccessGeneration != handledAccessGeneration) {
            handledAccessGeneration = mediaAccessGeneration
            viewModel.loadItems()
        }
    }

    LaunchedEffect(loadError) {
        val currentError = loadError
        if (currentError != null && rawItems.isNotEmpty()) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = currentError,
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text(stringResource(R.string.gallery_delete_title)) },
            text = {
                Text(
                    quantityStringResource(R.plurals.gallery_delete_body, selectedItemIds.size, selectedItemIds.size)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.deleteSelectedItems(context) { count ->
                            scope.launch {
                                snackbarHostState.showSnackbar(context.resources.getQuantityString(R.plurals.gallery_deleted_snackbar, count, count))
                            }
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
            if (isSelectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.gallery_selected_count, selectedItemIds.size),
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.gallery_clear_selection)
                            )
                        }
                    },
                    actions = {
                        val selectableCount = remember(displayedItems) { displayedItems.count { !it.isOffloaded } }
                        val isAllSelectableSelected = selectableCount > 0 && selectedItemIds.size == selectableCount
                        IconButton(onClick = {
                            if (isAllSelectableSelected) {
                                viewModel.clearSelection()
                            } else {
                                viewModel.selectAll()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.SelectAll,
                                contentDescription = stringResource(if (isAllSelectableSelected) R.string.gallery_deselect_all else R.string.gallery_select_all)
                            )
                        }
                        IconButton(onClick = { showDeleteConfirmDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.gallery_delete_selected),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                viewModel.uploadSelectedItems(context) { count ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(context.resources.getQuantityString(R.plurals.gallery_enqueued_snackbar, count, count))
                                    }
                                }
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.gallery_upload))
                        }
                    }
                )
            } else {
                var showFilterMenu by remember { mutableStateOf(false) }
                var showSortMenu by remember { mutableStateOf(false) }
                val isFilterActive = selectedFilter != GalleryFilter.ALL || selectedFolder != ALL_FOLDERS

                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.gallery_title)) },
                    actions = {
                        Box {
                            IconButton(onClick = { showFilterMenu = true }) {
                                if (isFilterActive) {
                                    BadgedBox(
                                        badge = { Badge() }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Tune,
                                            contentDescription = stringResource(R.string.gallery_filter_media),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = stringResource(R.string.gallery_filter_media)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = showFilterMenu,
                                onDismissRequest = { showFilterMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.gallery_filter_folder), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                                    enabled = false,
                                    onClick = {}
                                )
                                availableFolders.forEach { folder ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                if (folder == selectedFolder) {
                                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                                } else {
                                                    Spacer(Modifier.width(16.dp))
                                                }
                                                Text(if (folder == ALL_FOLDERS) stringResource(R.string.gallery_filter_all_folders) else folder)
                                            }
                                        },
                                        onClick = {
                                            viewModel.setFolder(folder)
                                            showFilterMenu = false
                                        }
                                    )
                                }

                                HorizontalDivider()

                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.gallery_filter_media_type), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                                    enabled = false,
                                    onClick = {}
                                )
                                GalleryFilter.entries.forEach { filter ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                if (selectedFilter == filter) {
                                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                                } else {
                                                    Spacer(Modifier.width(16.dp))
                                                }
                                                Text(stringResource(filter.labelRes))
                                            }
                                        },
                                        onClick = {
                                            viewModel.setFilter(filter)
                                            showFilterMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = stringResource(R.string.gallery_sort_media)
                                )
                            }

                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.gallery_sort_date_newest)) },
                                    onClick = {
                                        viewModel.setSort(GallerySortProperty.DATE, GallerySortDirection.DESC)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.gallery_sort_date_oldest)) },
                                    onClick = {
                                        viewModel.setSort(GallerySortProperty.DATE, GallerySortDirection.ASC)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.gallery_sort_size_largest)) },
                                    onClick = {
                                        viewModel.setSort(GallerySortProperty.SIZE, GallerySortDirection.DESC)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.gallery_sort_size_smallest)) },
                                    onClick = {
                                        viewModel.setSort(GallerySortProperty.SIZE, GallerySortDirection.ASC)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.gallery_sort_name_az)) },
                                    onClick = {
                                        viewModel.setSort(GallerySortProperty.NAME, GallerySortDirection.ASC)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.gallery_sort_name_za)) },
                                    onClick = {
                                        viewModel.setSort(GallerySortProperty.NAME, GallerySortDirection.DESC)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }

                        IconButton(onClick = onNavigateToSafeSpace) {
                            Icon(
                                imageVector = Icons.Default.CleaningServices,
                                contentDescription = stringResource(R.string.gallery_free_up_storage)
                            )
                        }
                    }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            PullToRefreshBox(
                isRefreshing = isLoading,
                onRefresh = { viewModel.loadItems() },
                state = state,
                modifier = Modifier.fillMaxSize()
            ) {
                val currentError = loadError
                when {
                    isLoading && rawItems.isEmpty() -> {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 110.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            userScrollEnabled = false
                        ) {
                            items(12) {
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(2.dp))
                                        .shimmer()
                                )
                            }
                        }
                    }
                    currentError != null && rawItems.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                currentError,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    displayedItems.isEmpty() -> {
                        // "All folders" with every folder unchecked in
                        // Settings is empty by choice, not for want of media.
                        if (noFoldersSelected && selectedFolder == ALL_FOLDERS) {
                            EmptyGalleryState(
                                title = stringResource(R.string.gallery_no_folders_title),
                                body = stringResource(R.string.gallery_no_folders_body),
                            )
                        } else {
                            EmptyGalleryState()
                        }
                    }
                    else -> {
                        val gridState = rememberLazyGridState()
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(minSize = 110.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(displayedItems, key = { it.mediaItem.id }) { galleryItem ->
                                val isSelected = selectedItemIds.contains(galleryItem.mediaItem.id)
                                GalleryItemCard(
                                    galleryItem = galleryItem,
                                    isSelected = isSelected,
                                    isSelectionMode = isSelectionMode,
                                    onToggleSelect = { viewModel.toggleSelection(galleryItem.mediaItem.id) },
                                    onClick = { onNavigateToDetail(galleryItem.mediaItem.id) }
                                )
                            }
                        }
                    }
                }
            }

            // API 34+ "Select photos": only the picked items are visible to
            // the app (and backed up), so keep that in view.
            if (mediaAccess == MediaAccessLevel.LIMITED) {
                LimitedMediaAccessBanner(
                    onSelectMorePhotos = onSelectMorePhotos,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryItemCard(
    galleryItem: GalleryItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val currentToggleSelect by rememberUpdatedState(onToggleSelect)
    val currentClick by rememberUpdatedState(onClick)

    val imageRequest = remember(galleryItem.mediaItem.contentUri, galleryItem.mediaItem.isVideo, galleryItem.mediaItem.orientationDegrees) {
        val builder = ImageRequest.Builder(context)
            .data(Uri.parse(galleryItem.mediaItem.contentUri))
            .size(300, 300)
            .scale(coil.size.Scale.FILL)
            .precision(coil.size.Precision.INEXACT)
            .allowHardware(true)
            .crossfade(150)
        if (galleryItem.mediaItem.isVideo) {
            builder.decoderFactory(VideoFrameDecoder.Factory())
        } else {
            ExifOrientationHelper.applyExifOrientation(
                builder,
                galleryItem.mediaItem.orientationDegrees,
                galleryItem.mediaItem.isDng || galleryItem.mediaItem.isRaw
            )
        }
        builder.build()
    }

    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        currentToggleSelect()
                    } else {
                        currentClick()
                    }
                },
                onLongClick = {
                    currentToggleSelect()
                }
            ),
        shape = RoundedCornerShape(2.dp),
        border = if (isSelected) BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = imageRequest,
                contentDescription = galleryItem.mediaItem.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            if (isSelected) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                ) {}
            }

            // Top-Left Lineage Status Badge (only when paired or non-default)
            if (galleryItem.lineageStatus != "Unpaired") {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = galleryItem.lineageStatus,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Top-Right Format / Selection Badge
            if (isSelectionMode || isSelected) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.5f),
                    shape = CircleShape,
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Outlined.CheckCircle,
                        contentDescription = stringResource(R.string.gallery_badge_selected),
                        tint = Color.White,
                        modifier = Modifier.padding(2.dp).size(18.dp)
                    )
                }
            } else if (galleryItem.mediaItem.isDng) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.gallery_badge_raw),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            } else if (galleryItem.mediaItem.isVideo) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = stringResource(R.string.gallery_badge_video),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Bottom-Right Backup / Offload Status Badge
            if (galleryItem.isOffloaded) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.gallery_badge_offloaded),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (galleryItem.isBackedUp) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = CircleShape,
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDone,
                        contentDescription = stringResource(R.string.gallery_badge_backed_up),
                        tint = Color.White,
                        modifier = Modifier.padding(3.dp).size(14.dp)
                    )
                }
            } else if (galleryItem.isPendingUpload) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = CircleShape,
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudSync,
                        contentDescription = stringResource(R.string.gallery_badge_pending),
                        tint = Color.White,
                        modifier = Modifier.padding(3.dp).size(14.dp)
                    )
                }
            } else if (galleryItem.isUploadFailed) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                    shape = CircleShape,
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = stringResource(R.string.gallery_badge_failed),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(3.dp).size(14.dp)
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun EmptyGalleryPreview() {
    BranchDamTheme {
        EmptyGalleryState()
    }
}

@Composable
private fun EmptyGalleryState(
    title: String = stringResource(R.string.gallery_empty_title),
    body: String = stringResource(R.string.gallery_empty_body),
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(24.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/**
 * Shown when the user granted access to selected photos only (API 34+
 * "Select photos"). Items outside the selection are invisible to the app
 * and are never backed up, so say so and offer the system picker again.
 */
@Composable
private fun LimitedMediaAccessBanner(
    onSelectMorePhotos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = stringResource(R.string.gallery_limited_access),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSelectMorePhotos) {
                Text(stringResource(R.string.gallery_select_more))
            }
        }
    }
}
