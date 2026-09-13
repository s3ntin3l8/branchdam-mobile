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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                val galleryItems = withContext(Dispatchers.IO) {
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
                                jpegStatus == "COMPLETED" && rawStatus == "COMPLETED" -> "COMPLETED"
                                jpegStatus == "COMPLETED" || rawStatus == "COMPLETED" -> "COMPLETED"
                                jpegStatus == "OFFLOADED" || rawStatus == "OFFLOADED" -> "OFFLOADED"
                                jpegStatus == "PENDING" || rawStatus == "PENDING" || jpegStatus == "IN_PROGRESS" || rawStatus == "IN_PROGRESS" -> "PENDING"
                                jpegStatus == "FAILED" || rawStatus == "FAILED" -> "FAILED"
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
        _selectedItemIds.value = _items.value.filter { !it.isOffloaded }.map { it.mediaItem.id }.toSet()
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
