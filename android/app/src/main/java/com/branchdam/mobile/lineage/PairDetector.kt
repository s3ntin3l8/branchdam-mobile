package com.branchdam.mobile.lineage

import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.observer.MediaItem
import kotlin.math.abs

data class LineagePair(
    val masterRaw: MediaItem,
    val derivativeJpeg: MediaItem,
    val confidence: Double = 1.00,
    val resolver: String = "android_camera_pair"
)

object PairDetector {

    private val STEM_SUFFIX_REGEX = Regex(
        """[\._]?(RAW(-\d+)?|ORIGINAL|COVER|MP|ACTION|PORTRAIT|NIGHT|BURST\d*)+""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Finds companion RAW (DNG) and JPEG pairs from recent media items.
     * Google Pixel and Android flagships shoot companion DNG and JPEG files sharing
     * the timestamp stem (e.g., PXL_20260829_051500.dng & PXL_20260829_051500.jpg)
     * or created within 2 seconds of each other.
     * Uses O(N) HashMap indexing for stem matches.
     */
    fun findPairs(items: List<MediaItem>): List<LineagePair> {
        val raws = items.filter { it.isDng || it.isRaw }
        val jpegs = items.filter { !it.isDng && !it.isRaw && !it.isVideo }

        if (raws.isEmpty() || jpegs.isEmpty()) return emptyList()

        // Index JPEGs by computed stem for O(1) hash map lookup
        val jpegsByStem = HashMap<String, MutableList<MediaItem>>(jpegs.size)
        for (jpeg in jpegs) {
            val stem = extractStem(jpeg.displayName)
            jpegsByStem.getOrPut(stem) { mutableListOf() }.add(jpeg)
        }

        val pairs = mutableListOf<LineagePair>()
        val matchedJpegIds = HashSet<Long>(raws.size)

        val unmatchedRaws = mutableListOf<MediaItem>()

        for (raw in raws) {
            val rawStem = extractStem(raw.displayName)
            val candidates = jpegsByStem[rawStem]
            val exactMatch = candidates?.firstOrNull { !matchedJpegIds.contains(it.id) }

            if (exactMatch != null) {
                pairs.add(LineagePair(masterRaw = raw, derivativeJpeg = exactMatch, confidence = 1.00))
                matchedJpegIds.add(exactMatch.id)
            } else {
                unmatchedRaws.add(raw)
            }
        }

        // Timestamp proximity match for remaining unmatched RAWs (within 2 seconds)
        if (unmatchedRaws.isNotEmpty()) {
            val remainingJpegs = jpegs.filter { !matchedJpegIds.contains(it.id) }
            for (raw in unmatchedRaws) {
                val timeMatch = remainingJpegs.firstOrNull { jpeg ->
                    !matchedJpegIds.contains(jpeg.id) && abs(jpeg.dateTakenUnix - raw.dateTakenUnix) <= 2
                }
                if (timeMatch != null) {
                    pairs.add(LineagePair(masterRaw = raw, derivativeJpeg = timeMatch, confidence = 0.95))
                    matchedJpegIds.add(timeMatch.id)
                }
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
            EngineHolder.enqueueLineageEvent(
                parentLocalID = pair.masterRaw.contentUri,
                childLocalID = pair.derivativeJpeg.contentUri,
                relationshipType = "DERIVED_FROM",
                resolver = pair.resolver,
                confidence = pair.confidence
            )
            count++
        }
        return count
    }

    internal fun extractStem(filename: String): String {
        val stem = if (filename.contains('.')) filename.substringBeforeLast('.') else filename
        return stem.replace(STEM_SUFFIX_REGEX, "").trim()
    }
}
