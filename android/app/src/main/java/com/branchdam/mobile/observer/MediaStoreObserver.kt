package com.branchdam.mobile.observer

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.branchdam.mobile.NativeBridge
import com.branchdam.mobile.service.SyncScheduler
import java.util.concurrent.atomic.AtomicLong

class MediaStoreObserver(
    private val context: Context,
    handler: Handler = Handler(Looper.getMainLooper())
) : ContentObserver(handler) {

    private val lastScannedTimestamp = AtomicLong(System.currentTimeMillis() / 1000L - 60)

    fun register() {
        val resolver = context.contentResolver
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, this)
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, this)
    }

    fun unregister() {
        context.contentResolver.unregisterContentObserver(this)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        scanAndEnqueueNewMedia()
    }

    fun scanAndEnqueueNewMedia(): Int {
        val minTimestamp = lastScannedTimestamp.get()
        val images = MediaScanner.queryRecentImages(context, minDateTakenUnix = minTimestamp * 1000L)
        val videos = MediaScanner.queryRecentVideos(context, minDateTakenUnix = minTimestamp * 1000L)

        var enqueuedCount = 0
        val allMedia = (images + videos).sortedBy { it.dateTakenUnix }
        val enqueuedIds = mutableListOf<String>()

        for (item in allMedia) {
            if (item.filePath.isNotEmpty() && !com.branchdam.mobile.service.ImportConfirmationNotifier.isItemSuppressed(item.contentUri)) {
                NativeBridge.enqueueMedia(
                    localPath = item.filePath,
                    filename = item.displayName,
                    capturedAtUnix = item.dateTakenUnix,
                    localId = item.contentUri
                )
                enqueuedCount++
                enqueuedIds.add(item.contentUri)
                if (item.dateTakenUnix > lastScannedTimestamp.get()) {
                    lastScannedTimestamp.set(item.dateTakenUnix)
                }
            }
        }

        if (enqueuedCount > 0) {
            val autoImport = com.branchdam.mobile.service.ImportConfirmationNotifier.getAutoImportEnabled(context)
            if (autoImport) {
                SyncScheduler.triggerImmediateSync(context)
            } else {
                com.branchdam.mobile.service.ImportConfirmationNotifier.showImportConfirmation(
                    context = context,
                    newItemCount = enqueuedCount,
                    itemIds = enqueuedIds.toTypedArray()
                )
            }
        }

        return enqueuedCount
    }
}
