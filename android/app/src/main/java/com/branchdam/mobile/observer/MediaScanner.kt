package com.branchdam.mobile.observer

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

object MediaScanner {

    private const val TAG = "MediaScanner"

    /**
     * The `ContentResolver.QUERY_ARG_*` bundle keys used by the
     * API 26+ `query(uri, projection, bundle, cancellationSignal)`
     * overload. Mirrored as local `const val`s from
     * `ContentResolver`'s public string constants (same values, see
     * AOSP `frameworks/base/core/java/android/content/ContentResolver.java`)
     * so the testable seam `buildQueryBundle` has a single
     * self-contained source of truth and the keys are visible to
     * static analysis. The platform constants are unstable to
     * reference directly across Robolectric API levels, so the
     * string literals are the safer surface.
     */
    private const val QUERY_ARG_SQL_SELECTION = "android:query-arg-sql-selection"
    private const val QUERY_ARG_SQL_SELECTION_ARGS = "android:query-arg-sql-selection-args"
    private const val QUERY_ARG_SQL_SORT_ORDER = "android:query-arg-sql-sort-order"
    private const val QUERY_ARG_LIMIT = "android:query-arg-limit"

    fun queryRecentImages(context: Context, minDateTakenUnix: Long = 0, limit: Int = 100): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.ORIENTATION
        )

        val selection = "${MediaStore.Images.Media.DATE_TAKEN} > ?"
        val selectionArgs = arrayOf(minDateTakenUnix.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        return queryMediaUri(
            context,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder,
            isVideo = false,
            limit = limit
        )
    }

    fun queryRecentVideos(context: Context, minDateTakenUnix: Long = 0, limit: Int = 50): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )

        val selection = "${MediaStore.Video.Media.DATE_TAKEN} > ?"
        val selectionArgs = arrayOf(minDateTakenUnix.toString())
        val sortOrder = "${MediaStore.Video.Media.DATE_TAKEN} DESC"

        return queryMediaUri(
            context,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder,
            isVideo = true,
            limit = limit
        )
    }

    fun extractFolderName(bucketDisplayName: String?, filePath: String): String {
        if (!bucketDisplayName.isNullOrBlank()) {
            return bucketDisplayName
        }
        if (filePath.isNotBlank()) {
            val parent = if (filePath.contains('/')) {
                filePath.substringBeforeLast('/').substringAfterLast('/')
            } else ""
            if (parent.isNotBlank() && parent != "0" && parent != "emulated") {
                return parent
            }
        }
        return "Camera"
    }

    fun queryAvailableFolders(context: Context): List<String> {
        val folders = mutableSetOf<String>()
        folders.add("Camera")
        val projection = arrayOf(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.DATA)
        val bundle = buildQueryBundle("", arrayOf(), "", 2000)

        for (uri in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            try {
                context.contentResolver.query(uri, projection, bundle, null)?.use { cursor ->
                    val bucketCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    while (cursor.moveToNext()) {
                        val bucket = if (bucketCol != -1) cursor.getString(bucketCol) else null
                        val data = if (dataCol != -1) cursor.getString(dataCol) ?: "" else ""
                        val folder = extractFolderName(bucket, data)
                        if (folder.isNotBlank()) {
                            folders.add(folder)
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "queryAvailableFolders denied", e)
            }
        }
        return folders.sorted()
    }

    private fun queryMediaUri(
        context: Context,
        uri: Uri,
        projection: Array<String>,
        selection: String,
        selectionArgs: Array<String>,
        sortOrder: String,
        isVideo: Boolean,
        limit: Int
    ): List<MediaItem> {
        val items = mutableListOf<MediaItem>()

        val bundle = buildQueryBundle(selection, selectionArgs, sortOrder, limit)

        val cursor: Cursor? = try {
            context.contentResolver.query(uri, projection, bundle, null)
        } catch (e: SecurityException) {
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
            val bucketColumn = it.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val orientationColumn = it.getColumnIndex(MediaStore.Images.Media.ORIENTATION)

            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val displayName = it.getString(nameColumn) ?: "unknown"
                val filePath = if (dataColumn != -1) it.getString(dataColumn) ?: "" else ""
                val mimeType = it.getString(mimeColumn) ?: if (isVideo) "video/mp4" else "image/jpeg"
                val sizeBytes = it.getLong(sizeColumn)
                val dateTaken = it.getLong(dateColumn) / 1000L
                val rawBucket = if (bucketColumn != -1) it.getString(bucketColumn) else null
                val folderName = extractFolderName(rawBucket, filePath)
                val orientation = if (orientationColumn != -1) it.getInt(orientationColumn) else 0

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
                        isRaw = isRaw,
                        folderName = folderName,
                        orientationDegrees = orientation
                    )
                )
            }
        }
        return items
    }

    /**
     * Builds the `Bundle` of `ContentResolver.QUERY_ARG_*` keys used by
     * the API 26+ `query(uri, projection, bundle, cancellationSignal)`
     * overload. Extracted from [queryMediaUri] so unit tests can
     * exercise the bundle contract (selection, args, sort order,
     * limit) without needing a real `ContentResolver` or Robolectric.
     *
     * The pre-API-34 sortOrder interpolation (`... DESC LIMIT $limit`)
     * silently broke on Android 14+ because the system rejects the
     * legacy `query(uri, projection, selection, selectionArgs,
     * sortOrder)` overload with an `IllegalArgumentException` that
     * surfaced verbatim as the "invalid token limit" red error on
     * Lineage Audit and Gallery. See PR #130.
     *
     * Returns the `Bundle` directly rather than a wrapper data class
     * so the resolver call site is a single allocation. The test seam
     * is the key string literals: any regression that drops the limit
     * out of `QUERY_ARG_LIMIT` and into the sortOrder string surfaces
     * as the "invalid token limit" IAE on a real device.
     */
    internal fun buildQueryBundle(
        selection: String,
        selectionArgs: Array<String>,
        sortOrder: String,
        limit: Int,
    ): android.os.Bundle = android.os.Bundle().apply {
        if (selection.isNotBlank()) {
            putString(QUERY_ARG_SQL_SELECTION, selection)
            if (selectionArgs.isNotEmpty()) {
                putStringArray(QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            }
        }
        if (sortOrder.isNotBlank()) {
            putString(QUERY_ARG_SQL_SORT_ORDER, sortOrder)
        }
        if (limit > 0) {
            putInt(QUERY_ARG_LIMIT, limit)
        }
    }
}
