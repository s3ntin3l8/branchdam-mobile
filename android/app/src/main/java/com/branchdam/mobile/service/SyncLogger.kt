package com.branchdam.mobile.service

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Diagnostic logger that writes sync events and errors to a rolling
 * local file in the app's cache directory. This bypasses the
 * ephemeral Logcat and provides a durable trace for users to share
 * when reporting sync issues.
 *
 * T2-11: surfacing engine-level failure details (database locks,
 * TLS handshakes, server 5xx) to a readable file helps debug
 * background sync failures that would otherwise be invisible.
 *
 * Thread safety: all public methods are synchronized because
 * multiple SyncWorker instances or concurrent coroutine contexts
 * may call [log] simultaneously.
 */
object SyncLogger {
    private const val MAX_LOG_SIZE = 1024 * 1024 // 1MB
    private const val LOG_SUBDIR = "sync_logs"
    private const val LOG_FILENAME = "branchdam_sync.log"
    private val dateFormat: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    @Synchronized
    fun log(context: Context, message: String, throwable: Throwable? = null) {
        val logDir = File(context.cacheDir, LOG_SUBDIR)
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        val logFile = File(logDir, LOG_FILENAME)

        // Roll the log if it gets too large
        if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
            logFile.renameTo(File(logDir, "$LOG_FILENAME.old"))
        }

        val timestamp = dateFormat.format(Instant.now())
        val logLine = "[$timestamp] $message\n" + (throwable?.let { "${it.stackTraceToString()}\n" } ?: "")

        try {
            logFile.appendText(logLine)
        } catch (_: Exception) {
            // Fail silently; we don't want a diagnostic failure to crash the worker
        }
    }

    fun getLogPath(context: Context): String =
        File(File(context.cacheDir, LOG_SUBDIR), LOG_FILENAME).absolutePath

    fun hasLog(context: Context): Boolean =
        File(File(context.cacheDir, LOG_SUBDIR), LOG_FILENAME).exists()
}
