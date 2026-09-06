package com.branchdam.mobile.service

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic logger that writes sync events and errors to a rolling
 * local file in the app's cache directory. This bypasses the
 * ephemeral Logcat and provides a durable trace for users to share
 * when reporting sync issues.
 *
 * T2-11: surfacing engine-level failure details (database locks,
 * TLS handshakes, server 5xx) to a readable file helps debug
 * background sync failures that would otherwise be invisible.
 */
object SyncLogger {
    private const val MAX_LOG_SIZE = 1024 * 1024 // 1MB
    private const val LOG_FILENAME = "branchdam_sync.log"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun log(context: Context, message: String, throwable: Throwable? = null) {
        val logFile = File(context.cacheDir, LOG_FILENAME)

        // Roll the log if it gets too large
        if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
            logFile.renameTo(File(context.cacheDir, "$LOG_FILENAME.old"))
        }

        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] $message\n" + (throwable?.let { "${it.stackTraceToString()}\n" } ?: "")

        try {
            logFile.appendText(logLine)
        } catch (_: Exception) {
            // Fail silently; we don't want a diagnostic failure to crash the worker
        }
    }

    fun getLogPath(context: Context): String = File(context.cacheDir, LOG_FILENAME).absolutePath
}
