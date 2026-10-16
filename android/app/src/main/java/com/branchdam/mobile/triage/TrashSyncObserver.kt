package com.branchdam.mobile.triage

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.branchdam.mobile.EngineHolder

data class TrashedMediaItem(
    val id: Long,
    val contentUri: String,
    val displayName: String,
    val isTrashed: Boolean
)

object TrashSyncObserver {

    /**
     * The MediaStore collections whose recycle bin is synced. Media is
     * enqueued for upload under `ContentUris.withAppendedId(<collection>,
     * id)` (MediaScanner), so each trashed row's local ID must be built
     * from the collection it was queried from. Videos used to be missed
     * entirely: only the Images collection was queried.
     */
    val TRASH_COLLECTIONS: List<Uri>
        get() = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )

    /**
     * Inspects MediaStore for trashed items (IS_TRASHED = 1 on Android 11+).
     * Items already recorded in [processed] are skipped to avoid
     * re-enqueueing delete events for items lingering in the recycle bin
     * (up to 30 days). Newly handled URIs are added to [processed], which
     * is persisted, and entries that have left the bin are dropped from it.
     *
     * If the item was an intentional offload (Free Up Space), suppression
     * is applied. Otherwise, enqueues EVENT_NODE_DELETED to remove the
     * derivative export from Immich.
     *
     * The delete event is keyed by the item's content URI: that is the
     * local ID media is enqueued for upload under (MediaScanner builds it
     * with ContentUris.withAppendedId on the same collection URI), and the
     * core resolves it to the server node UUID at sync time. Items that
     * were never uploaded are dropped by the core, not sent.
     */
    fun processTrashedItems(
        context: Context,
        processed: ProcessedTrashStore,
    ): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return 0
        }

        val bin = queryTrashedBin(context)
        val eventsEnqueued = processNewlyTrashed(
            items = bin.items,
            processed = processed,
            isOffloaded = EngineHolder::isMediaOffloaded,
            enqueueDelete = { uri -> EngineHolder.enqueueDeleteEvent(uri).isNotEmpty() },
        )
        // Prune entries that left the bin, but only from a complete view of
        // it: a collection that failed to read would otherwise look empty.
        if (bin.complete) {
            processed.retainOnly(bin.items.mapTo(HashSet()) { it.contentUri })
        }
        return eventsEnqueued
    }

    /**
     * The per-item decision of [processTrashedItems], with the engine
     * calls injected for unit tests. Returns the number of delete events
     * enqueued.
     */
    internal fun processNewlyTrashed(
        items: List<TrashedMediaItem>,
        processed: ProcessedTrashStore,
        isOffloaded: (String) -> Boolean,
        enqueueDelete: (String) -> Boolean,
    ): Int {
        var eventsEnqueued = 0
        for (item in items) {
            // Skip items already handled in a previous pass.
            if (item.contentUri in processed) {
                continue
            }

            // Check if this deletion was an intentional offload.
            // EngineHolder's isMediaOffloaded is the B.2.3 fail-closed
            // path: a DB error returns false so the deletion is
            // suppressed (the audit's "verified required" invariant).
            if (isOffloaded(item.contentUri)) {
                // Suppress EVENT_NODE_DELETED - retain remote master and Immich export
                processed.add(item.contentUri)
                continue
            }

            if (enqueueDelete(item.contentUri)) {
                eventsEnqueued++
            }

            // Mark processed regardless of enqueue success to avoid
            // silent infinite retry loops.
            processed.add(item.contentUri)
        }
        return eventsEnqueued
    }

    /**
     * Queries MediaStore for all currently trashed images and videos
     * (Android 11+). A collection that can't be read contributes nothing.
     */
    fun queryTrashedMedia(context: Context): List<TrashedMediaItem> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return emptyList()
        }
        return queryTrashedBin(context).items
    }

    /** The recycle bin's contents, and whether every collection was read. */
    internal data class TrashedBin(val items: List<TrashedMediaItem>, val complete: Boolean)

    internal fun queryTrashedBin(context: Context): TrashedBin {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return TrashedBin(emptyList(), complete = true)
        }
        val items = mutableListOf<TrashedMediaItem>()
        var complete = true
        for (collection in TRASH_COLLECTIONS) {
            val rows = queryTrashedCollection(context, collection)
            if (rows == null) complete = false else items += rows
        }
        return TrashedBin(items, complete)
    }

    /**
     * Trashed rows of one collection, keyed by the content URI the media
     * was enqueued under, or null if the query failed.
     */
    private fun queryTrashedCollection(context: Context, collection: Uri): List<TrashedMediaItem>? {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.IS_TRASHED
        )

        val bundle = android.os.Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }

        return try {
            val cursor: Cursor = context.contentResolver.query(
                collection,
                projection,
                bundle,
                null
            ) ?: return null

            val items = mutableListOf<TrashedMediaItem>()
            cursor.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val trashedCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)

                while (it.moveToNext()) {
                    val id = it.getLong(idCol)
                    val name = it.getString(nameCol) ?: "unknown"
                    val isTrashed = it.getInt(trashedCol) == 1
                    val uri = ContentUris.withAppendedId(collection, id).toString()

                    items.add(TrashedMediaItem(id, uri, name, isTrashed))
                }
            }
            items
        } catch (_: Exception) {
            // Mock environments or restricted permissions.
            null
        }
    }
}
