package com.branchdam.mobile.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.Dimension
import java.io.BufferedInputStream

/**
 * A Coil [Fetcher] that extracts fast JPEG previews / thumbnails for RAW (DNG) files
 * instead of demosaicing multi-megabyte raw sensor data.
 *
 * It uses:
 * 1. Android's [android.content.ContentResolver.loadThumbnail] API on Android 10+ (API 29+),
 *    which retrieves pre-rendered thumbnails from the OS MediaStore cache in < 15ms.
 * 2. [ExifInterface.getThumbnailBytes] to extract embedded JPEG preview thumbnails
 *    from the DNG file header on older API levels or standalone file URIs.
 * 3. Fallback: returns `null` so Coil can fall back to standard decoding pipeline if no
 *    embedded thumbnail exists.
 */
class RawPreviewFetcher(
    private val context: Context,
    private val uri: Uri,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bitmap = loadRawPreviewBitmap(context, uri, options) ?: return null
        return DrawableResult(
            drawable = BitmapDrawable(context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (isRawUri(context, data)) {
                return RawPreviewFetcher(context, data, options)
            }
            return null
        }
    }

    companion object {
        fun isRawUri(context: Context, uri: Uri): Boolean {
            val scheme = uri.scheme
            val path = uri.path?.lowercase() ?: ""
            val lastPathSegment = uri.lastPathSegment?.lowercase() ?: ""

            if (lastPathSegment.endsWith(".dng") || lastPathSegment.endsWith(".raw") ||
                lastPathSegment.endsWith(".cr2") || lastPathSegment.endsWith(".nef") ||
                lastPathSegment.endsWith(".arw") || path.endsWith(".dng") || path.endsWith(".raw")
            ) {
                return true
            }

            if (scheme == "content") {
                try {
                    val mimeType = context.contentResolver.getType(uri)?.lowercase()
                    if (mimeType != null && (mimeType.contains("dng") || mimeType.contains("raw") || mimeType.contains("adobe"))) {
                        return true
                    }
                } catch (_: Throwable) {
                    // Ignore ContentResolver errors
                }
            }
            return false
        }

        fun loadRawPreviewBitmap(context: Context, uri: Uri, options: Options): Bitmap? {
            // 1. Try ContentResolver.loadThumbnail on Android 10+ (API 29+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val targetW = (options.size.width as? Dimension.Pixels)?.px ?: 1024
                    val targetH = (options.size.height as? Dimension.Pixels)?.px ?: 1024
                    val size = Size(targetW.coerceAtLeast(256), targetH.coerceAtLeast(256))
                    val thumbnail = context.contentResolver.loadThumbnail(uri, size, null)
                    if (thumbnail != null) {
                        return thumbnail
                    }
                } catch (_: Throwable) {
                    // Fallback if loadThumbnail fails for non-MediaStore URIs
                }
            }

            // 2. Try ExifInterface embedded JPEG thumbnail
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val bufferedStream = BufferedInputStream(inputStream)
                    val exif = ExifInterface(bufferedStream)
                    if (exif.hasThumbnail()) {
                        val thumbBytes = exif.thumbnailBytes
                        if (thumbBytes != null) {
                            val targetW = (options.size.width as? Dimension.Pixels)?.px ?: 1024
                            val targetH = (options.size.height as? Dimension.Pixels)?.px ?: 1024

                            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size, boundsOpts)

                            var sampleSize = 1
                            val rawW = boundsOpts.outWidth
                            val rawH = boundsOpts.outHeight
                            if (rawH > targetH || rawW > targetW) {
                                val halfH = rawH / 2
                                val halfW = rawW / 2
                                while ((halfH / sampleSize) >= targetH && (halfW / sampleSize) >= targetW) {
                                    sampleSize *= 2
                                }
                            }

                            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                            val bitmap = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size, decodeOpts)
                            if (bitmap != null) {
                                val rotation = exif.rotationDegrees
                                return if (rotation != 0) {
                                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                                } else {
                                    bitmap
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // Fallback
            }

            return null
        }
    }
}
