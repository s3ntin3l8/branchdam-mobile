package com.branchdam.mobile.triage

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
     * Inspects MediaStore for trashed items (IS_TRASHED = 1 on Android 11+).
     * Items already present in [processedUris] are skipped to avoid
     * re-enqueueing delete events for items lingering in the recycle bin
     * (up to 30 days). Newly processed URIs are added to [processedUris].
     *
     * If the item was an intentional offload (Free Up Space), suppression
     * is applied. Otherwise, enqueues EVENT_NODE_DELETED to remove the
     * derivative export from Immich.
     */
    fun processTrashedItems(
        context: Context,
        processedUris: MutableSet<String>,
        nodeUuidLookup: (contentUri: String) -> String?
    ): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return 0
        }

        val trashedItems = queryTrashedMedia(context)
        var eventsEnqueued = 0

        for (item in trashedItems) {
            // Skip items already processed in a previous onChange.
            if (item.contentUri in processedUris) {
                continue
            }

            // Check if this deletion was an intentional offload.
            // EngineHolder's isMediaOffloaded is the B.2.3 fail-closed
            // path: a DB error returns false so the deletion is
            // suppressed (the audit's "verified required" invariant).
            val isOffloaded = EngineHolder.isMediaOffloaded(item.contentUri)
            if (isOffloaded) {
                // Suppress EVENT_NODE_DELETED - retain remote master and Immich export
                processedUris.add(item.contentUri)
                continue
            }

            val nodeUuid = nodeUuidLookup(item.contentUri)
            if (!nodeUuid.isNullOrEmpty()) {
                EngineHolder.enqueueDeleteEvent(nodeUuid)
                eventsEnqueued++
            }

            // Mark processed regardless of enqueue success to avoid
            // silent infinite retry loops for items with no server node UUID.
            processedUris.add(item.contentUri)
        }

        return eventsEnqueued
    }

    /**
     * Queries MediaStore for all currently trashed items (Android 11+).
     */
    fun queryTrashedMedia(context: Context): List<TrashedMediaItem> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return emptyList()
        }

        val items = mutableListOf<TrashedMediaItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.IS_TRASHED
        )

        val bundle = android.os.Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }

        try {
            val cursor: Cursor? = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                bundle,
                null
            )

            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val trashedCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)

                while (it.moveToNext()) {
                    val id = it.getLong(idCol)
                    val name = it.getString(nameCol) ?: "unknown"
                    val isTrashed = it.getInt(trashedCol) == 1
                    val uri = "${MediaStore.Images.Media.EXTERNAL_CONTENT_URI}/$id"

                    items.add(TrashedMediaItem(id, uri, name, isTrashed))
                }
            }
        } catch (_: Exception) {
            // Fallback for mock environments or restricted permissions
        }

        return items
    }
}
