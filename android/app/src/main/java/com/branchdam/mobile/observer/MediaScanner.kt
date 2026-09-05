package com.branchdam.mobile.observer

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

object MediaScanner {

    private const val TAG = "MediaScanner"

    fun queryRecentImages(context: Context, minDateTakenUnix: Long = 0, limit: Int = 100): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN
        )

        val selection = "${MediaStore.Images.Media.DATE_TAKEN} > ?"
        val selectionArgs = arrayOf(minDateTakenUnix.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC LIMIT $limit"

        return queryMediaUri(
            context,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder,
            isVideo = false
        )
    }

    fun queryRecentVideos(context: Context, minDateTakenUnix: Long = 0, limit: Int = 50): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_TAKEN
        )

        val selection = "${MediaStore.Video.Media.DATE_TAKEN} > ?"
        val selectionArgs = arrayOf(minDateTakenUnix.toString())
        val sortOrder = "${MediaStore.Video.Media.DATE_TAKEN} DESC LIMIT $limit"

        return queryMediaUri(
            context,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder,
            isVideo = true
        )
    }

    private fun queryMediaUri(
        context: Context,
        uri: Uri,
        projection: Array<String>,
        selection: String,
        selectionArgs: Array<String>,
        sortOrder: String,
        isVideo: Boolean
    ): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val cursor: Cursor? = try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
        } catch (e: SecurityException) {
            // Cold launch can race the runtime permission grant: the
            // ViewModel's `init` fires the query before the user has
            // tapped "Allow" on the permission dialog. Returning an
            // empty list lets the UI render its empty state instead of
            // a red error message; the user can refresh once the
            // permission is granted (the relevant screens expose a
            // refresh action, and the MediaStoreObserver re-enqueues
            // on the next onChange).
            //
            // We log the exception (with the URI) so that *other*
            // SecurityException causes — cross-user URI access, the
            // Android 14+ photo-picker race, a malformed sub-URI —
            // remain distinguishable from the cold-launch case in
            // production telemetry. Silently swallowing this would
            // make any future "no media found" regression invisible.
            Log.w(TAG, "queryMediaUri($uri) denied; returning empty list", e)
            return emptyList()
        }

        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dataColumn = it.getColumnIndex(MediaStore.MediaColumns.DATA)
            val mimeColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)

            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val displayName = it.getString(nameColumn) ?: "unknown"
                val filePath = if (dataColumn != -1) it.getString(dataColumn) ?: "" else ""
                val mimeType = it.getString(mimeColumn) ?: if (isVideo) "video/mp4" else "image/jpeg"
                val sizeBytes = it.getLong(sizeColumn)
                val dateTaken = it.getLong(dateColumn) / 1000L

                val itemUri = ContentUris.withAppendedId(uri, id).toString()
                val isRaw = displayName.endsWith(".dng", ignoreCase = true) || mimeType == "image/x-adobe-dng"

                items.add(
                    MediaItem(
                        id = id,
                        contentUri = itemUri,
                        filePath = filePath,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = sizeBytes,
                        dateTakenUnix = dateTaken,
                        isRaw = isRaw
                    )
                )
            }
        }
        return items
    }
}
