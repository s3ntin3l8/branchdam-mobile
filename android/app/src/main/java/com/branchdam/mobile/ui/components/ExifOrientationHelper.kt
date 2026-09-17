package com.branchdam.mobile.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation

class RotateTransformation(private val degrees: Float) : Transformation {
    override val cacheKey: String = "RotateTransformation_$degrees"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (degrees == 0f) return input
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(input, 0, 0, input.width, input.height, matrix, true)
    }
}

object ExifOrientationHelper {

    /**
     * Reads the EXIF orientation tag from the given content URI or file path
     * and returns the required rotation degrees (0f, 90f, 180f, or 270f).
     */
    fun getExifRotationDegrees(context: Context, contentUriString: String?): Float {
        if (contentUriString.isNullOrBlank()) return 0f
        return try {
            val uri = Uri.parse(contentUriString)
            val inputStream = context.contentResolver.openInputStream(uri) ?: return 0f
            inputStream.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    ExifInterface.ORIENTATION_TRANSPOSE -> 90f
                    ExifInterface.ORIENTATION_TRANSVERSE -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Throwable) {
            0f
        }
    }

    /**
     * Applies EXIF auto-rotation to a Coil [ImageRequest.Builder] using
     * [RotateTransformation] so Coil rotates the bitmap before layout
     * measurement. This ensures [ContentScale.Crop] and [ContentScale.Fit]
     * measure the already-rotated bitmap dimensions.
     */
    fun applyExifOrientation(
        builder: ImageRequest.Builder,
        context: Context,
        contentUriString: String?
    ): ImageRequest.Builder {
        val degrees = getExifRotationDegrees(context, contentUriString)
        if (degrees != 0f) {
            builder.transformations(RotateTransformation(degrees))
        }
        return builder
    }
}
