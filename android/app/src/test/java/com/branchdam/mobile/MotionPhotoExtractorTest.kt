package com.branchdam.mobile

import com.branchdam.mobile.lineage.MotionPhotoExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Regression coverage for [MotionPhotoExtractor]'s pure parser.
 *
 * The pre-PR #132 shape interpolated the limit / bounds check
 * (or in the Uri overload, passed `Long.MAX_VALUE` as the file
 * length) which silently let malformed XMP blocks be classified
 * as motion photos with junk `microVideoOffset` /
 * `microVideoLength` values. The fix splits the verdict on three
 * independent checks:
 *  1. `offset > 0` — the XMP block must declare a non-empty
 *     trailer.
 *  2. `fileLength <= 0 || offset < fileLength` — when the file
 *     size is known, the offset must be a non-empty suffix.
 *  3. The `isMotionPhoto = true` verdict must NOT fire when
 *     either check fails.
 *
 * The Uri overload (`detectMotionPhoto(Context, Uri)`) is a thin
 * wrapper that calls [MotionPhotoExtractor.querySize] and then
 * [MotionPhotoExtractor.parseMotionPhotoStream]; the wrapper is
 * exercised by the Robolectric integration tests in PR #133's
 * settings-connection-refresh branch. This file pins the pure
 * parser because that is the surface that contains the bug.
 */
class MotionPhotoExtractorTest {

    private fun xmpWithOffset(offset: Long, motion: Boolean = true): String {
        val motionTag = if (motion) " GCamera:MotionPhoto=\"1\"" else ""
        return "http://ns.google.com/photos/1.0/camera/$motionTag GCamera:MicroVideoOffset=\"$offset\" padding"
    }

    @Test
    fun testParseMotionPhotoStream_happyPath() {
        val stream = ByteArrayInputStream(xmpWithOffset(150_000L).toByteArray(Charsets.ISO_8859_1))

        val info = MotionPhotoExtractor.parseMotionPhotoStream(stream, fileLength = 500_000L)
        assertTrue(info.isMotionPhoto)
        assertEquals(150_000L, info.microVideoLength)
        assertEquals(350_000L, info.microVideoOffset)
    }

    @Test
    fun testParseMotionPhotoStream_nonMotionPhoto() {
        val plainJpeg = "standard jpeg image without xmp metadata tags"
        val stream = ByteArrayInputStream(plainJpeg.toByteArray(Charsets.ISO_8859_1))

        val info = MotionPhotoExtractor.parseMotionPhotoStream(stream, fileLength = 50_000L)
        assertFalse(info.isMotionPhoto)
    }

    @Test
    fun testParseMotionPhotoStream_offsetLargerThanFileIsNotClassified() {
        // The pre-PR #132 shape had `Long.MAX_VALUE` as the file
        // length, so the `offset < fileLength` half of the bounds
        // check always passed. Pin the post-fix behaviour: a
        // malformed XMP block with an offset larger than the file
        // must NOT be classified as a motion photo.
        val stream = ByteArrayInputStream(xmpWithOffset(1_000_000L).toByteArray(Charsets.ISO_8859_1))

        val info = MotionPhotoExtractor.parseMotionPhotoStream(stream, fileLength = 500_000L)
        assertFalse(
            "offset >= fileLength must not be classified as a motion photo",
            info.isMotionPhoto,
        )
    }

    @Test
    fun testParseMotionPhotoStream_offsetZeroIsNotClassified() {
        // The XMP block declares `MotionPhoto="1"` but the offset
        // is missing or zero — the original code returned
        // `isMotionPhoto = true` with zero `microVideoOffset` /
        // `microVideoLength`. The post-fix refuses to classify.
        val xmp = "http://ns.google.com/photos/1.0/camera/ GCamera:MotionPhoto=\"1\" padding"
        val stream = ByteArrayInputStream(xmp.toByteArray(Charsets.ISO_8859_1))

        val info = MotionPhotoExtractor.parseMotionPhotoStream(stream, fileLength = 500_000L)
        assertFalse(
            "a missing or zero offset must not be classified as a motion photo",
            info.isMotionPhoto,
        )
    }

    @Test
    fun testParseMotionPhotoStream_sizeUnknownButOffsetPositiveStillClassifies() {
        // The Uri overload's querySize can return -1L (e.g. the
        // OpenableColumns query is not supported). The fix must
        // still classify the JPEG as a motion photo when the
        // offset is positive — `offset > 0` is the load-bearing
        // half of the gate when the size is unknown.
        val stream = ByteArrayInputStream(xmpWithOffset(123_456L).toByteArray(Charsets.ISO_8859_1))

        val info = MotionPhotoExtractor.parseMotionPhotoStream(stream, fileLength = -1L)
        assertTrue(
            "with size unknown, a positive offset must still classify the JPEG as a motion photo",
            info.isMotionPhoto,
        )
        assertEquals(123_456L, info.microVideoLength)
        assertEquals(0L, info.microVideoOffset)
    }
}
