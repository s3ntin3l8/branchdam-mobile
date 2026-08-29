package com.branchdam.mobile.lineage

import com.branchdam.mobile.NativeBridge
import com.branchdam.mobile.observer.MediaItem
import kotlin.math.abs

data class LineagePair(
    val masterRaw: MediaItem,
    val derivativeJpeg: MediaItem,
    val confidence: Double = 1.00,
    val resolver: String = "android_camera_pair"
)

object PairDetector {

    /**
     * Finds companion RAW (DNG) and JPEG pairs from recent media items.
     * Google Pixel and Android flagships shoot companion DNG and JPEG files sharing
     * the timestamp stem (e.g., PXL_20260829_051500.dng & PXL_20260829_051500.jpg)
     * or created within 2 seconds of each other.
     */
    fun findPairs(items: List<MediaItem>): List<LineagePair> {
        val raws = items.filter { it.isDng || it.isRaw }
        val jpegs = items.filter { !it.isDng && !it.isRaw && !it.isVideo }

        val pairs = mutableListOf<LineagePair>()
        val matchedJpegs = mutableSetOf<Long>()

        for (raw in raws) {
            val rawStem = extractStem(raw.displayName)

            // Exact stem match
            val exactMatch = jpegs.firstOrNull { jpeg ->
                !matchedJpegs.contains(jpeg.id) && extractStem(jpeg.displayName) == rawStem
            }

            if (exactMatch != null) {
                pairs.add(LineagePair(masterRaw = raw, derivativeJpeg = exactMatch, confidence = 1.00))
                matchedJpegs.add(exactMatch.id)
                continue
            }

            // Timestamp proximity match (within 2 seconds)
            val timeMatch = jpegs.firstOrNull { jpeg ->
                !matchedJpegs.contains(jpeg.id) && abs(jpeg.dateTakenUnix - raw.dateTakenUnix) <= 2
            }

            if (timeMatch != null) {
                pairs.add(LineagePair(masterRaw = raw, derivativeJpeg = timeMatch, confidence = 0.95))
                matchedJpegs.add(timeMatch.id)
            }
        }

        return pairs
    }

    /**
     * Enqueues deterministic Confidence-1.00 lineage edges for detected pairs.
     */
    fun registerPairLineage(pairs: List<LineagePair>): Int {
        var count = 0
        for (pair in pairs) {
            NativeBridge.enqueueLineageEvent(
                parentUuid = pair.masterRaw.contentUri,
                childUuid = pair.derivativeJpeg.contentUri,
                relationshipType = "DERIVED_FROM",
                resolver = pair.resolver,
                confidence = pair.confidence
            )
            count++
        }
        return count
    }

    private fun extractStem(filename: String): String {
        val dotIdx = filename.lastIndexOf('.')
        return if (dotIdx != -1) filename.substring(0, dotIdx) else filename
    }
}
