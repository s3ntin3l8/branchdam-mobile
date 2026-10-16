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

    /**
     * How much of the file head is searched for the XMP packet. The XMP
     * APP1 segment can follow an EXIF segment carrying a thumbnail, so this
     * is well past the 64 KiB of a single APP segment.
     */
    private const val XMP_SCAN_BYTES = 256 * 1024

    /** `GCamera:MicroVideo="1"` (legacy) or `Camera:MotionPhoto="1"` (format 1.0), attribute or element form. */
    private val MOTION_FLAG = Regex(
        """\b(?:GCamera|Camera):(?:MotionPhoto|MicroVideo)\s*(?:=\s*["']1["']|>\s*1\s*<)""",
    )

    /** Start of a `Container:Item` in a Motion Photo 1.0 directory. */
    private val ITEM_START = Regex("""<\w+:Item\b""")

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

    /**
     * Reads the XMP at the start of a JPEG/HEIF and reports whether it is a
     * Google Motion Photo and where its embedded video sits.
     *
     * Two formats are recognised:
     *  - **Legacy MicroVideo** (Pixel 2/3 era): `GCamera:MicroVideo="1"`
     *    plus `GCamera:MicroVideoOffset`, the video's length counted back
     *    from the end of the file.
     *  - **Motion Photo format 1.0** (current Pixels): `Camera:MotionPhoto="1"`
     *    (or `GCamera:`) plus a `Container:Directory` listing the file's
     *    media items in order, each with `Item:Semantic`, `Item:Length` and
     *    `Item:Padding`. The video is the item whose semantic is
     *    `MotionPhoto`; it starts where the lengths and paddings of it and
     *    every item after it, counted back from the end of the file, say.
     *    Items after the video (an Ultra HDR gain map, say) are allowed.
     *
     * When [fileLength] is unknown (<= 0) a recognised photo is reported
     * with `microVideoOffset = 0`, and the extractors refuse to cut it.
     */
    fun parseMotionPhotoStream(stream: InputStream, fileLength: Long): MotionPhotoInfo {
        val buffer = ByteArray(XMP_SCAN_BYTES)
        val bytesRead = readUpTo(stream, buffer)
        if (bytesRead <= 0) return MotionPhotoInfo(isMotionPhoto = false)

        val text = String(buffer, 0, bytesRead, Charsets.ISO_8859_1)

        if (!MOTION_FLAG.containsMatchIn(text)) return MotionPhotoInfo(isMotionPhoto = false)

        // Legacy MicroVideo trailer.
        val offset = extractOffset(text)
        if (offset > 0) {
            // Bounds check: when fileLength is known, the offset must be
            // smaller than the file (the trailer is a non-empty suffix).
            // When it is unknown, a positive offset is a plausible trailer.
            if (fileLength <= 0 || offset < fileLength) {
                val microVideoOffset = if (fileLength > 0) fileLength - offset else 0L
                return MotionPhotoInfo(
                    isMotionPhoto = true,
                    microVideoOffset = microVideoOffset,
                    microVideoLength = offset,
                )
            }
            return MotionPhotoInfo(isMotionPhoto = false)
        }

        // Motion Photo format 1.0 container directory.
        val video = locateDirectoryVideo(text) ?: return MotionPhotoInfo(isMotionPhoto = false)
        if (fileLength <= 0) {
            return MotionPhotoInfo(isMotionPhoto = true, microVideoOffset = 0L, microVideoLength = video.length)
        }
        val start = fileLength - video.bytesFromEnd
        if (start <= 0 || start + video.length > fileLength) {
            // The directory does not fit the file: refuse rather than cut
            // garbage out of it.
            return MotionPhotoInfo(isMotionPhoto = false)
        }
        return MotionPhotoInfo(isMotionPhoto = true, microVideoOffset = start, microVideoLength = video.length)
    }

    /** The video item of a Motion Photo 1.0 directory, located from the end of the file. */
    internal data class DirectoryVideo(val length: Long, val bytesFromEnd: Long)

    private data class ContainerItem(val semantic: String, val mime: String, val length: Long, val padding: Long)

    /**
     * Finds the video item in a `Container:Directory` and how far from the
     * end of the file it starts. Null when there is no directory, no video
     * item, or a length is missing or not positive.
     */
    internal fun locateDirectoryVideo(xmp: String): DirectoryVideo? {
        val starts = ITEM_START.findAll(xmp).map { it.range.first }.toList()
        if (starts.isEmpty()) return null
        val items = starts.mapIndexed { i, from ->
            val to = if (i + 1 < starts.size) starts[i + 1] else xmp.length
            val segment = xmp.substring(from, to).substringBefore("Directory>")
            ContainerItem(
                semantic = itemField(segment, "Semantic").orEmpty(),
                mime = itemField(segment, "Mime").orEmpty(),
                length = itemField(segment, "Length")?.toLongOrNull() ?: 0L,
                padding = itemField(segment, "Padding")?.toLongOrNull() ?: 0L,
            )
        }
        val index = items.indexOfFirst { it.semantic.equals("MotionPhoto", ignoreCase = true) }
            .takeIf { it >= 0 }
            ?: items.indexOfFirst { it.mime.startsWith("video/", ignoreCase = true) }.takeIf { it >= 0 }
            ?: return null
        val video = items[index]
        if (video.length <= 0) return null
        var fromEnd = 0L
        for (item in items.subList(index, items.size)) {
            if (item.length <= 0 || item.padding < 0) return null
            fromEnd += item.length + item.padding
        }
        return DirectoryVideo(length = video.length, bytesFromEnd = fromEnd)
    }

    /** Reads `Item:<name>` as an attribute or as a child element. */
    private fun itemField(segment: String, name: String): String? {
        Regex("""\b\w+:$name\s*=\s*["']([^"']*)["']""").find(segment)?.let { return it.groupValues[1].trim() }
        Regex("""<\w+:$name>([^<]*)<""").find(segment)?.let { return it.groupValues[1].trim() }
        return null
    }

    /** Fills [buffer] from [stream] until it is full or the stream ends. */
    private fun readUpTo(stream: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = stream.read(buffer, total, buffer.size - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun extractOffset(xmpText: String): Long {
        val pattern = Regex("GCamera:MicroVideoOffset\\s*=\\s*[\"'](\\d+)[\"']")
        val match = pattern.find(xmpText)
        return match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }

    /** Copies [length] bytes of [input], starting [offset] bytes in, to [outputFile]. */
    private fun copyRange(input: InputStream, offset: Long, length: Long, outputFile: File): Boolean {
        var toSkip = offset
        while (toSkip > 0) {
            val skipped = input.skip(toSkip)
            if (skipped <= 0) {
                if (input.read() < 0) return false
                toSkip--
            } else {
                toSkip -= skipped
            }
        }
        outputFile.parentFile?.mkdirs()
        var remaining = length
        outputFile.outputStream().use { output ->
            val buf = ByteArray(64 * 1024)
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                output.write(buf, 0, n)
                remaining -= n
            }
        }
        return remaining == 0L && outputFile.length() > 0
    }

    fun extractMicroVideo(file: File, outputFile: File): Boolean {
        val info = detectMotionPhoto(file)
        if (!info.isMotionPhoto || info.microVideoOffset <= 0) return false
        return try {
            file.inputStream().use { input ->
                copyRange(input, info.microVideoOffset, info.microVideoLength, outputFile)
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractMicroVideo failed for ${file.absolutePath}", e)
            false
        }
    }

    fun extractMicroVideo(context: Context, uri: Uri, outputFile: File): Boolean {
        val info = detectMotionPhoto(context, uri)
        if (!info.isMotionPhoto || info.microVideoOffset <= 0) return false
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                copyRange(input, info.microVideoOffset, info.microVideoLength, outputFile)
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "extractMicroVideo failed for $uri", e)
            false
        }
    }
}
