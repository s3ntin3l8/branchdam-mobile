package com.branchdam.mobile.observer

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.lineage.EditCorrelator
import com.branchdam.mobile.lineage.MotionPhotoExtractor
import com.branchdam.mobile.lineage.PairDetector
import com.branchdam.mobile.service.SyncScheduler
import com.branchdam.mobile.triage.TrashSyncObserver
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class MediaStoreObserver(
    private val context: Context,
    handler: Handler = Handler(HandlerThread("MediaStoreObserver").apply { start() }.looper)
) : ContentObserver(handler) {

    private val lastScannedTimestamp = AtomicLong(System.currentTimeMillis() / 1000L - 60)
    private val lastTrashScanTimestamp = AtomicLong(0L)

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

        // D.4: Trash sync — only process items trashed since the last scan
        // to avoid re-enqueueing delete events for items lingering in the
        // recycle bin (up to 30 days on Android 11+).
        val trashSince = lastTrashScanTimestamp.get()
        lastTrashScanTimestamp.set(System.currentTimeMillis() / 1000L)
        val deletedCount = TrashSyncObserver.processTrashedItems(context, trashSince) { contentUri ->
            contentUri
        }
        if (deletedCount > 0) {
            SyncScheduler.triggerImmediateSync(context)
        }
    }

    fun scanAndEnqueueNewMedia(): Int {
        val minTimestamp = lastScannedTimestamp.get()
        val images = MediaScanner.queryRecentImages(context, minDateTakenUnix = minTimestamp * 1000L)
        val videos = MediaScanner.queryRecentVideos(context, minDateTakenUnix = minTimestamp * 1000L)

        val allMedia = (images + videos).sortedBy { it.dateTakenUnix }
        val newItems = mutableListOf<MediaItem>()

        for (item in allMedia) {
            if (item.filePath.isNotEmpty() && !com.branchdam.mobile.service.ImportConfirmationNotifier.isItemSuppressed(item.contentUri)) {
                newItems.add(item)
                if (item.dateTakenUnix > lastScannedTimestamp.get()) {
                    lastScannedTimestamp.set(item.dateTakenUnix)
                }
            }
        }

        if (newItems.isNotEmpty()) {
            val autoImport = com.branchdam.mobile.service.ImportConfirmationNotifier.getAutoImportEnabled(context)
            if (autoImport) {
                for (item in newItems) {
                    EngineHolder.enqueueMedia(
                        localPath = item.filePath,
                        filename = item.displayName,
                        capturedAtUnix = item.dateTakenUnix,
                        localId = item.contentUri
                    )
                }

                // D.3: Lineage pipeline — detect pairs, edits, and motion photos.
                runLineageDetection(newItems)

                SyncScheduler.triggerImmediateSync(context)
            } else {
                com.branchdam.mobile.service.ImportConfirmationNotifier.stagePendingItems(newItems)
                com.branchdam.mobile.service.ImportConfirmationNotifier.showImportConfirmation(
                    context = context,
                    newItemCount = newItems.size,
                    itemIds = newItems.map { it.contentUri }.toTypedArray()
                )
            }
        }

        return newItems.size
    }

    private fun runLineageDetection(newItems: List<MediaItem>) {
        // Pair detection (DNG + JPEG companion pairs).
        val pairs = PairDetector.findPairs(newItems)
        PairDetector.registerPairLineage(pairs)

        // Edit correlation (in-phone editor exports → camera roll master).
        val editDerivatives = newItems.filter { item ->
            item.filePath.contains("Edited", ignoreCase = true) ||
                item.filePath.contains("Restored", ignoreCase = true) ||
                item.filePath.contains("Luminar", ignoreCase = true)
        }
        if (editDerivatives.isNotEmpty()) {
            val edits = EditCorrelator.findInPhoneEdits(newItems, editDerivatives)
            EditCorrelator.registerEditLineage(edits)
        }

        // Motion photo detection — DNG/HEIF motion photos with embedded micro video.
        for (item in newItems.filter { !it.isVideo }) {
            val file = File(item.filePath)
            if (MotionPhotoExtractor.detectMotionPhoto(file).isMotionPhoto) {
                EngineHolder.enqueueLineageEvent(
                    parentLocalID = item.contentUri,
                    childLocalID = item.contentUri,
                    relationshipType = "MOTION_PHOTO_CONTAINS",
                    resolver = "android_motion_photo_xmp",
                    confidence = 1.00
                )
            }
        }
    }
}
