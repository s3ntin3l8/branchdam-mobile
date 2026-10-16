package com.branchdam.mobile.ui.gallery

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.SharedPreferences
import com.branchdam.mobile.BranchDamKeys
import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.R
import com.branchdam.mobile.lineage.LineageReview
import com.branchdam.mobile.lineage.PairDetector
import com.branchdam.mobile.observer.MediaItem
import com.branchdam.mobile.observer.MediaScanner
import com.branchdam.mobile.observer.UploadStaging
import com.branchdam.mobile.service.SyncScheduler
import com.branchdam.mobile.triage.MediaDeleteCoordinator
import com.branchdam.mobile.triage.MediaStoreDeleter
import com.branchdam.mobile.triage.PendingDeleteConsent
import com.branchdam.mobile.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A media-type filter; [labelRes] is the name the filter menu shows. */
enum class GalleryFilter(@StringRes val labelRes: Int) {
    ALL(R.string.gallery_filter_all),
    PHOTOS(R.string.gallery_filter_photos),
    VIDEOS(R.string.gallery_filter_videos),
    RAW(R.string.gallery_filter_raw),
    BACKED_UP(R.string.gallery_filter_backed_up)
}

enum class GallerySortProperty {
    DATE,
    SIZE,
    NAME
}

enum class GallerySortDirection {
    DESC,
    ASC
}

/**
 * The folder-filter key meaning "no folder filter". It is a key, not UI
 * text: the menu shows it as R.string.gallery_filter_all_folders.
 */
const val ALL_FOLDERS = "All Folders"

@Immutable
data class GalleryItem(
    val primaryMediaItem: MediaItem,
    val companionMediaItem: MediaItem? = null,
    /**
     * Confidence of the RAW/JPEG pairing behind [companionMediaItem]. Only
     * an exact (1.00) stem-match pair is sent as a lineage edge on upload;
     * a capture-time guess (0.95) waits for the user in the lineage
     * review screen.
     */
    val companionConfidence: Double = 0.0,
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

    private val prefs = application.getSharedPreferences(BranchDamKeys.PREFS_NAME, Context.MODE_PRIVATE)

    private val _includedFolders = MutableStateFlow<Set<String>>(
        prefs.getStringSet(BranchDamKeys.INCLUDED_GALLERY_FOLDERS, emptySet()) ?: emptySet()
    )
    val includedFolders: StateFlow<Set<String>> = _includedFolders.asStateFlow()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == BranchDamKeys.INCLUDED_GALLERY_FOLDERS) {
            _includedFolders.value = prefs.getStringSet(BranchDamKeys.INCLUDED_GALLERY_FOLDERS, emptySet()) ?: emptySet()
        }
    }

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
        combine(_items, _selectedFilter, _selectedFolder) { items, filter, folder -> Triple(items, filter, folder) },
        combine(_selectedSortProperty, _selectedSortDirection, _includedFolders) { sortProp, sortDir, folders -> Triple(sortProp, sortDir, folders) }
    ) { (rawItems, filter, folder), (sortProp, sortDir, configuredIncludedFolders) ->
        var filtered = rawItems.filter { item ->
            val passesFilter = when (filter) {
                GalleryFilter.ALL -> true
                GalleryFilter.PHOTOS -> !item.primaryMediaItem.isVideo
                GalleryFilter.VIDEOS -> item.primaryMediaItem.isVideo
                GalleryFilter.RAW -> item.primaryMediaItem.isDng || item.primaryMediaItem.isRaw || item.companionMediaItem != null
                GalleryFilter.BACKED_UP -> item.isBackedUp
            }

            val passesFolder = if (folder == ALL_FOLDERS) {
                if (configuredIncludedFolders.contains(SettingsViewModel.NO_FOLDERS_SENTINEL)) false
                else if (configuredIncludedFolders.isEmpty()) true
                else configuredIncludedFolders.contains(item.primaryMediaItem.folderName) ||
                        (item.companionMediaItem != null && configuredIncludedFolders.contains(item.companionMediaItem.folderName))
            } else {
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
    }.flowOn(defaultDispatcher)
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val deleteCoordinator = MediaDeleteCoordinator(viewModelScope) { ioDispatcher }

    private val _selectedItemIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedItemIds: StateFlow<Set<Long>> = _selectedItemIds.asStateFlow()

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        loadItems()
    }

    override fun onCleared() {
        super.onCleared()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    fun loadItems() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Phase 1: Fast initial query (top 100 recent items) for <30ms time-to-first-frame
                val initialItems = withContext(ioDispatcher) {
                    val context = getApplication<Application>()
                    val fastImages = MediaScanner.queryRecentImages(context, limit = 100)
                    val fastVideos = MediaScanner.queryRecentVideos(context, limit = 30)
                    val fastAll = fastImages + fastVideos
                    val statuses = EngineHolder.getAllMediaStatuses()
                    val (fastResult, fastFolders) = processMediaItems(fastAll, statuses)
                    _availableFolders.value = fastFolders
                    fastResult
                }
                _items.value = initialItems
                _isLoading.value = false
                _loadError.value = null

                // Phase 2: Full background scan for complete gallery history
                withContext(ioDispatcher) {
                    val context = getApplication<Application>()
                    val fullImages = MediaScanner.queryRecentImages(context, limit = 5000)
                    val fullVideos = MediaScanner.queryRecentVideos(context, limit = 1000)
                    val fullAll = fullImages + fullVideos
                    if (fullAll.size > initialItems.size) {
                        val statuses = EngineHolder.getAllMediaStatuses()
                        val (fullResult, fullFolders) = processMediaItems(fullAll, statuses)
                        _availableFolders.value = fullFolders
                        _items.value = fullResult
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "loadItems failed", t)
                if (_items.value.isEmpty()) {
                    _loadError.value = t.message ?: "Failed to load media"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun processMediaItems(
        allItems: List<MediaItem>,
        allStatuses: Map<String, String>
    ): Pair<List<GalleryItem>, List<String>> {
        val pairs = PairDetector.findPairs(allItems)
        val pairedRawIds = mutableSetOf<Long>()
        val pairedJpegIds = mutableSetOf<Long>()
        val pairMapByJpegId = mutableMapOf<Long, com.branchdam.mobile.lineage.LineagePair>()

        for (pair in pairs) {
            pairedRawIds.add(pair.masterRaw.id)
            pairedJpegIds.add(pair.derivativeJpeg.id)
            pairMapByJpegId[pair.derivativeJpeg.id] = pair
        }

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
                        companionConfidence = pair?.confidence ?: 0.0,
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

        val folderList = mutableListOf(ALL_FOLDERS)
        val itemFolders = resultList.map { it.primaryMediaItem.folderName }.filter { it.isNotBlank() }
        for (f in itemFolders.toSet().sorted()) {
            if (!folderList.contains(f)) {
                folderList.add(f)
            }
        }

        return Pair(resultList, folderList)
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

                val primaryId = UploadStaging.enqueue(context, primary)
                var companionId = 0L
                if (companion != null) {
                    companionId = UploadStaging.enqueue(context, companion)
                    if (primaryId > 0L && companionId > 0L && LineageReview.isAutomatic(item.companionConfidence)) {
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
        val galleryItem = _items.value.find { it.primaryMediaItem.id == mediaItem.id || it.companionMediaItem?.id == mediaItem.id }
        val primary = galleryItem?.primaryMediaItem ?: mediaItem
        val companion = galleryItem?.companionMediaItem

        viewModelScope.launch(ioDispatcher) {
            // The offload lookup is a blocking engine call: keep it off
            // the main thread, on the same dispatcher as the enqueue.
            if (EngineHolder.isMediaOffloaded(mediaItem.contentUri)) {
                withContext(Dispatchers.Main) { onComplete(false) }
                return@launch
            }
            val primaryId = UploadStaging.enqueue(context, primary)
            var companionId = 0L
            if (companion != null) {
                companionId = UploadStaging.enqueue(context, companion)
                if (primaryId > 0L && companionId > 0L &&
                    LineageReview.isAutomatic(galleryItem?.companionConfidence ?: 0.0)
                ) {
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

    /**
     * System delete confirmation the gallery screens must launch. MediaStore
     * refuses to delete camera-roll items this app did not create without
     * it on API 29+ (see [MediaStoreDeleter]).
     */
    val pendingDeleteConsent: StateFlow<PendingDeleteConsent?> get() = deleteCoordinator.pendingConsent

    fun onDeleteConsentLaunched(id: Long) = deleteCoordinator.onConsentLaunched(id)

    fun onDeleteConsentResult(approved: Boolean) = deleteCoordinator.onConsentResult(approved)

    fun deleteItem(context: Context, item: GalleryItem, onComplete: (Boolean) -> Unit) {
        deleteGalleryItems(context, listOf(item)) { deleted -> onComplete(deleted.isNotEmpty()) }
    }

    fun deleteSelectedItems(context: Context, onComplete: (Int) -> Unit) {
        val selectedIds = _selectedItemIds.value
        val itemsToDelete = _items.value.filter { selectedIds.contains(it.mediaItem.id) }
        if (itemsToDelete.isEmpty()) {
            clearSelection()
            onComplete(0)
            return
        }
        deleteGalleryItems(context, itemsToDelete) { deleted ->
            clearSelection()
            onComplete(deleted.size)
        }
    }

    /**
     * Removes [items] from the list optimistically, asks MediaStore to
     * delete them (pausing for the user's confirmation on API 29+), then
     * emits delete events for what was actually deleted and restores the
     * rest. [onFinished] runs on the main dispatcher with the items whose
     * primary media was deleted.
     */
    private fun deleteGalleryItems(
        context: Context,
        items: List<GalleryItem>,
        onFinished: (List<GalleryItem>) -> Unit,
    ) {
        val primaryIds = items.map { it.primaryMediaItem.id }.toSet()
        _items.update { list -> list.filter { it.primaryMediaItem.id !in primaryIds } }

        // Primary first so the API 28/29 sequential path attempts it
        // before its RAW companion.
        val uris = items.flatMap { item ->
            listOfNotNull(item.primaryMediaItem.contentUri, item.companionMediaItem?.contentUri)
        }

        val accepted = deleteCoordinator.delete(deleterFactory(context), uris) { done ->
            for (uri in uris) {
                if (uri in done.deleted) EngineHolder.enqueueDeleteEvent(uri)
            }
            val deletedItems = items.filter { it.primaryMediaItem.contentUri in done.deleted }
            val failedItems = items.filter { it.primaryMediaItem.contentUri !in done.deleted }
            if (failedItems.isNotEmpty()) {
                restoreItems(failedItems)
            }
            if (done.deleted.isNotEmpty()) {
                try {
                    SyncScheduler.triggerImmediateSync(context)
                } catch (e: Exception) {
                    Log.w(TAG, "triggerImmediateSync failed", e)
                }
            }
            withContext(Dispatchers.Main) {
                onFinished(deletedItems)
            }
        }
        if (!accepted) {
            Log.w(TAG, "delete ignored: another delete is still waiting for confirmation")
            restoreItems(items)
            onFinished(emptyList())
        }
    }

    private fun restoreItems(toRestore: List<GalleryItem>) {
        _items.update { list ->
            list + toRestore.filter { restored -> list.none { it.primaryMediaItem.id == restored.primaryMediaItem.id } }
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

        @androidx.annotation.VisibleForTesting
        internal var defaultDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default

        @androidx.annotation.VisibleForTesting
        internal var deleterFactory: (Context) -> MediaStoreDeleter = { MediaStoreDeleter.forContext(it) }
    }
}
