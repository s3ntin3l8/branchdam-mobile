package com.branchdam.mobile.ui.gallery

import android.app.Application
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

data class GalleryItem(
    val mediaItem: MediaItem,
    val lineageStatus: String,
)

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val _items = MutableStateFlow<List<GalleryItem>>(emptyList())
    val items: StateFlow<List<GalleryItem>> = _items.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadItems()
    }

    fun loadItems() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
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

            val galleryItems = allItems.map { item ->
                val status = when {
                    pairedIds.contains(item.id) -> "Paired"
                    item.isDng -> "RAW"
                    else -> "Unpaired"
                }
                GalleryItem(mediaItem = item, lineageStatus = status)
            }

            _items.value = galleryItems
            _isLoading.value = false
        }
    }
}
