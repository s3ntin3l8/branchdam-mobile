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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

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
 */
object UploadStaging {
    private const val TAG = "UploadStaging"
    private const val DIR_NAME = "upload_staging"
    private const val PART_SUFFIX = ".part"

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
     */
    fun enqueue(context: Context, item: MediaItem): Long {
        val path = pathForUpload(context, item) ?: return 0L
        val id = EngineHolder.enqueueMedia(
            localPath = path,
            filename = item.displayName,
            capturedAtUnix = item.dateTakenUnix,
            localId = item.contentUri,
        )
        if (id <= 0L) discardIfStaged(context, path)
        return id
    }

    /**
     * The path to enqueue for [item], or null if it cannot be read at all.
     * Falls back to the DATA path on API 30+ when staging fails (for
     * example, not enough free space for a large video): FUSE makes it
     * readable there, at the cost of redacted location.
     */
    fun pathForUpload(context: Context, item: MediaItem): String? {
        val source = chooseUploadSource(Build.VERSION.SDK_INT, hasMediaLocationPermission(context))
        if (source == UploadSource.DATA_PATH) return item.filePath.ifEmpty { null }

        val staged = try {
            stage(context, item, requireOriginal = source == UploadSource.STAGE_ORIGINAL)
        } catch (e: Exception) {
            Log.w(TAG, "staging ${item.contentUri} failed", e)
            null
        }
        if (staged != null) return staged.absolutePath

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && item.filePath.isNotEmpty()) {
            Log.w(TAG, "falling back to the redacted DATA path for ${item.contentUri}")
            return item.filePath
        }
        return null
    }

    /** Staged file name: unique per MediaStore row, keeps the extension. */
    internal fun stagedFileName(item: MediaItem): String {
        val safeName = item.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        return "${item.id}_$safeName"
    }

    private fun stage(context: Context, item: MediaItem, requireOriginal: Boolean): File? {
        val dir = stagingDir(context)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("cannot create $dir")
        val target = File(dir, stagedFileName(item))
        val part = File(dir, target.name + PART_SUFFIX)

        val uri = Uri.parse(item.contentUri)
        val readUri = if (requireOriginal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.setRequireOriginal(uri)
        } else {
            uri
        }
        val input = try {
            context.contentResolver.openInputStream(readUri)
        } catch (e: RuntimeException) {
            // MediaProvider refuses requireOriginal with a SecurityException
            // or UnsupportedOperationException when ACCESS_MEDIA_LOCATION was
            // revoked between the check and the open: take the redacted
            // bytes rather than nothing.
            if (!requireOriginal || (e !is SecurityException && e !is UnsupportedOperationException)) throw e
            Log.w(TAG, "original bytes denied for ${item.contentUri}; staging redacted copy", e)
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
        return target
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
     */
    internal fun shouldDeleteStaged(fileName: String, status: String?, lastModifiedMs: Long, nowMs: Long): Boolean {
        val age = nowMs - lastModifiedMs
        return when {
            fileName.endsWith(PART_SUFFIX) -> age > PART_GRACE_MS
            status == "COMPLETED" || status == "OFFLOADED" -> true
            status == null || status == "NOT_ENQUEUED" -> age > ORPHAN_GRACE_MS
            // PENDING / IN_PROGRESS / FAILED: the bytes are still needed
            // for the upload or its retry.
            else -> false
        }
    }

    /**
     * Deletes staged copies whose upload has COMPLETED, plus abandoned
     * ones. Called after each sync batch. Returns how many were removed.
     */
    fun cleanup(
        context: Context,
        statuses: Map<String, String> = EngineHolder.getAllMediaStatuses(),
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        val files = stagingDir(context).listFiles() ?: return 0
        var removed = 0
        for (file in files) {
            if (!file.isFile) continue
            if (shouldDeleteStaged(file.name, statuses[file.absolutePath], file.lastModified(), nowMs) && file.delete()) {
                removed++
            }
        }
        return removed
    }

    private const val COPY_BUFFER = 64 * 1024
}
