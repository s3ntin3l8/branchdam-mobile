package com.branchdam.mobile.otg

import java.util.Locale

data class OtgMediaCandidate(
    val uri: String,
    val relativePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val lastModifiedUnix: Long,
    val isRaw: Boolean,
    val isVideo: Boolean
) {
    companion object {
        private val RAW_EXTENSIONS = setOf(
            "dng", "cr3", "cr2", "arw", "nef", "nrw",
            "orf", "rw2", "pef", "raf", "3fr"
        )

        private val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "heic", "heif", "png", "webp", "tif", "tiff"
        )

        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mov", "m4v", "avi", "mkv"
        )

        fun isRawExtension(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return ext in RAW_EXTENSIONS
        }

        fun isVideoExtension(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return ext in VIDEO_EXTENSIONS
        }

        fun isSupportedMedia(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return ext in RAW_EXTENSIONS || ext in IMAGE_EXTENSIONS || ext in VIDEO_EXTENSIONS
        }

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            if (bytes < 1024) return "$bytes B"
            val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
            val unit = "KMGTPE"[exp - 1]
            return String.format(Locale.US, "%.1f %cB", bytes / Math.pow(1024.0, exp.toDouble()), unit)
        }
    }
}

data class OtgScanResult(
    val deviceLabel: String,
    val rootUri: String,
    val candidates: List<OtgMediaCandidate>,
    val totalSizeBytes: Long = candidates.sumOf { it.sizeBytes },
    val rawCount: Int = candidates.count { it.isRaw },
    val videoCount: Int = candidates.count { it.isVideo },
    val jpegCount: Int = candidates.count { !it.isRaw && !it.isVideo }
) {
    val totalCount: Int
        get() = candidates.size

    val formattedTotalSize: String
        get() = OtgMediaCandidate.formatBytes(totalSizeBytes)
}
