package com.branchdam.mobile.triage

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/**
 * A camera-roll item's size and modification time (unix seconds) as
 * MediaStore reports them (`SIZE`, `DATE_MODIFIED`). The Go engine only
 * ever sees a staged copy of the bytes, so the shell reads this twice: at
 * enqueue, where the engine records it next to the BLAKE3 as the reclaim
 * baseline, and again immediately before a Safe-Space reclaim, where the
 * engine refuses the delete (`local-changed`) unless it still matches
 * (#229). A zero field means "unknown"; the engine never reclaims an item
 * with no baseline.
 */
data class LocalFileState(val sizeBytes: Long, val modifiedUnix: Long) {
    companion object {
        val UNKNOWN = LocalFileState(0L, 0L)
    }
}

private const val TAG = "LocalFileState"

/**
 * Reads [contentUri]'s current [LocalFileState] from MediaStore. Returns
 * [LocalFileState.UNKNOWN] for anything that is not a readable
 * `content://` row (a synthetic child ID, an OTG card file, a revoked
 * permission), which the engine treats as "cannot prove unchanged".
 */
fun queryLocalFileState(context: Context, contentUri: String): LocalFileState {
    if (!contentUri.startsWith("content://")) return LocalFileState.UNKNOWN
    val projection = arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
    return try {
        context.contentResolver.query(Uri.parse(contentUri), projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use LocalFileState.UNKNOWN
            val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val modifiedIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            LocalFileState(
                sizeBytes = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L,
                modifiedUnix = if (modifiedIdx >= 0 && !cursor.isNull(modifiedIdx)) cursor.getLong(modifiedIdx) else 0L,
            )
        } ?: LocalFileState.UNKNOWN
    } catch (e: Exception) {
        Log.w(TAG, "querying size/date_modified for $contentUri failed", e)
        LocalFileState.UNKNOWN
    }
}
