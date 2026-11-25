package com.branchdam.mobile.observer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.otg.OtgStaging
import com.branchdam.mobile.triage.LocalFileState
import com.branchdam.mobile.triage.queryLocalFileState
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** Where the bytes the Go engine hashes and uploads come from. */
enum class UploadSource {
    /** API 28: legacy storage; the MediaStore DATA path is readable and unredacted. */
    DATA_PATH,

    /**
     * API 29+ with ACCESS_MEDIA_LOCATION: copy the original bytes
     * (`MediaStore.setRequireOriginal`) into the no-backup staging dir.
     */
    STAGE_ORIGINAL,

    /**
     * API 29+ without ACCESS_MEDIA_LOCATION: copy what the ContentResolver
     * serves (location redacted). Still staged because scoped storage
     * makes the DATA path unreadable on API 29.
     */
    STAGE_REDACTED,
}

/** Pure decision: how an item should be read for upload. */
fun chooseUploadSource(sdkInt: Int, hasMediaLocation: Boolean): UploadSource = when {
    sdkInt < 29 -> UploadSource.DATA_PATH
    hasMediaLocation -> UploadSource.STAGE_ORIGINAL
    else -> UploadSource.STAGE_REDACTED
}

/**
 * A path the Go engine can open for an item, and whether the bytes
 * behind it are the item's *original* content.
 *
 * A location-redacted copy is not the original: the server archives the
 * stripped bytes, so a hash match says nothing about the original and
 * the item must never be reclaimed (#303).
 */
data class UploadPath(val path: String, val isOriginal: Boolean)

/**
 * A staged copy of an item's bytes and whether it holds the item's
 * original bytes (#303).
 */
internal data class StagedFile(val file: File, val isOriginal: Boolean)

/**
 * Pure decision: does a staged copy hold the item's original bytes?
 *
 * [requireOriginal] is true when the staging asked for the unredacted
 * original (`MediaStore.setRequireOriginal`), and [originalRead] is true
 * only when that open actually succeeded. A refused original (the
 * permission was revoked between the check and the open) falls back to
 * the redacted bytes, so the copy is not the original.
 */
internal fun stagedUploadIsOriginal(requireOriginal: Boolean, originalRead: Boolean): Boolean =
    requireOriginal && originalRead

/**
 * Pure decision: the legacy (API < 29) DATA path. Nothing redacts the
 * location metadata before API 29, so the path holds the original bytes.
 */
internal fun legacyDataPath(filePath: String): UploadPath? =
    filePath.takeIf { it.isNotEmpty() }?.let { UploadPath(it, isOriginal = true) }

/**
 * Pure decision: the fallback to the DATA path used when staging failed
 * on API 30+ (FUSE makes the path readable there). Its location metadata
 * is redacted, so it never holds the original bytes.
 */
internal fun redactedDataPathFallback(sdkInt: Int, filePath: String): UploadPath? =
    if (sdkInt >= Build.VERSION_CODES.R && filePath.isNotEmpty()) {
        UploadPath(filePath, isOriginal = false)
    } else {
        null
    }

/**
 * Produces a file path the Go engine can `os.Open` for a MediaStore item.
 *
 * Since API 29 a camera-roll file read through its DATA path (or a plain
 * content URI) has its GPS EXIF redacted, so the archived bytes and hashes
 * would differ from the original. The engine opens a path, both at enqueue
 * (hash) and later in `syncBatch` (upload), possibly after a process
 * restart -- so a `/proc/self/fd` path would not survive. Instead the
 * original bytes are copied into `noBackupFilesDir/upload_staging` and
 * that path is enqueued; [cleanup] deletes each copy once the engine
 * reports its upload COMPLETED.
 *
 * A copy that is not the original (a redacted stage, a refused original,
 * the DATA-path fallback) is reported as such: the reclaim baseline of
 * such an item must be dropped, because the archived bytes are not the
 * original's and the original may never be reclaimed (#303).
 */
object UploadStaging {
    private const val TAG = "UploadStaging"
    private const val DIR_NAME = "upload_staging"
    private const val PART_SUFFIX = ".part"

    /** Suffix of the staged micro video of a motion photo. */
    private const val MICRO_VIDEO_SUFFIX = "_microvideo.mp4"

    /** An interrupted copy is abandoned after this long. */
    internal const val PART_GRACE_MS = 60L * 60L * 1000L

    /**
     * A staged copy no upload row references (the engine deduplicated it
     * against an earlier upload, or enqueue failed) is removed after this
     * long. The grace covers the gap between staging and enqueue.
     */
    internal const val ORPHAN_GRACE_MS = 24L * 60L * 60L * 1000L

    fun stagingDir(context: Context): File = File(context.noBackupFilesDir, DIR_NAME)

    fun hasMediaLocationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Enqueues [item] for upload, reading its original bytes where the
     * platform allows. Returns the queue ID (> 0) or 0 on failure.
     *
     * The reclaim baseline is only recorded for an original upload: a
     * location-redacted copy proves nothing about the original's bytes,
     * so the item must never be reclaimed (#303).
     */
    internal fun enqueue(
        context: Context,
        item: MediaItem,
        resolvePath: (Context, MediaItem) -> UploadPath? = ::pathForUpload,
        enqueueMedia: (
            localPath: String,
            filename: String,
            capturedAtUnix: Long,
            localId: String,
            baseline: LocalFileState,
        ) -> Long = EngineHolder::enqueueMedia,
    ): Long {
        // Read the reclaim baseline before the bytes are copied: a change
        // in between leaves it describing the older bytes, so the reclaim
        // sees a mismatch and keeps the item (fail-safe, #229).
        val baseline = queryLocalFileState(context, item.contentUri)
        val upload = resolvePath(context, item) ?: return 0L
        val recorded = if (upload.isOriginal) {
            baseline
        } else {
            Log.w(
                TAG,
                "uploading a location-redacted copy of ${item.contentUri}; " +
                    "the original was never archived, so the item cannot be reclaimed",
            )
            LocalFileState.UNKNOWN
        }
        val id = enqueueMedia(
            upload.path,
            item.displayName,
            item.dateTakenUnix,
            item.contentUri,
            recorded,
        )
        if (id <= 0L) discardIfStaged(context, upload.path)
        return id
    }

    /**
     * The path to enqueue for [item], or null if it cannot be read at all.
     * Falls back to the DATA path on API 30+ when staging fails (for
     * example, not enough free space for a large video): FUSE makes it
     * readable there, at the cost of redacted location -- so the fallback
     * is reported as a non-original copy (#303).
     */
    fun pathForUpload(context: Context, item: MediaItem): UploadPath? {
        val source = chooseUploadSource(Build.VERSION.SDK_INT, hasMediaLocationPermission(context))
        if (source == UploadSource.DATA_PATH) return legacyDataPath(item.filePath)

        val requireOriginal = source == UploadSource.STAGE_ORIGINAL
        val staged = try {
            stage(context, item, requireOriginal = requireOriginal)
        } catch (e: Exception) {
            Log.w(TAG, "staging ${item.contentUri} failed", e)
            null
        }
        if (staged != null) {
            return UploadPath(staged.file.absolutePath, isOriginal = staged.isOriginal)
        }

        val fallback = redactedDataPathFallback(Build.VERSION.SDK_INT, item.filePath)
        if (fallback != null) {
            Log.w(TAG, "falling back to the redacted DATA path for ${item.contentUri}")
            return fallback
        }
        return null
    }

    /** Staged file name: unique per MediaStore row, keeps the extension. */
    internal fun stagedFileName(item: MediaItem): String {
        val safeName = item.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        return "${item.id}_$safeName"
    }

    /**
     * Where the micro video of the motion photo [item] is staged: in the
     * staging dir, so [cleanup] removes it once the engine reports the
     * upload COMPLETED/OFFLOADED (or after the orphan grace period), and
     * named after [stagedFileName], so two photos with the same display
     * name in different folders never overwrite each other's video.
     */
    fun microVideoFile(context: Context, item: MediaItem): File =
        File(stagingDir(context), stagedFileName(item) + MICRO_VIDEO_SUFFIX)

    /**
     * The `.part` file the micro video is written to before it is
     * renamed onto [microVideoFile], so a crash mid-extraction leaves
     * only a `.part` that [cleanup] already reaps.
     */
    fun microVideoPartFile(context: Context, item: MediaItem): File =
        partFileOf(microVideoFile(context, item))

    /** The `.part` sibling of [target]: a copy is written there first, then renamed onto [target]. */
    private fun partFileOf(target: File): File = File(target.parentFile, target.name + PART_SUFFIX)

    /**
     * Copies [item]'s bytes into the staging dir. Returns the staged file
     * and whether it holds the item's original bytes: only a successful
     * `setRequireOriginal` open does (#303).
     *
     * [openOriginal] opens the unredacted original and is a seam for the
     * tests; it throws when the platform refuses the original.
     */
    internal fun stage(
        context: Context,
        item: MediaItem,
        requireOriginal: Boolean,
        openOriginal: (Uri) -> InputStream? = { uri ->
            val readUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.setRequireOriginal(uri)
            } else {
                uri
            }
            context.contentResolver.openInputStream(readUri)
        },
    ): StagedFile? {
        val dir = stagingDir(context)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("cannot create $dir")
        val target = File(dir, stagedFileName(item))
        val part = partFileOf(target)

        val uri = Uri.parse(item.contentUri)
        var originalRead = false
        val input = try {
            if (requireOriginal) {
                val stream = openOriginal(uri)
                // A provider that answers with no stream read no bytes:
                // the original was not read, so it cannot be reported as
                // the original.
                if (stream == null) return null
                originalRead = true
                stream
            } else {
                context.contentResolver.openInputStream(uri)
            }
        } catch (e: RuntimeException) {
            // MediaProvider refuses requireOriginal with a SecurityException
            // or UnsupportedOperationException when ACCESS_MEDIA_LOCATION was
            // revoked between the check and the open: take the redacted
            // bytes rather than nothing.
            if (!requireOriginal || (e !is SecurityException && e !is UnsupportedOperationException)) throw e
            Log.w(TAG, "original bytes denied for ${item.contentUri}; staging redacted copy", e)
            originalRead = false
            context.contentResolver.openInputStream(uri)
        } ?: return null

        try {
            input.use { src ->
                FileOutputStream(part).use { out ->
                    src.copyTo(out, COPY_BUFFER)
                    out.fd.sync()
                }
            }
            // Atomic replace: an upload still reading an earlier copy of
            // the same item keeps its open inode.
            if (!part.renameTo(target)) throw IOException("rename $part -> $target failed")
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        return StagedFile(target, isOriginal = stagedUploadIsOriginal(requireOriginal, originalRead))
    }

    /** Deletes [path] if it is a staged copy (never a camera-roll file). */
    fun discardIfStaged(context: Context, path: String) {
        val file = File(path)
        if (file.parentFile?.absolutePath == stagingDir(context).absolutePath) {
            file.delete()
        }
    }

    /**
     * Pure decision for [cleanup]: [status] is the engine's status for the
     * staged path (null when no upload row references it).
     *
     * [engineAnswered] is false when the status map came back empty:
     * the engine is not initialised, the query threw, or the queue holds
     * no media rows. An unknown status then means "not asked", not "no
     * upload row": the copy may be the only one the phone holds of a
     * pending upload, so it is kept (same guard as
     * [OtgStaging.shouldDelete]).
     */
    internal fun shouldDeleteStaged(
        fileName: String,
        status: String?,
        lastModifiedMs: Long,
        nowMs: Long,
        engineAnswered: Boolean = true,
    ): Boolean {
        val age = nowMs - lastModifiedMs
        return when {
            fileName.endsWith(PART_SUFFIX) -> age > PART_GRACE_MS
            status == "COMPLETED" || status == "OFFLOADED" -> true
            status == null || status == "NOT_ENQUEUED" -> engineAnswered && age > ORPHAN_GRACE_MS
            // PENDING / IN_PROGRESS / FAILED: the bytes are still needed
            // for the upload or its retry.
            else -> false
        }
    }

    /**
     * Deletes staged copies whose upload has COMPLETED, plus abandoned
     * ones. Called after each sync batch. Also sweeps the OTG card
     * staging area (see [OtgStaging]) with the same status map. Returns
     * how many were removed.
     *
     * An empty [statuses] map means the status of every staged copy is
     * unknown (the engine is not initialised, the query threw, or the
     * queue holds no media rows). Nothing is reaped then: a copy whose
     * row is still PENDING may be the only copy of that upload.
     */
    fun cleanup(
        context: Context,
        statuses: Map<String, String> = EngineHolder.getAllMediaStatuses(),
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        val engineAnswered = statuses.isNotEmpty()
        var removed = 0
        for (file in stagingDir(context).listFiles().orEmpty()) {
            if (!file.isFile) continue
            val status = statuses[file.absolutePath]
            if (shouldDeleteStaged(file.name, status, file.lastModified(), nowMs, engineAnswered) && file.delete()) {
                removed++
            }
        }
        return removed + OtgStaging.cleanup(context, statuses, nowMs)
    }

    private const val COPY_BUFFER = 64 * 1024
}
