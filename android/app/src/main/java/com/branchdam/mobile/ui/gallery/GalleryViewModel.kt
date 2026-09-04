package com.branchdam.mobile.ui.gallery

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.branchdam.mobile.lineage.PairDetector
import com.branchdam.mobile.observer.MediaItem
import com.branchdam.mobile.observer.MediaScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GalleryItem(
    val mediaItem: MediaItem,
    val lineageStatus: String,
)

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val _items = MutableStateFlow<List<GalleryItem>>(emptyList())
    val items: StateFlow<List<GalleryItem>> = _items.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

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
                        GalleryItem(mediaItem = item, lineageStatus = status)
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

    companion object {
        private const val TAG = "GalleryViewModel"
    }
}
