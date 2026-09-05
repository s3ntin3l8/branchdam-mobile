package com.branchdam.mobile.lineage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.InputStream

data class MotionPhotoInfo(
    val isMotionPhoto: Boolean,
    val microVideoOffset: Long = 0L,
    val microVideoLength: Long = 0L
)

object MotionPhotoExtractor {

    private const val TAG = "MotionPhotoExtractor"

    private val XMP_HEADER = "http://ns.google.com/photos/1.0/camera/".toByteArray(Charsets.UTF_8)
    private val MICRO_VIDEO_OFFSET_TAG = "GCamera:MicroVideoOffset=\"".toByteArray(Charsets.UTF_8)

    fun detectMotionPhoto(context: Context, uri: Uri): MotionPhotoInfo {
        return try {
            val size = querySize(context, uri)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                parseMotionPhotoStream(stream, size)
            } ?: MotionPhotoInfo(isMotionPhoto = false)
        } catch (e: Exception) {
            // Surface the failure (permission, deleted file, broken
            // URI, ...) at warn level rather than the previous silent
            // swallow so a regression that always hits this catch is
            // visible in logcat.
            Log.w(TAG, "detectMotionPhoto($uri) failed", e)
            MotionPhotoInfo(isMotionPhoto = false)
        }
    }

    fun detectMotionPhoto(file: File): MotionPhotoInfo {
        if (!file.exists() || file.length() < 1024) {
            return MotionPhotoInfo(isMotionPhoto = false)
        }

        return try {
            file.inputStream().use { stream ->
                parseMotionPhotoStream(stream, file.length())
            }
        } catch (e: Exception) {
            Log.w(TAG, "detectMotionPhoto(${file.absolutePath}) failed", e)
            MotionPhotoInfo(isMotionPhoto = false)
        }
    }

    /**
     * Looks up [OpenableColumns.SIZE] for the given URI. Returns
     * `-1L` when the size is unknown (the query failed, the URI
     * doesn't support size lookup, or the resolver returned a null
     * cursor). `parseMotionPhotoStream` treats any `fileLength <= 0`
     * as "skip the bounds check" so a corrupt XMP block with an
     * out-of-range offset is reported as a motion photo only when
     * the offset is independently positive (which is what the
     * `offset > 0` half of the bounds check already enforces).
     *
     * Exposed as `internal` so a unit test can verify the size
     * lookup contract (a real `ContentResolver` is required to
     * exercise the full Uri path; see the kdoc on
     * [MotionPhotoExtractorTest] for the integration shape).
     */
    internal fun querySize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else -1L
            } ?: -1L
        } catch (e: Exception) {
            Log.w(TAG, "querySize($uri) failed; bounds check disabled", e)
            -1L
        }
    }

    fun parseMotionPhotoStream(stream: InputStream, fileLength: Long): MotionPhotoInfo {
        val buffer = ByteArray(64 * 1024)
        val bytesRead = stream.read(buffer)
        if (bytesRead <= 0) return MotionPhotoInfo(isMotionPhoto = false)

        val text = String(buffer, 0, bytesRead, Charsets.ISO_8859_1)

        if (text.contains("GCamera:MotionPhoto=\"1\"") || text.contains("GCamera:MicroVideo=\"1\"")) {
            val offset = extractOffset(text)
            // Bounds check: when fileLength is known, the offset must
            // be a positive value smaller than the file (so the
            // micro-video trailer is a non-empty suffix of the file).
            // When fileLength is unknown (fileLength <= 0), the
            // positive check alone is sufficient — the XMP block
            // declares the offset as a string of digits and any
            // non-zero value is a plausible trailer.
            //
            // A negative or zero offset (the XMP block didn't declare
            // a `GCamera:MicroVideoOffset`, or the declared value is
            // malformed) is also rejected: the original code returned
            // `isMotionPhoto = true` with zero `microVideoOffset` /
            // `microVideoLength`, which would feed junk values into
            // the lineage event.
            if (offset > 0 && (fileLength <= 0 || offset < fileLength)) {
                val microVideoOffset = if (fileLength > 0) fileLength - offset else 0L
                return MotionPhotoInfo(
                    isMotionPhoto = true,
                    microVideoOffset = microVideoOffset,
                    microVideoLength = offset,
                )
            }
            // Bounds check failed (or offset is non-positive): the
            // XMP says this is a motion photo but the offset is
            // unreliable. Refuse to classify rather than emit a
            // lineage event with a zero-length micro-video trailer.
            return MotionPhotoInfo(isMotionPhoto = false)
        }

        return MotionPhotoInfo(isMotionPhoto = false)
    }

    private fun extractOffset(xmpText: String): Long {
        val pattern = Regex("GCamera:MicroVideoOffset=\"(\\d+)\"")
        val match = pattern.find(xmpText)
        return match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }
}
