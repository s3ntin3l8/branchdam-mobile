package com.branchdam.mobile

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.MediaStore
import com.branchdam.mobile.observer.MediaItem
import com.branchdam.mobile.observer.UploadPath
import com.branchdam.mobile.observer.UploadSource
import com.branchdam.mobile.observer.UploadStaging
import com.branchdam.mobile.observer.chooseUploadSource
import com.branchdam.mobile.observer.legacyDataPath
import com.branchdam.mobile.observer.redactedDataPathFallback
import com.branchdam.mobile.observer.stagedUploadIsOriginal
import com.branchdam.mobile.triage.LocalFileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Tests for how uploads read camera-roll media. On API 29+ the DATA path
 * and plain content URI serve location-redacted bytes, so the original is
 * staged via setRequireOriginal when ACCESS_MEDIA_LOCATION is granted, and
 * the staged copy is deleted once its upload COMPLETED.
 *
 * A copy that is not the original proves nothing about the original's
 * bytes, so it must not carry a reclaim baseline (#303): the engine would
 * otherwise archive the stripped copy, see every reclaim gate pass, and
 * delete an original whose GPS metadata was never archived.
 *
 * Runs under `@RunWith(RobolectricTestRunner::class)` so `Uri.parse` is
 * real (the default-value stubs of the rest of the suite return null for
 * every static method).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadStagingTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun legacyStorageReadsTheDataPath() {
        assertEquals(UploadSource.DATA_PATH, chooseUploadSource(28, hasMediaLocation = false))
        assertEquals(UploadSource.DATA_PATH, chooseUploadSource(28, hasMediaLocation = true))
    }

    @Test
    fun scopedStorageStagesTheOriginalWhenAllowed() {
        assertEquals(UploadSource.STAGE_ORIGINAL, chooseUploadSource(29, hasMediaLocation = true))
        assertEquals(UploadSource.STAGE_ORIGINAL, chooseUploadSource(35, hasMediaLocation = true))
    }

    @Test
    fun scopedStorageWithoutLocationPermissionStagesRedactedBytes() {
        assertEquals(UploadSource.STAGE_REDACTED, chooseUploadSource(29, hasMediaLocation = false))
        assertEquals(UploadSource.STAGE_REDACTED, chooseUploadSource(34, hasMediaLocation = false))
    }

    @Test
    fun stagedFileNameIsUniquePerRowAndKeepsTheExtension() {
        val item = MediaItem(
            id = 42L,
            contentUri = "content://media/external/images/media/42",
            filePath = "",
            displayName = "PXL 2026/10 #1.dng",
            mimeType = "image/x-adobe-dng",
            sizeBytes = 1L,
            dateTakenUnix = 0L,
            isRaw = true,
        )
        val name = UploadStaging.stagedFileName(item)
        assertEquals("42_PXL_2026_10__1.dng", name)
        assertFalse(name.contains('/'))
    }

    @Test
    fun completedUploadsAreCleanedUpImmediately() {
        val now = 10_000_000L
        assertTrue(UploadStaging.shouldDeleteStaged("1_a.jpg", "COMPLETED", now, now))
        assertTrue(UploadStaging.shouldDeleteStaged("1_a.jpg", "OFFLOADED", now, now))
    }

    @Test
    fun copiesStillNeededForUploadAreKept() {
        val ancient = 0L
        val now = 100L * UploadStaging.ORPHAN_GRACE_MS
        for (status in listOf("PENDING", "IN_PROGRESS", "FAILED")) {
            assertFalse(status, UploadStaging.shouldDeleteStaged("1_a.jpg", status, ancient, now))
        }
    }

    @Test
    fun unreferencedCopiesGetAGracePeriod() {
        val now = 10L * UploadStaging.ORPHAN_GRACE_MS
        val fresh = now - 1_000L
        val stale = now - UploadStaging.ORPHAN_GRACE_MS - 1L
        assertFalse(UploadStaging.shouldDeleteStaged("1_a.jpg", null, fresh, now))
        assertTrue(UploadStaging.shouldDeleteStaged("1_a.jpg", null, stale, now))
        assertTrue(UploadStaging.shouldDeleteStaged("1_a.jpg", "NOT_ENQUEUED", stale, now))
    }

    @Test
    fun interruptedCopiesAreRemovedAfterTheirGrace() {
        val now = 10L * UploadStaging.PART_GRACE_MS
        assertFalse(UploadStaging.shouldDeleteStaged("1_a.jpg.part", null, now - 1_000L, now))
        assertTrue(
            UploadStaging.shouldDeleteStaged("1_a.jpg.part", "PENDING", now - UploadStaging.PART_GRACE_MS - 1L, now),
        )
    }

    // --- which bytes an upload path holds (#303) ---

    @Test
    fun aRedactedStageIsNeverTheOriginal() {
        // STAGE_REDACTED: staged without ACCESS_MEDIA_LOCATION.
        assertFalse(stagedUploadIsOriginal(requireOriginal = false, originalRead = false))
    }

    @Test
    fun aRefusedOriginalIsNeverTheOriginal() {
        // The platform refused setRequireOriginal, so the staged copy is
        // the redacted fallback.
        assertFalse(stagedUploadIsOriginal(requireOriginal = true, originalRead = false))
    }

    @Test
    fun aSuccessfulOriginalStageIsTheOriginal() {
        assertTrue(stagedUploadIsOriginal(requireOriginal = true, originalRead = true))
    }

    @Test
    fun theLegacyDataPathIsTheOriginal() {
        // API < 29 redacts nothing, so the DATA path is the original.
        val path = legacyDataPath("/storage/emulated/0/DCIM/Camera/IMG_0001.jpg")
        assertNotNull(path)
        assertTrue(path!!.isOriginal)
        assertNull("an empty DATA path is not readable", legacyDataPath(""))
    }

    @Test
    fun theDataPathFallbackIsNeverTheOriginal() {
        // API 30+ fallback after staging failed: FUSE reads it, but the
        // location metadata is redacted.
        val fallback = redactedDataPathFallback(sdkInt = 30, filePath = "/storage/emulated/0/DCIM/Camera/IMG_0001.jpg")
        assertNotNull(fallback)
        assertFalse(fallback!!.isOriginal)
        assertNull(
            "below API 30 the DATA path is not readable at all",
            redactedDataPathFallback(sdkInt = 29, filePath = "/storage/emulated/0/DCIM/Camera/IMG_0001.jpg"),
        )
    }

    @Test
    fun aStagedOriginalIsReportedAsTheOriginal() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val context = stagingContext(bytes)
        val staged = UploadStaging.stage(context, testItem(), requireOriginal = true, openOriginal = { ByteArrayInputStream(bytes) })

        assertNotNull(staged)
        assertTrue("a successful setRequireOriginal open is the original", staged!!.isOriginal)
        assertEquals(bytes.size.toLong(), staged.file.length())
    }

    @Test
    fun aRefusedOriginalStagesTheRedactedCopy() {
        val bytes = byteArrayOf(5, 6, 7)
        val context = stagingContext(bytes)
        val staged = UploadStaging.stage(
            context,
            testItem(),
            requireOriginal = true,
            openOriginal = { throw SecurityException("ACCESS_MEDIA_LOCATION revoked") },
        )

        assertNotNull(staged)
        assertFalse("the refused original fell back to redacted bytes", staged!!.isOriginal)
        assertEquals(bytes.size.toLong(), staged.file.length())
    }

    @Test
    fun aRedactedStageIsReportedAsNotTheOriginal() {
        val bytes = byteArrayOf(8, 9)
        val context = stagingContext(bytes)
        val staged = UploadStaging.stage(context, testItem(), requireOriginal = false)

        assertNotNull(staged)
        assertFalse(staged!!.isOriginal)
    }

    @Test
    fun aCopyThatFailsMidwayLeavesNothingBehind() {
        val context = stagingContext(ByteArray(0))
        val failure = runCatching {
            UploadStaging.stage(context, testItem(), requireOriginal = true, openOriginal = { FailingInputStream() })
        }.exceptionOrNull()

        assertTrue("a failing copy must not be reported as staged", failure is IOException)
        val staged = UploadStaging.stagingDir(context).listFiles().orEmpty()
        assertTrue("the interrupted copy must be removed", staged.isEmpty())
    }

    @Test
    fun aProviderThatOpensNoStreamStagesNothing() {
        // The provider answered with no stream, so no bytes were read:
        // nothing may be staged, and the original can never be claimed.
        val context = stagingContext(ByteArray(0))

        val staged = UploadStaging.stage(context, testItem(), requireOriginal = true, openOriginal = { null })

        assertNull("no stream means nothing was read, so nothing is staged", staged)
    }

    @Test
    @Config(sdk = [30])
    fun aFailedCopyFallsBackToTheRedactedDataPath() {
        // The copy fails halfway, so the staged copy is discarded and the
        // API 30+ DATA path is used instead. FUSE reads it, but the
        // location metadata is redacted: the upload is not the original.
        val context = redactedUploadContext { FailingInputStream() }

        val upload = UploadStaging.pathForUpload(context, testItem())

        assertNotNull("the DATA path is readable on API 30+", upload)
        assertFalse("a failed copy is never the original", upload!!.isOriginal)
        assertEquals(testItem().filePath, upload.path)
    }

    @Test
    fun aRedactedStageIsNotTheOriginalThroughPathForUpload() {
        // API 34 without ACCESS_MEDIA_LOCATION: the staged copy holds the
        // redacted bytes, so pathForUpload must report it as such.
        val bytes = byteArrayOf(1, 2, 3)
        val context = redactedUploadContext { ByteArrayInputStream(bytes) }

        val upload = UploadStaging.pathForUpload(context, testItem())

        assertNotNull(upload)
        assertFalse("staged without ACCESS_MEDIA_LOCATION: not the original", upload!!.isOriginal)
        assertTrue(
            "the upload must point at the staged copy",
            upload.path.startsWith(UploadStaging.stagingDir(context).absolutePath),
        )
    }

    // --- what the engine is told about the item (#303) ---

    @Test
    fun anOriginalUploadRecordsTheMediaStoreBaseline() {
        val context = mediaStoreContext(sizeBytes = 4_096L, modifiedUnix = 1_700_000_000L)
        var captured: LocalFileState? = null

        val id = UploadStaging.enqueue(
            context = context,
            item = testItem(),
            resolvePath = { _, _ -> UploadPath("/data/user/0/app/upload_staging/1_a.jpg", isOriginal = true) },
            enqueueMedia = { _, _, _, _, baseline ->
                captured = baseline
                42L
            },
        )

        assertEquals(42L, id)
        assertEquals(
            "the baseline read from MediaStore must be recorded for an original upload",
            LocalFileState(4_096L, 1_700_000_000L),
            captured,
        )
    }

    @Test
    fun aRedactedUploadRecordsNoBaseline() {
        val context = mediaStoreContext(sizeBytes = 4_096L, modifiedUnix = 1_700_000_000L)
        var captured: LocalFileState? = null

        val id = UploadStaging.enqueue(
            context = context,
            item = testItem(),
            resolvePath = { _, _ -> UploadPath("/data/user/0/app/upload_staging/1_a.jpg", isOriginal = false) },
            enqueueMedia = { _, _, _, _, baseline ->
                captured = baseline
                42L
            },
        )

        assertEquals(42L, id)
        assertEquals(
            "a redacted copy proves nothing about the original: no baseline",
            LocalFileState.UNKNOWN,
            captured,
        )
    }

    @Test
    fun theBaselineIsReadBeforeTheBytesAreCopied() {
        var baselineRead = false
        val context = mediaStoreContext(sizeBytes = 4_096L, modifiedUnix = 1_700_000_000L) {
            baselineRead = true
        }

        UploadStaging.enqueue(
            context = context,
            item = testItem(),
            resolvePath = { _, _ ->
                assertTrue("the baseline must be read before the bytes are copied (#229)", baselineRead)
                UploadPath("/data/user/0/app/upload_staging/1_a.jpg", isOriginal = true)
            },
            enqueueMedia = { _, _, _, _, _ -> 7L },
        )
    }

    @Test
    fun unreferencedCopiesAreKeptWhenTheEngineCannotAnswer() {
        val now = 10L * UploadStaging.ORPHAN_GRACE_MS
        val stale = now - UploadStaging.ORPHAN_GRACE_MS - 1L
        // An empty status map is what a failed or uninitialised engine
        // returns: the status of the copy is unknown, not "no row".
        assertFalse(
            "an unknown status must not cost the only copy of a pending upload",
            UploadStaging.shouldDeleteStaged("1_a.jpg", null, stale, now, engineAnswered = false),
        )
        assertFalse(
            UploadStaging.shouldDeleteStaged("1_a.jpg", "NOT_ENQUEUED", stale, now, engineAnswered = false),
        )
    }

    @Test
    fun stagedCopiesSurviveACleanupPassWithoutAnEngineStatus() {
        val context = stagingContext(ByteArray(0))
        val item = imageItem(id = 42L)
        val staged = UploadStaging.microVideoFile(context, item)
        staged.parentFile?.mkdirs()
        staged.writeText("video")
        // Older than the orphan grace: only the grace would remove it.
        staged.setLastModified(System.currentTimeMillis() - UploadStaging.ORPHAN_GRACE_MS - 60_000L)

        val removed = UploadStaging.cleanup(context, statuses = emptyMap())

        assertEquals("no status means no safe answer: nothing may be reaped", 0, removed)
        assertTrue(
            "the staged micro video must survive an engine outage: it may be the only copy",
            staged.exists(),
        )
    }

    @Test
    fun microVideoIsStagedInsideTheStagingDir() {
        val context = stagingContext(ByteArray(0))
        val item = imageItem(id = 42L)

        val file = UploadStaging.microVideoFile(context, item)

        assertEquals(
            "the micro video must live in the staging dir, so the cleanup sweep finds it",
            UploadStaging.stagingDir(context).absolutePath,
            file.parentFile?.absolutePath,
        )
        assertEquals("42_IMG_0001.jpg_microvideo.mp4", file.name)
    }

    @Test
    fun microVideoNamesAreUniquePerMediaStoreRow() {
        val context = stagingContext(ByteArray(0))
        // Same display name, different rows: the old per-stem file name
        // made the second photo overwrite the first one's video.
        val first = imageItem(id = 1L, displayName = "IMG_0001.jpg")
        val second = imageItem(id = 2L, displayName = "IMG_0001.jpg")

        assertNotEquals(
            "two rows with the same display name must not share a micro video file",
            UploadStaging.microVideoFile(context, first).name,
            UploadStaging.microVideoFile(context, second).name,
        )
    }

    @Test
    fun microVideoNameDiffersFromTheStagedCopyOfTheSameItem() {
        val context = stagingContext(ByteArray(0))
        val item = imageItem(id = 42L)

        assertNotEquals(
            "the micro video must not overwrite the staged copy of the photo itself",
            UploadStaging.stagedFileName(item),
            UploadStaging.microVideoFile(context, item).name,
        )
    }

    @Test
    fun microVideoIsWrittenThroughAPartFile() {
        val context = stagingContext(ByteArray(0))
        val item = imageItem(id = 42L)

        val part = UploadStaging.microVideoPartFile(context, item)

        assertEquals(UploadStaging.stagingDir(context).absolutePath, part.parentFile?.absolutePath)
        assertEquals(
            "a crash mid-extraction must leave only a .part the cleanup reaps",
            UploadStaging.microVideoFile(context, item).name + ".part",
            part.name,
        )
    }

    @Test
    fun stagedMicroVideoIsRemovedOnceItsUploadCompleted() {
        val context = stagingContext(ByteArray(0))
        val item = imageItem(id = 42L)
        val staged = UploadStaging.microVideoFile(context, item)
        staged.parentFile?.mkdirs()
        staged.writeText("video")

        val removed = UploadStaging.cleanup(context, statuses = mapOf(staged.absolutePath to "COMPLETED"))

        assertEquals(1, removed)
        assertFalse("a completed micro video must not stay in the staging dir", staged.exists())
    }

    // --- helpers ---

    /**
     * A camera-roll photo: the still a motion photo is split from. The
     * micro video is staged next to the still's staged copy, under the
     * same row id.
     */
    private fun imageItem(id: Long, displayName: String = "IMG_0001.jpg") = MediaItem(
        id = id,
        contentUri = "content://media/external/images/media/$id",
        filePath = "",
        displayName = displayName,
        mimeType = "image/jpeg",
        sizeBytes = 1L,
        dateTakenUnix = 0L,
        isRaw = false,
    )

    private fun testItem(): MediaItem = MediaItem(
        id = 1L,
        contentUri = "content://media/external/images/media/1",
        filePath = "/storage/emulated/0/DCIM/Camera/IMG_0001.jpg",
        displayName = "IMG_0001.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 4_096L,
        dateTakenUnix = 1_700_000_000L,
        isRaw = false,
    )

    /** A context whose MediaStore rows report [sizeBytes] / [modifiedUnix]. */
    private fun mediaStoreContext(
        sizeBytes: Long,
        modifiedUnix: Long,
        onQuery: () -> Unit = {},
    ): Context {
        val cursor = mock<Cursor>()
        whenever(cursor.moveToFirst()).thenReturn(true)
        whenever(cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)).thenReturn(0)
        whenever(cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)).thenReturn(1)
        whenever(cursor.isNull(0)).thenReturn(false)
        whenever(cursor.isNull(1)).thenReturn(false)
        whenever(cursor.getLong(0)).thenReturn(sizeBytes)
        whenever(cursor.getLong(1)).thenReturn(modifiedUnix)

        val resolver = Mockito.mock(
            ContentResolver::class.java,
            Answer { invocation ->
                if (invocation.method.name == "query") {
                    onQuery()
                    cursor
                } else {
                    null
                }
            },
        )

        val context = mock<Context>()
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(context.noBackupFilesDir).thenReturn(temporaryFolder.newFolder("media-store"))
        return context
    }

    /** A context whose content resolver serves [bytes] (the redacted copy). */
    private fun stagingContext(bytes: ByteArray): Context {
        val resolver = Mockito.mock(
            ContentResolver::class.java,
            Answer { invocation ->
                if (invocation.method.name == "openInputStream") ByteArrayInputStream(bytes) else null
            },
        )

        val context = mock<Context>()
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(context.noBackupFilesDir).thenReturn(temporaryFolder.newFolder("staging"))
        // The OTG sweep derives its legacy root from filesDir; keep it
        // inside the test folder instead of a path relative to the
        // working directory.
        whenever(context.filesDir).thenReturn(temporaryFolder.newFolder("files"))
        return context
    }

    /**
     * A context whose content resolver serves a fresh stream from [stream]
     * on every open and whose ACCESS_MEDIA_LOCATION permission is denied,
     * so the upload source is STAGE_REDACTED.
     */
    private fun redactedUploadContext(stream: () -> InputStream?): Context {
        val resolver = Mockito.mock(
            ContentResolver::class.java,
            Answer { invocation ->
                if (invocation.method.name == "openInputStream") stream() else null
            },
        )

        val context = mock<Context>()
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(context.noBackupFilesDir).thenReturn(temporaryFolder.newFolder())
        whenever(context.checkPermission(anyString(), anyInt(), anyInt()))
            .thenReturn(PackageManager.PERMISSION_DENIED)
        return context
    }

    /** A stream that fails in the middle of the copy. */
    private class FailingInputStream : InputStream() {
        override fun read(): Int = throw IOException("read failed")

        override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("read failed")
    }
}
