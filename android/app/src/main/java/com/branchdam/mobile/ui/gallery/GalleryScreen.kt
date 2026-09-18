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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
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
) {
    val rawItems by viewModel.items.collectAsStateWithLifecycle()
    val displayedItems by viewModel.displayedItems.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val selectedItemIds by viewModel.selectedItemIds.collectAsStateWithLifecycle()

    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val selectedFolder by viewModel.selectedFolder.collectAsStateWithLifecycle()
    val availableFolders by viewModel.availableFolders.collectAsStateWithLifecycle()
    val selectedSortProperty by viewModel.selectedSortProperty.collectAsStateWithLifecycle()
    val selectedSortDirection by viewModel.selectedSortDirection.collectAsStateWithLifecycle()

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val state = rememberPullToRefreshState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val isSelectionMode = selectedItemIds.isNotEmpty()

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
            title = { Text("Delete Photo(s) Permanently?") },
            text = {
                Text(
                    "This will permanently delete the selected ${selectedItemIds.size} item(s) from branchDAM and your local device. This action cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.deleteSelectedItems(context) { count ->
                            scope.launch {
                                snackbarHostState.showSnackbar("Deleted $count item(s) permanently")
                            }
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
            if (isSelectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = "${selectedItemIds.size} selected",
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear selection"
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
                                contentDescription = if (isAllSelectableSelected) "Deselect all" else "Select all"
                            )
                        }
                        IconButton(onClick = { showDeleteConfirmDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete selected items",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                viewModel.uploadSelectedItems(context) { count ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Enqueued $count item(s) for upload")
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
                            Text("Upload")
                        }
                    }
                )
            } else {
                var showFilterMenu by remember { mutableStateOf(false) }
                var showSortMenu by remember { mutableStateOf(false) }
                val isFilterActive = selectedFilter != GalleryFilter.ALL || selectedFolder != "All Folders"

                CenterAlignedTopAppBar(
                    title = { Text("Gallery") },
                    actions = {
                        Box {
                            IconButton(onClick = { showFilterMenu = true }) {
                                if (isFilterActive) {
                                    BadgedBox(
                                        badge = { Badge() }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Tune,
                                            contentDescription = "Filter media",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = "Filter media"
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = showFilterMenu,
                                onDismissRequest = { showFilterMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Folder", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
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
                                                Text(folder)
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
                                    text = { Text("Media Type", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
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
                                                Text(filter.label)
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
                                    contentDescription = "Sort media"
                                )
                            }

                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Date (Newest First)") },
                                    onClick = {
                                        viewModel.setSort(GallerySortProperty.DATE, GallerySortDirection.DESC)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Date (Oldest First)") },
                                    onClick = {
                                        viewModel.setSort(GallerySortProperty.DATE, GallerySortDirection.ASC)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Size (Largest First)") },
                                    onClick = {
                                        viewModel.setSort(GallerySortProperty.SIZE, GallerySortDirection.DESC)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Size (Smallest First)") },
                                    onClick = {
                                        viewModel.setSort(GallerySortProperty.SIZE, GallerySortDirection.ASC)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Name (A to Z)") },
                                    onClick = {
                                        viewModel.setSort(GallerySortProperty.NAME, GallerySortDirection.ASC)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Name (Z to A)") },
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
                                contentDescription = "Free Up Storage (Safe Space)"
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
                        EmptyGalleryState()
                    }
                    else -> {
                        LazyVerticalGrid(
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
    val imageRequest = remember(galleryItem.mediaItem.contentUri) {
        val builder = ImageRequest.Builder(context)
            .data(Uri.parse(galleryItem.mediaItem.contentUri))
            .crossfade(200)
        if (galleryItem.mediaItem.isVideo) {
            builder.decoderFactory(VideoFrameDecoder.Factory())
        } else {
            ExifOrientationHelper.applyExifOrientation(builder, context, galleryItem.mediaItem.contentUri, galleryItem.mediaItem.mimeType)
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
                        onToggleSelect()
                    } else {
                        onClick()
                    }
                },
                onLongClick = {
                    onToggleSelect()
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
                        contentDescription = "Selected",
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
                        text = "RAW",
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
                            contentDescription = "Video",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "VIDEO",
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
                        text = "Offloaded",
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
                        contentDescription = "Backed Up",
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
                        contentDescription = "Pending Upload",
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
                        contentDescription = "Upload Failed",
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
private fun EmptyGalleryState() {
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
            "No media items found",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Photos and videos from your camera roll will appear here once synced.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
