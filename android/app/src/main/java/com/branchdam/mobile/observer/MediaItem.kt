package com.branchdam.mobile.observer

data class MediaItem(
    val id: Long,
    val contentUri: String,
    val filePath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateTakenUnix: Long,
    val isRaw: Boolean,
    val burstId: String? = null
) {
    val isVideo: Boolean
        get() = mimeType.startsWith("video/")

    val isDng: Boolean
        get() = displayName.endsWith(".dng", ignoreCase = true) || mimeType == "image/x-adobe-dng"
}
