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
    val mediaItem: MediaItem,
    val lineageStatus: String,
    val isOffloaded: Boolean = false,
)

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

                    val pairedIds = mutableSetOf<Long>()
                    val pairs = PairDetector.findPairs(allItems)
                    for (pair in pairs) {
                        pairedIds.add(pair.masterRaw.id)
                        pairedIds.add(pair.derivativeJpeg.id)
                    }

                    allItems.map { item ->
                        val status = when {
                            pairedIds.contains(item.id) -> "Paired"
                            item.isDng -> "RAW"
                            else -> "Unpaired"
                        }
                        val isOffloaded = EngineHolder.isMediaOffloaded(item.contentUri)
                        GalleryItem(
                            mediaItem = item,
                            lineageStatus = status,
                            isOffloaded = isOffloaded
                        )
                    }
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
                val mediaId = EngineHolder.enqueueMedia(
                    localPath = item.mediaItem.filePath,
                    filename = item.mediaItem.displayName,
                    capturedAtUnix = item.mediaItem.dateTakenUnix,
                    localId = item.mediaItem.contentUri
                )
                if (mediaId > 0L) {
                    successCount++
                }
            }
            if (successCount > 0) {
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

        viewModelScope.launch(ioDispatcher) {
            val mediaId = EngineHolder.enqueueMedia(
                localPath = mediaItem.filePath,
                filename = mediaItem.displayName,
                capturedAtUnix = mediaItem.dateTakenUnix,
                localId = mediaItem.contentUri
            )
            val success = mediaId > 0L
            if (success) {
                SyncScheduler.triggerImmediateSync(context)
            }
            withContext(Dispatchers.Main) {
                onComplete(success)
            }
        }
    }

    fun getItemById(id: Long): GalleryItem? {
        return _items.value.find { it.mediaItem.id == id }
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
