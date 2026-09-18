package com.branchdam.mobile.ui.gallery

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.lineage.PairDetector
import com.branchdam.mobile.observer.MediaItem
import com.branchdam.mobile.observer.MediaScanner
import com.branchdam.mobile.service.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class GalleryFilter(val label: String) {
    ALL("All"),
    PHOTOS("Photos"),
    VIDEOS("Videos"),
    RAW("RAW"),
    BACKED_UP("Backed Up")
}

enum class GallerySortProperty(val label: String) {
    DATE("Date"),
    SIZE("Size"),
    NAME("Name")
}

enum class GallerySortDirection(val label: String) {
    DESC("Descending"),
    ASC("Ascending")
}

const val ALL_FOLDERS = "All Folders"

data class GalleryItem(
    val primaryMediaItem: MediaItem,
    val companionMediaItem: MediaItem? = null,
    val lineageStatus: String = "Unpaired",
    val isOffloaded: Boolean = false,
    val backupStatus: String = "NOT_ENQUEUED",
) {
    val mediaItem: MediaItem get() = primaryMediaItem
    val isPair: Boolean get() = companionMediaItem != null
    val isBackedUp: Boolean get() = backupStatus == "COMPLETED" || isOffloaded
    val isPendingUpload: Boolean get() = backupStatus == "PENDING" || backupStatus == "IN_PROGRESS"
    val isUploadFailed: Boolean get() = backupStatus == "FAILED"
}

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val _items = MutableStateFlow<List<GalleryItem>>(emptyList())
    val items: StateFlow<List<GalleryItem>> = _items.asStateFlow()

    private val _selectedFilter = MutableStateFlow(GalleryFilter.ALL)
    val selectedFilter: StateFlow<GalleryFilter> = _selectedFilter.asStateFlow()

    private val _selectedFolder = MutableStateFlow(ALL_FOLDERS)
    val selectedFolder: StateFlow<String> = _selectedFolder.asStateFlow()

    private val _availableFolders = MutableStateFlow<List<String>>(listOf(ALL_FOLDERS))
    val availableFolders: StateFlow<List<String>> = _availableFolders.asStateFlow()

    private val _selectedSortProperty = MutableStateFlow(GallerySortProperty.DATE)
    val selectedSortProperty: StateFlow<GallerySortProperty> = _selectedSortProperty.asStateFlow()

    private val _selectedSortDirection = MutableStateFlow(GallerySortDirection.DESC)
    val selectedSortDirection: StateFlow<GallerySortDirection> = _selectedSortDirection.asStateFlow()

    val displayedItems: StateFlow<List<GalleryItem>> = combine(
        _items,
        _selectedFilter,
        _selectedFolder,
        _selectedSortProperty,
        _selectedSortDirection
    ) { rawItems, filter, folder, sortProp, sortDir ->
        var filtered = rawItems.filter { item ->
            val passesFilter = when (filter) {
                GalleryFilter.ALL -> true
                GalleryFilter.PHOTOS -> !item.primaryMediaItem.isVideo
                GalleryFilter.VIDEOS -> item.primaryMediaItem.isVideo
                GalleryFilter.RAW -> item.primaryMediaItem.isDng || item.primaryMediaItem.isRaw || item.companionMediaItem != null
                GalleryFilter.BACKED_UP -> item.isBackedUp
            }
            val passesFolder = if (folder == ALL_FOLDERS) true else {
                item.primaryMediaItem.folderName.equals(folder, ignoreCase = true) ||
                    (item.companionMediaItem != null && item.companionMediaItem.folderName.equals(folder, ignoreCase = true))
            }
            passesFilter && passesFolder
        }

        when (sortProp) {
            GallerySortProperty.DATE -> {
                filtered = if (sortDir == GallerySortDirection.DESC) {
                    filtered.sortedByDescending { it.primaryMediaItem.dateTakenUnix }
                } else {
                    filtered.sortedBy { it.primaryMediaItem.dateTakenUnix }
                }
            }
            GallerySortProperty.SIZE -> {
                filtered = if (sortDir == GallerySortDirection.DESC) {
                    filtered.sortedByDescending { it.primaryMediaItem.sizeBytes + (it.companionMediaItem?.sizeBytes ?: 0L) }
                } else {
                    filtered.sortedBy { it.primaryMediaItem.sizeBytes + (it.companionMediaItem?.sizeBytes ?: 0L) }
                }
            }
            GallerySortProperty.NAME -> {
                filtered = if (sortDir == GallerySortDirection.DESC) {
                    filtered.sortedByDescending { it.primaryMediaItem.displayName }
                } else {
                    filtered.sortedBy { it.primaryMediaItem.displayName }
                }
            }
        }
        filtered
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _selectedItemIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedItemIds: StateFlow<Set<Long>> = _selectedItemIds.asStateFlow()

    init {
        loadItems()
    }

    fun loadItems() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val galleryItems = withContext(ioDispatcher) {
                    val context = getApplication<Application>()
                    val images = MediaScanner.queryRecentImages(context)
                    val videos = MediaScanner.queryRecentVideos(context)
                    val allItems = images + videos

                    val pairs = PairDetector.findPairs(allItems)
                    val pairedRawIds = mutableSetOf<Long>()
                    val pairedJpegIds = mutableSetOf<Long>()
                    val pairMapByJpegId = mutableMapOf<Long, com.branchdam.mobile.lineage.LineagePair>()

                    for (pair in pairs) {
                        pairedRawIds.add(pair.masterRaw.id)
                        pairedJpegIds.add(pair.derivativeJpeg.id)
                        pairMapByJpegId[pair.derivativeJpeg.id] = pair
                    }

                    val allStatuses = EngineHolder.getAllMediaStatuses()
                    val resultList = mutableListOf<GalleryItem>()

                    for (item in allItems) {
                        if (pairedRawIds.contains(item.id)) {
                            // Grouped under companion JPEG card
                            continue
                        }

                        if (pairedJpegIds.contains(item.id)) {
                            val pair = pairMapByJpegId[item.id]
                            val companionRaw = pair?.masterRaw

                            val jpegStatus = allStatuses[item.contentUri]
                                ?: allStatuses[item.filePath]
                                ?: allStatuses[item.displayName]
                                ?: "NOT_ENQUEUED"
                            val rawStatus = companionRaw?.let {
                                allStatuses[it.contentUri]
                                    ?: allStatuses[it.filePath]
                                    ?: allStatuses[it.displayName]
                            } ?: "NOT_ENQUEUED"

                            val combinedStatus = when {
                                jpegStatus == "FAILED" || rawStatus == "FAILED" -> "FAILED"
                                jpegStatus == "PENDING" || rawStatus == "PENDING" || jpegStatus == "IN_PROGRESS" || rawStatus == "IN_PROGRESS" -> "PENDING"
                                jpegStatus == "OFFLOADED" || rawStatus == "OFFLOADED" -> "OFFLOADED"
                                jpegStatus == "COMPLETED" || rawStatus == "COMPLETED" -> "COMPLETED"
                                else -> "NOT_ENQUEUED"
                            }
                            val isOffloaded = combinedStatus == "OFFLOADED"

                            resultList.add(
                                GalleryItem(
                                    primaryMediaItem = item,
                                    companionMediaItem = companionRaw,
                                    lineageStatus = "RAW+JPEG",
                                    isOffloaded = isOffloaded,
                                    backupStatus = combinedStatus,
                                )
                            )
                        } else {
                            val backupStatus = allStatuses[item.contentUri]
                                ?: allStatuses[item.filePath]
                                ?: allStatuses[item.displayName]
                                ?: "NOT_ENQUEUED"
                            val isOffloaded = backupStatus == "OFFLOADED"
                            val status = if (item.isDng) "RAW" else "Unpaired"
                            resultList.add(
                                GalleryItem(
                                    primaryMediaItem = item,
                                    companionMediaItem = null,
                                    lineageStatus = status,
                                    isOffloaded = isOffloaded,
                                    backupStatus = backupStatus,
                                )
                            )
                        }
                    }
                    val detectedFolders = MediaScanner.queryAvailableFolders(context)
                    val folderList = mutableListOf(ALL_FOLDERS)
                    for (f in detectedFolders) {
                        if (f.isNotBlank() && !folderList.contains(f)) {
                            folderList.add(f)
                        }
                    }
                    _availableFolders.value = folderList

                    resultList
                }
                _items.value = galleryItems
                _loadError.value = null
            } catch (t: Throwable) {
                Log.w(TAG, "loadItems failed", t)
                _loadError.value = t.message ?: "Failed to load media"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun toggleSelection(id: Long) {
        _selectedItemIds.update { current ->
            if (current.contains(id)) current - id else current + id
        }
    }

    fun selectAll() {
        _selectedItemIds.value = displayedItems.value.filter { !it.isOffloaded }.map { it.mediaItem.id }.toSet()
    }

    fun clearSelection() {
        _selectedItemIds.value = emptySet()
    }

    fun uploadSelectedItems(context: Context, onComplete: (Int) -> Unit) {
        val selectedIds = _selectedItemIds.value
        val itemsToUpload = _items.value.filter { selectedIds.contains(it.mediaItem.id) && !it.isOffloaded }
        if (itemsToUpload.isEmpty()) {
            clearSelection()
            onComplete(0)
            return
        }

        viewModelScope.launch(ioDispatcher) {
            var successCount = 0
            for (item in itemsToUpload) {
                val primary = item.primaryMediaItem
                val companion = item.companionMediaItem

                val primaryId = EngineHolder.enqueueMedia(
                    localPath = primary.filePath,
                    filename = primary.displayName,
                    capturedAtUnix = primary.dateTakenUnix,
                    localId = primary.contentUri
                )
                var companionId = 0L
                if (companion != null) {
                    companionId = EngineHolder.enqueueMedia(
                        localPath = companion.filePath,
                        filename = companion.displayName,
                        capturedAtUnix = companion.dateTakenUnix,
                        localId = companion.contentUri
                    )
                    if (primaryId > 0L && companionId > 0L) {
                        EngineHolder.enqueueLineageEvent(
                            parentLocalID = companion.contentUri,
                            childLocalID = primary.contentUri,
                            relationshipType = "DERIVED_FROM",
                            resolver = "android_camera_pair",
                            confidence = 1.00
                        )
                    }
                }
                if (primaryId > 0L || companionId > 0L) {
                    successCount++
                }
            }
            if (successCount > 0) {
                _items.update { list ->
                    list.map { item ->
                        if (selectedIds.contains(item.mediaItem.id) && !item.isOffloaded) {
                            item.copy(backupStatus = "PENDING")
                        } else item
                    }
                }
                SyncScheduler.triggerImmediateSync(context)
            }
            withContext(Dispatchers.Main) {
                clearSelection()
                onComplete(successCount)
            }
        }
    }

    fun uploadItem(context: Context, mediaItem: MediaItem, onComplete: (Boolean) -> Unit) {
        if (EngineHolder.isMediaOffloaded(mediaItem.contentUri)) {
            onComplete(false)
            return
        }

        val galleryItem = _items.value.find { it.primaryMediaItem.id == mediaItem.id || it.companionMediaItem?.id == mediaItem.id }
        val primary = galleryItem?.primaryMediaItem ?: mediaItem
        val companion = galleryItem?.companionMediaItem

        viewModelScope.launch(ioDispatcher) {
            val primaryId = EngineHolder.enqueueMedia(
                localPath = primary.filePath,
                filename = primary.displayName,
                capturedAtUnix = primary.dateTakenUnix,
                localId = primary.contentUri
            )
            var companionId = 0L
            if (companion != null) {
                companionId = EngineHolder.enqueueMedia(
                    localPath = companion.filePath,
                    filename = companion.displayName,
                    capturedAtUnix = companion.dateTakenUnix,
                    localId = companion.contentUri
                )
                if (primaryId > 0L && companionId > 0L) {
                    EngineHolder.enqueueLineageEvent(
                        parentLocalID = companion.contentUri,
                        childLocalID = primary.contentUri,
                        relationshipType = "DERIVED_FROM",
                        resolver = "android_camera_pair",
                        confidence = 1.00
                    )
                }
            }
            val success = primaryId > 0L || companionId > 0L
            if (success) {
                _items.update { list ->
                    list.map { item ->
                        if (item.primaryMediaItem.id == primary.id) {
                            item.copy(backupStatus = "PENDING")
                        } else item
                    }
                }
                SyncScheduler.triggerImmediateSync(context)
            }
            withContext(Dispatchers.Main) {
                onComplete(success)
            }
        }
    }

    fun getItemById(id: Long): GalleryItem? {
        return _items.value.find { it.primaryMediaItem.id == id || it.companionMediaItem?.id == id }
    }

    fun setFilter(filter: GalleryFilter) {
        clearSelection()
        _selectedFilter.value = filter
    }

    fun setFolder(folder: String) {
        clearSelection()
        _selectedFolder.value = folder
    }

    fun setSort(property: GallerySortProperty, direction: GallerySortDirection) {
        _selectedSortProperty.value = property
        _selectedSortDirection.value = direction
    }

    fun deleteItem(context: Context, item: GalleryItem, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(ioDispatcher) {
            _items.update { list ->
                list.filter { it.primaryMediaItem.id != item.primaryMediaItem.id }
            }

            val primaryUri = item.primaryMediaItem.contentUri
            val companionUri = item.companionMediaItem?.contentUri

            var success = false
            try {
                val primaryDeleted = context.contentResolver.delete(android.net.Uri.parse(primaryUri), null, null)
                if (primaryDeleted > 0) {
                    if (companionUri != null) {
                        try {
                            context.contentResolver.delete(android.net.Uri.parse(companionUri), null, null)
                        } catch (e: Exception) {
                            Log.w(TAG, "deleteItem companion contentResolver delete failed", e)
                        }
                    }
                    success = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "deleteItem contentResolver delete failed", e)
            }

            if (success) {
                EngineHolder.enqueueDeleteEvent(primaryUri)
                if (companionUri != null) {
                    EngineHolder.enqueueDeleteEvent(companionUri)
                }
                SyncScheduler.triggerImmediateSync(context)
            } else {
                _items.update { list ->
                    if (list.none { it.primaryMediaItem.id == item.primaryMediaItem.id }) list + item else list
                }
            }

            withContext(Dispatchers.Main) {
                onComplete(success)
            }
        }
    }

    fun deleteSelectedItems(context: Context, onComplete: (Int) -> Unit) {
        val selectedIds = _selectedItemIds.value
        val itemsToDelete = _items.value.filter { selectedIds.contains(it.mediaItem.id) }
        if (itemsToDelete.isEmpty()) {
            clearSelection()
            onComplete(0)
            return
        }

        viewModelScope.launch(ioDispatcher) {
            _items.update { list ->
                list.filter { !selectedIds.contains(it.primaryMediaItem.id) }
            }

            var deletedCount = 0
            val failedItems = mutableListOf<GalleryItem>()

            for (item in itemsToDelete) {
                val primaryUri = item.primaryMediaItem.contentUri
                val companionUri = item.companionMediaItem?.contentUri

                var localSuccess = false
                try {
                    val primaryDeleted = context.contentResolver.delete(android.net.Uri.parse(primaryUri), null, null)
                    if (primaryDeleted > 0) {
                        if (companionUri != null) {
                            try {
                                context.contentResolver.delete(android.net.Uri.parse(companionUri), null, null)
                            } catch (e: Exception) {
                                Log.w(TAG, "deleteSelectedItems companion contentResolver delete failed", e)
                            }
                        }
                        localSuccess = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "deleteSelectedItems contentResolver delete failed", e)
                }

                if (localSuccess) {
                    EngineHolder.enqueueDeleteEvent(primaryUri)
                    if (companionUri != null) {
                        EngineHolder.enqueueDeleteEvent(companionUri)
                    }
                    deletedCount++
                } else {
                    failedItems.add(item)
                }
            }

            if (failedItems.isNotEmpty()) {
                _items.update { list ->
                    list + failedItems.filter { failed -> list.none { it.primaryMediaItem.id == failed.primaryMediaItem.id } }
                }
            }

            if (deletedCount > 0) {
                SyncScheduler.triggerImmediateSync(context)
            }

            withContext(Dispatchers.Main) {
                clearSelection()
                onComplete(deletedCount)
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun setItemsForTesting(testItems: List<GalleryItem>) {
        _items.value = testItems
    }

    companion object {
        private const val TAG = "GalleryViewModel"

        @androidx.annotation.VisibleForTesting
        internal var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
    }
}
