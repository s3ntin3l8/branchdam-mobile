package com.branchdam.mobile

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import com.branchdam.mobile.observer.MediaItem
import com.branchdam.mobile.observer.MediaStoreObserver
import com.branchdam.mobile.observer.UploadStaging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The motion photo branch of [MediaStoreObserver]: the micro video
 * extracted from a motion photo is staged in the upload staging dir and
 * enqueued from there.
 *
 * It used to be extracted into `cacheDir/motion_photos` and enqueued
 * from there, but the engine reopens the enqueued path at sync time --
 * possibly long after the scan and after a process restart -- and the
 * OS may clear the cache dir in between, so the upload row failed and
 * the micro video was never archived.
 *
 * Runs under `@RunWith(RobolectricTestRunner::class)` because the branch
 * parses the item's content URI: without the shadow runtime
 * `Uri.parse` is a stub that returns null, so the detector could never
 * see the photo's bytes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStoreObserverMotionPhotoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * A JPEG carrying the legacy Google Motion Photo marker, with the
     * video payload as its trailing bytes.
     */
    private fun motionPhotoBytes(payload: ByteArray): ByteArray {
        val xmp = "http://ns.google.com/photos/1.0/camera/ GCamera:MicroVideo=\"1\" " +
            "GCamera:MicroVideoOffset=\"${payload.size}\" padding"
        return xmp.toByteArray(Charsets.ISO_8859_1) + ByteArray(2048) { 0x20 } + payload
    }

    /**
     * A context that serves [bytes] for [uri] the way the MediaStore
     * would -- the bytes for the extraction, the size for the bounds
     * check -- and has auto-import switched on.
     */
    private fun createMotionPhotoContext(uri: Uri, bytes: ByteArray): Context {
        val cursor: Cursor = mock()
        whenever(cursor.moveToFirst()).thenReturn(true)
        whenever(cursor.getLong(0)).thenReturn(bytes.size.toLong())

        val resolver: ContentResolver = mock()
        whenever(resolver.query(eq(uri), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(cursor)
        // A fresh stream per open: the detector and the extractor read
        // the same URI twice.
        whenever(resolver.openInputStream(eq(uri))).thenAnswer { ByteArrayInputStream(bytes) }

        val context: Context = mock()
        val prefs: SharedPreferences = mock()
        val editor: SharedPreferences.Editor = mock()
        whenever(prefs.getLong(BranchDamKeys.OBSERVER_LAST_SCANNED_TIMESTAMP, 0L)).thenReturn(0L)
        whenever(prefs.getBoolean(BranchDamKeys.AUTO_IMPORT_CAMERA_ROLL, false)).thenReturn(true)
        whenever(prefs.edit()).thenReturn(editor)
        whenever(editor.putLong(any(), any())).thenReturn(editor)
        whenever(context.getSharedPreferences(BranchDamKeys.PREFS_NAME, Context.MODE_PRIVATE)).thenReturn(prefs)
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(context.noBackupFilesDir).thenReturn(tempFolder.root)
        return context
    }

    private fun motionPhotoItem(uri: Uri, sizeBytes: Long) = MediaItem(
        id = 42L,
        contentUri = uri.toString(),
        filePath = "",
        displayName = "PXL_20260101_000000.jpg",
        mimeType = "image/jpeg",
        sizeBytes = sizeBytes,
        dateTakenUnix = 1_700_000_000L,
        isRaw = false,
    )

    /**
     * An observer with a stubbed handler: the real one starts a
     * HandlerThread the test has no use for.
     */
    private fun observer(context: Context, enqueue: (String) -> Long): MediaStoreObserver =
        MediaStoreObserver(
            context = context,
            handler = mock<Handler>(),
            enqueueMedia = { localPath, _, _, _ -> enqueue(localPath) },
        )

    @Test
    fun motionPhotoMicroVideoIsEnqueuedFromTheStagingDir() {
        val payload = "MICRO_VIDEO_BYTES".toByteArray(Charsets.ISO_8859_1)
        val bytes = motionPhotoBytes(payload)
        val uri = Uri.parse("content://media/external/images/media/42")
        val context = createMotionPhotoContext(uri, bytes)
        val item = motionPhotoItem(uri, bytes.size.toLong())

        val enqueued = mutableListOf<String>()
        observer(context) { path ->
            enqueued += path
            1L
        }.enqueueMotionPhotoMicroVideos(listOf(item))

        assertEquals("the micro video must be enqueued exactly once", 1, enqueued.size)
        val stagedDir = UploadStaging.stagingDir(context)
        assertTrue(
            "the micro video must be enqueued from the staging dir, not from the cache dir: ${enqueued[0]}",
            enqueued[0].startsWith(stagedDir.absolutePath + File.separator),
        )
        assertEquals(
            "the enqueued path must be the staged micro video of this row",
            UploadStaging.microVideoFile(context, item).absolutePath,
            enqueued[0],
        )
        assertEquals("MICRO_VIDEO_BYTES", File(enqueued[0]).readText(Charsets.ISO_8859_1))
        assertTrue(
            "the .part file must be renamed onto the staged copy",
            stagedDir.listFiles().orEmpty().none { it.name.endsWith(".part") },
        )
    }

    @Test
    fun aRefusedMicroVideoEnqueueDropsTheStagedCopy() {
        val payload = "MICRO_VIDEO_BYTES".toByteArray(Charsets.ISO_8859_1)
        val bytes = motionPhotoBytes(payload)
        val uri = Uri.parse("content://media/external/images/media/42")
        val context = createMotionPhotoContext(uri, bytes)
        val item = motionPhotoItem(uri, bytes.size.toLong())

        observer(context) { 0L }.enqueueMotionPhotoMicroVideos(listOf(item))

        assertTrue(
            "a refused enqueue must not leave the staged copy behind",
            UploadStaging.stagingDir(context).listFiles().orEmpty().isEmpty(),
        )
    }
}
