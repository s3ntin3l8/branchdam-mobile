package com.branchdam.mobile.otg

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Where OTG card copies wait for upload, and when they may go.
 *
 * Each confirmed import stages into its own session directory under
 * `noBackupFilesDir/otg_staging`. A per-session directory keeps two cards
 * that both hold `DCIM/100CANON/IMG_0001.CR3` from overwriting each other
 * while the first copy is still queued. `noBackupFilesDir` keeps
 * multi-gigabyte RAW and video files out of Auto Backup, and the system
 * never purges it the way it may purge `cacheDir`.
 *
 * Copies are deleted only once the engine reports their upload
 * COMPLETED (or OFFLOADED). The card itself may have been reformatted by
 * then, so a staged copy that is still PENDING, IN_PROGRESS or FAILED is
 * the only copy the phone holds and is never removed.
 */
object OtgStaging {
    private const val DIR_NAME = "otg_staging"

    /** Pre-session location (`filesDir/otg_stage`); swept with the same rules. */
    private const val LEGACY_DIR_NAME = "otg_stage"

    internal const val PART_SUFFIX = ".part"

    /** An interrupted copy (or an empty session directory) is abandoned after this long. */
    internal const val PART_GRACE_MS = 60L * 60L * 1000L

    /**
     * A finished copy no upload row references (the engine matched it to
     * an earlier upload of the same bytes) is removed after this long.
     * Longer than the camera-roll grace: there is no MediaStore original
     * to fall back on.
     */
    internal const val ORPHAN_GRACE_MS = 7L * 24L * 60L * 60L * 1000L

    fun root(context: Context): File = File(context.noBackupFilesDir, DIR_NAME)

    fun legacyRoot(context: Context): File = File(context.filesDir, LEGACY_DIR_NAME)

    /** A new, not-yet-existing session directory under [root]. */
    fun newSessionDir(root: File, nowMs: Long = System.currentTimeMillis()): File {
        var dir = File(root, "s$nowMs")
        var n = 1
        while (dir.exists()) {
            dir = File(root, "s${nowMs}_${n++}")
        }
        return dir
    }

    /**
     * The staged location for [relativePath] inside [sessionDir], or null
     * when the path would escape it (absolute, empty, or with `.`/`..`
     * segments). The path is not canonicalised, so it matches the path
     * the engine records for the upload.
     */
    fun stagedFile(sessionDir: File, relativePath: String): File? {
        if (relativePath.isEmpty() || relativePath.startsWith("/") || relativePath.startsWith("\\")) return null
        val segments = relativePath.split('/', '\\')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return File(sessionDir, relativePath)
    }

    /** The in-progress name a copy is written to before its atomic rename. */
    fun partFileFor(target: File): File = File(target.parentFile, target.name + PART_SUFFIX)

    /**
     * Pure decision for [sweep]. [status] is the engine's status for the
     * staged path (null when no upload row references it). [engineAnswered]
     * is false when the status map came back empty, which is also what a
     * failed or uninitialised engine returns: an unknown file is then kept.
     */
    internal fun shouldDelete(
        fileName: String,
        status: String?,
        lastModifiedMs: Long,
        nowMs: Long,
        engineAnswered: Boolean,
    ): Boolean {
        val age = nowMs - lastModifiedMs
        return when {
            fileName.endsWith(PART_SUFFIX) -> age > PART_GRACE_MS
            status == "COMPLETED" || status == "OFFLOADED" -> true
            status == null || status == "NOT_ENQUEUED" -> engineAnswered && age > ORPHAN_GRACE_MS
            // PENDING / IN_PROGRESS / FAILED: the upload or its retry
            // still reads these bytes.
            else -> false
        }
    }

    /**
     * Deletes the staged copies under [root] that [shouldDelete] allows,
     * then the session directories left empty. Returns how many files
     * were removed.
     */
    fun sweep(root: File, statuses: Map<String, String>, nowMs: Long = System.currentTimeMillis()): Int {
        if (!root.isDirectory) return 0
        val engineAnswered = statuses.isNotEmpty()
        var removed = 0
        root.walkBottomUp().forEach { file ->
            if (file == root) return@forEach
            if (file.isFile) {
                val status = statuses[file.absolutePath] ?: canonicalOrNull(file)?.let { statuses[it] }
                if (shouldDelete(file.name, status, file.lastModified(), nowMs, engineAnswered) && file.delete()) {
                    removed++
                }
            } else if (file.isDirectory) {
                // A just-created session directory is briefly empty while
                // its first copy starts: only prune old empty ones.
                if (file.list()?.isEmpty() == true && nowMs - file.lastModified() > PART_GRACE_MS) {
                    file.delete()
                }
            }
        }
        return removed
    }

    /** Sweeps the current and the legacy staging roots. */
    fun cleanup(context: Context, statuses: Map<String, String>, nowMs: Long = System.currentTimeMillis()): Int =
        sweep(root(context), statuses, nowMs) + sweep(legacyRoot(context), statuses, nowMs)

    private fun canonicalOrNull(file: File): String? = try {
        file.canonicalPath
    } catch (e: IOException) {
        null
    }
}
