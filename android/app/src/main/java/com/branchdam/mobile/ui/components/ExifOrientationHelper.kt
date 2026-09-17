package com.branchdam.mobile.ui.components

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

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
            }
        } catch (_: Throwable) {
            0f
        }
    }
}
