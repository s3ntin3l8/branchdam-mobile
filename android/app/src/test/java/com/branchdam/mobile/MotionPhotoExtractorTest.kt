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

    @Test
    fun testExtractMicroVideo_happyPath() {
        val tempDir = java.nio.file.Files.createTempDirectory("motion_photo_test").toFile()
        try {
            val motionFile = java.io.File(tempDir, "test_motion.jpg")
            val padding = ByteArray(2048) { 0x20 }
            val xmpBytes = xmpWithOffset(offset = 12L).toByteArray(Charsets.ISO_8859_1)
            val videoPayload = "VIDEO_STREAM".toByteArray(Charsets.ISO_8859_1)
            motionFile.writeBytes(xmpBytes + padding + videoPayload)

            val outputFile = java.io.File(tempDir, "extracted.mp4")
            val success = MotionPhotoExtractor.extractMicroVideo(motionFile, outputFile)
            assertTrue(success)
            assertTrue(outputFile.exists())
            assertEquals("VIDEO_STREAM", outputFile.readText(Charsets.ISO_8859_1))
        } finally {
            tempDir.deleteRecursively()
        }
    }
    // --- Motion Photo format 1.0 (current Pixels) ---------------------

    /**
     * The XMP a current Pixel writes: `Camera:MotionPhoto` plus a
     * `Container:Directory` of items in file order, no MicroVideoOffset.
     */
    private fun pixelXmp(vararg items: String): String = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about=""
            xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
            xmlns:Container="http://ns.google.com/photos/1.0/container/"
            xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
            Camera:MotionPhoto="1" Camera:MotionPhotoVersion="1"
            Camera:MotionPhotoPresentationTimestampUs="968133">
          <Container:Directory><rdf:Seq>
            ${items.joinToString("\n") { "<rdf:li rdf:parseType=\"Resource\">$it</rdf:li>" }}
          </rdf:Seq></Container:Directory>
        </rdf:Description></rdf:RDF></x:xmpmeta>
    """.trimIndent()

    private fun item(semantic: String, mime: String, length: Long, padding: Long = 0) =
        "<Container:Item Item:Mime=\"$mime\" Item:Semantic=\"$semantic\" Item:Length=\"$length\" Item:Padding=\"$padding\"/>"

    @Test
    fun testParseMotionPhotoStream_pixelContainerFormat() {
        val xmp = pixelXmp(
            item("Primary", "image/jpeg", 0),
            item("MotionPhoto", "video/mp4", 2_681_512),
        )
        val info = MotionPhotoExtractor.parseMotionPhotoStream(
            ByteArrayInputStream(xmp.toByteArray(Charsets.ISO_8859_1)), fileLength = 6_000_000L,
        )
        assertTrue("Camera:MotionPhoto + Container:Directory is a motion photo", info.isMotionPhoto)
        assertEquals(2_681_512L, info.microVideoLength)
        assertEquals(6_000_000L - 2_681_512L, info.microVideoOffset)
    }

    @Test
    fun testParseMotionPhotoStream_pixelContainerWithItemsAroundTheVideo() {
        // Ultra HDR motion photo: a gain map before the video, and a
        // padded item after it. The video starts where the lengths and
        // paddings from it to the end of the file say.
        val xmp = pixelXmp(
            item("Primary", "image/jpeg", 0),
            item("GainMap", "image/jpeg", 40_000),
            item("MotionPhoto", "video/mp4", 1_000_000, padding = 16),
            item("Depth", "image/jpeg", 5_000, padding = 4),
        )
        val info = MotionPhotoExtractor.parseMotionPhotoStream(
            ByteArrayInputStream(xmp.toByteArray(Charsets.ISO_8859_1)), fileLength = 3_000_000L,
        )
        assertTrue(info.isMotionPhoto)
        assertEquals(1_000_000L, info.microVideoLength)
        assertEquals(3_000_000L - (1_000_000L + 16 + 5_000 + 4), info.microVideoOffset)
    }

    @Test
    fun testParseMotionPhotoStream_elementFormFields() {
        val xmp = """
            <rdf:Description xmlns:Camera="http://ns.google.com/photos/1.0/camera/">
              <Camera:MotionPhoto>1</Camera:MotionPhoto>
              <Container:Directory><rdf:Seq>
                <rdf:li><Container:Item><Item:Semantic>Primary</Item:Semantic><Item:Mime>image/jpeg</Item:Mime></Container:Item></rdf:li>
                <rdf:li><Container:Item><Item:Semantic>MotionPhoto</Item:Semantic><Item:Mime>video/mp4</Item:Mime><Item:Length>4096</Item:Length></Container:Item></rdf:li>
              </rdf:Seq></Container:Directory>
            </rdf:Description>
        """.trimIndent()
        val info = MotionPhotoExtractor.parseMotionPhotoStream(
            ByteArrayInputStream(xmp.toByteArray(Charsets.ISO_8859_1)), fileLength = 100_000L,
        )
        assertTrue(info.isMotionPhoto)
        assertEquals(4096L, info.microVideoLength)
        assertEquals(100_000L - 4096L, info.microVideoOffset)
    }

    @Test
    fun testParseMotionPhotoStream_containerSizeUnknown() {
        val xmp = pixelXmp(item("Primary", "image/jpeg", 0), item("MotionPhoto", "video/mp4", 777))
        val info = MotionPhotoExtractor.parseMotionPhotoStream(
            ByteArrayInputStream(xmp.toByteArray(Charsets.ISO_8859_1)), fileLength = -1L,
        )
        assertTrue(info.isMotionPhoto)
        assertEquals(777L, info.microVideoLength)
        assertEquals("no offset without a file size: the extractors refuse to cut", 0L, info.microVideoOffset)
    }

    @Test
    fun testParseMotionPhotoStream_containerThatDoesNotFitIsRefused() {
        val tooLong = pixelXmp(item("Primary", "image/jpeg", 0), item("MotionPhoto", "video/mp4", 9_000_000))
        assertFalse(
            MotionPhotoExtractor.parseMotionPhotoStream(
                ByteArrayInputStream(tooLong.toByteArray(Charsets.ISO_8859_1)), fileLength = 500_000L,
            ).isMotionPhoto,
        )
        val noLength = pixelXmp(item("Primary", "image/jpeg", 0), item("MotionPhoto", "video/mp4", 0))
        assertFalse(
            MotionPhotoExtractor.parseMotionPhotoStream(
                ByteArrayInputStream(noLength.toByteArray(Charsets.ISO_8859_1)), fileLength = 500_000L,
            ).isMotionPhoto,
        )
        // A directory without the MotionPhoto flag (a plain Ultra HDR
        // JPEG with a gain map) is not a motion photo.
        val ultraHdrOnly = pixelXmp(item("Primary", "image/jpeg", 0), item("GainMap", "image/jpeg", 40_000))
            .replace("Camera:MotionPhoto=\"1\"", "")
        assertFalse(
            MotionPhotoExtractor.parseMotionPhotoStream(
                ByteArrayInputStream(ultraHdrOnly.toByteArray(Charsets.ISO_8859_1)), fileLength = 500_000L,
            ).isMotionPhoto,
        )
    }

    @Test
    fun testExtractMicroVideo_pixelContainerCopiesOnlyTheVideo() {
        val tempDir = java.nio.file.Files.createTempDirectory("motion_photo_v1").toFile()
        try {
            val video = "MP4_VIDEO_BYTES".toByteArray(Charsets.ISO_8859_1)
            val trailer = "DEPTH".toByteArray(Charsets.ISO_8859_1)
            val xmp = pixelXmp(
                item("Primary", "image/jpeg", 0),
                item("MotionPhoto", "video/mp4", video.size.toLong()),
                item("Depth", "image/jpeg", trailer.size.toLong()),
            ).toByteArray(Charsets.ISO_8859_1)
            val file = java.io.File(tempDir, "PXL_20260101_000000000.MP.jpg")
            file.writeBytes(xmp + ByteArray(2048) { 0x20 } + video + trailer)

            val out = java.io.File(tempDir, "out.mp4")
            assertTrue(MotionPhotoExtractor.extractMicroVideo(file, out))
            assertEquals("MP4_VIDEO_BYTES", out.readText(Charsets.ISO_8859_1))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
