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
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MediaStoreObserver(
    private val context: Context,
    handler: Handler = Handler(HandlerThread("MediaStoreObserver").apply { start() }.looper)
) : ContentObserver(handler) {

    private val lastScannedTimestamp = AtomicLong(System.currentTimeMillis() / 1000L - 60)

    /**
     * Tracks content URIs of trashed items already processed. Prevents
     * re-enqueueing EVENT_NODE_DELETED for items lingering in the Android 11+
     * recycle bin (up to 30 days) across successive onChange callbacks.
     */
    private val processedTrashedUris = mutableSetOf<String>()

    /**
     * Bounded to [Dispatchers.IO] so the ContentProvider queries and
     * file I/O in [scanAndEnqueueNewMedia] do not block the
     * HandlerThread that [onChange] runs on, and never the main
     * thread. T2-3 lifts the blocking calls out of the observer
     * callback so the app stays responsive during burst photo
     * capture and iCloud sync.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("MediaStoreObserver")
    )

    /**
     * Coalesces rapid-fire onChange callbacks into a single scan +
     * lineage pipeline run after a 500ms quiet window. Burst capture
     * that emits 50 events in 1 second collapses to one scan 500ms
     * after the last event, satisfying the T2-3 acceptance criterion
     * of at most 2 scans per burst.
     */
    private val debouncer = Debouncer(
        scope = scope,
        windowMs = DEBOUNCE_WINDOW_MS,
        onFire = ::scanAndEnqueueNewMedia,
    )

    fun register() {
        val resolver = context.contentResolver
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, this)
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, this)
    }

    fun unregister() {
        context.contentResolver.unregisterContentObserver(this)
        debouncer.close()
        scope.cancel()
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        // Schedule a debounced scan + lineage run. Non-blocking: the
        // [debouncer] coalesces bursts of onChange into one scan after
        // a quiet window so a 50-event burst does not produce 50
        // ContentProvider rescans.
        debouncer.trigger()

        // D.4: Trash sync — MATCH_ONLY returns all items in the 30-day
        // recycle bin. The in-memory processedTrashedUris set prevents
        // re-enqueueing delete events across successive onChange calls.
        val deletedCount = TrashSyncObserver.processTrashedItems(context, processedTrashedUris) { contentUri ->
            contentUri
        }
        if (deletedCount > 0) {
            SyncScheduler.triggerImmediateSync(context)
        }
    }

    /**
     * Queries MediaStore for recent images and videos, enqueues new
     * items via [EngineHolder], and runs the lineage pipeline. Runs on
     * [Dispatchers.IO] because the caller's [scope] is bounded there.
     */
    private suspend fun scanAndEnqueueNewMedia() {
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
                SyncScheduler.triggerImmediateSync(context)
            } else {
                com.branchdam.mobile.service.ImportConfirmationNotifier.stagePendingItems(newItems)
                com.branchdam.mobile.service.ImportConfirmationNotifier.showImportConfirmation(
                    context = context,
                    newItemCount = newItems.size,
                    itemIds = newItems.map { it.contentUri }.toTypedArray()
                )
            }

            // D.3: Lineage pipeline runs for BOTH auto-import and
            // confirmation-based import. Confirmation-accepted items are
            // enqueued later by the notification action, but lineage edges
            // between the items in this batch are still valid and should
            // be recorded now (the engine deduplicates by local ID).
            runLineageDetection(newItems)
        }
    }

    private fun runLineageDetection(newItems: List<MediaItem>) {
        // Pair detection (DNG + JPEG companion pairs).
        val pairs = PairDetector.findPairs(newItems)
        PairDetector.registerPairLineage(pairs)

        // Edit correlation (in-phone editor exports -> camera roll master).
        val editDerivatives = newItems.filter { item ->
            item.filePath.contains("Edited", ignoreCase = true) ||
                item.filePath.contains("Restored", ignoreCase = true) ||
                item.filePath.contains("Luminar", ignoreCase = true)
        }
        if (editDerivatives.isNotEmpty()) {
            val edits = EditCorrelator.findInPhoneEdits(newItems, editDerivatives)
            EditCorrelator.registerEditLineage(edits)
        }

        // Motion photo detection -- DNG/HEIF motion photos with embedded micro video.
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

    private companion object {
        const val DEBOUNCE_WINDOW_MS = 500L
    }
}
