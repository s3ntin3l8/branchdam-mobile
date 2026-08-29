package com.branchdam.mobile.lineage

import java.io.File
import java.io.InputStream

data class MotionPhotoInfo(
    val isMotionPhoto: Boolean,
    val microVideoOffset: Long = 0L,
    val microVideoLength: Long = 0L
)

object MotionPhotoExtractor {

    private val XMP_HEADER = "http://ns.google.com/photos/1.0/camera/".toByteArray(Charsets.UTF_8)
    private val MICRO_VIDEO_OFFSET_TAG = "GCamera:MicroVideoOffset=\"".toByteArray(Charsets.UTF_8)

    /**
     * Inspects a JPEG file stream for Google Camera Motion Photo XMP metadata.
     */
    fun detectMotionPhoto(file: File): MotionPhotoInfo {
        if (!file.exists() || file.length() < 1024) {
            return MotionPhotoInfo(isMotionPhoto = false)
        }

        try {
            file.inputStream().use { stream ->
                return parseMotionPhotoStream(stream, file.length())
            }
        } catch (_: Exception) {
            return MotionPhotoInfo(isMotionPhoto = false)
        }
    }

    fun parseMotionPhotoStream(stream: InputStream, fileLength: Long): MotionPhotoInfo {
        val buffer = ByteArray(64 * 1024)
        val bytesRead = stream.read(buffer)
        if (bytesRead <= 0) return MotionPhotoInfo(isMotionPhoto = false)

        val text = String(buffer, 0, bytesRead, Charsets.ISO_8859_1)

        if (text.contains("GCamera:MotionPhoto=\"1\"") || text.contains("GCamera:MicroVideo=\"1\"")) {
            val offset = extractOffset(text)
            if (offset > 0 && offset < fileLength) {
                return MotionPhotoInfo(
                    isMotionPhoto = true,
                    microVideoOffset = fileLength - offset,
                    microVideoLength = offset
                )
            }
            return MotionPhotoInfo(isMotionPhoto = true, microVideoOffset = 0L, microVideoLength = 0L)
        }

        return MotionPhotoInfo(isMotionPhoto = false)
    }

    private fun extractOffset(xmpText: String): Long {
        val pattern = Regex("GCamera:MicroVideoOffset=\"(\\d+)\"")
        val match = pattern.find(xmpText)
        return match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }
}
