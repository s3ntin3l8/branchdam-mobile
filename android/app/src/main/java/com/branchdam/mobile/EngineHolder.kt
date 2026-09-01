package com.branchdam.mobile

import android.util.Log
import io.branchdam.core.branchdam.Branchdam
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Single-instance holder for the gomobile-bound branchdam engine. All
 * public methods dispatch through a single-threaded executor because
 * gomobile's Go→JNI transport is blocking; the call returns synchronously
 * to the caller.
 *
 * The underlying binding uses the Branchdam.bindingXxx static methods
 * (primitive-only signatures that survive gobind). Methods on the Engine
 * class itself are skipped by gomobile because they take/return struct
 * types; this wrapper bridges that gap.
 */
object EngineHolder {
    private const val TAG = "EngineHolder"

    // Single-threaded executor serializing all gomobile calls. gomobile's
    // Go→JNI transport is blocking; running on a background thread keeps the
    // main thread free and the serial executor prevents concurrent FFI calls
    // into the same C bridge context.
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var isInitialized = false

    /**
     * Initialize the Go engine. Returns true on success. Failures are
     * logged at WARN; the existing shell contract returns Bool.
     */
    fun initialize(
        dbPath: String,
        baseURL: String,
        apiKey: String = "",
        agentID: String = "pixel-fold",
        version: String = "0.1.0",
    ): Boolean {
        return try {
            executor.submit(Callable {
                Branchdam.bindingOpen(dbPath, baseURL, apiKey, agentID, version)
            }).get()
            isInitialized = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "bindingOpen failed: $t")
            isInitialized = false
            false
        }
    }

    /** Closes the engine. Idempotent. */
    fun shutdown() {
        try {
            executor.submit(Callable { Branchdam.bindingClose() }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "bindingClose failed: $t")
        }
        isInitialized = false
    }

    fun isInitialized(): Boolean = isInitialized

    fun enqueueMedia(
        localPath: String,
        filename: String,
        capturedAtUnix: Long,
        localID: String,
    ): Long {
        return try {
            executor.submit(Callable {
                Branchdam.bindingEnqueueMedia(localPath, filename, localID, "", capturedAtUnix, 0L)
            }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "enqueueMedia failed: $t")
            0L
        }
    }

    fun enqueueLineageEvent(
        parentLocalID: String,
        childLocalID: String,
        relationshipType: String = "DERIVED_FROM",
        resolver: String = "android_camera_pair",
        confidence: Double = 1.00,
    ): String {
        return try {
            executor.submit(Callable {
                Branchdam.bindingEnqueueLineageEvent(parentLocalID, childLocalID, relationshipType, resolver, confidence)
            }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "enqueueLineageEvent failed: $t")
            ""
        }
    }

    fun enqueueDeleteEvent(localID: String): String {
        return try {
            executor.submit(Callable { Branchdam.bindingEnqueueDeleteEvent(localID) }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "enqueueDeleteEvent failed: $t")
            ""
        }
    }

    fun syncBatch(timeoutSecs: Int = 120, batchSize: Int = 10) {
        try {
            executor.submit(Callable {
                Branchdam.bindingSyncBatch(timeoutSecs.toLong(), batchSize.toLong())
            }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "syncBatch failed: $t")
        }
    }

    fun isMediaOffloaded(localID: String): Boolean {
        return try {
            executor.submit(Callable { Branchdam.bindingIsMediaOffloaded(localID) }).get()
        } catch (t: Throwable) {
            // B.2.3: DB error → fail closed. Returning false causes the
            // shell to refuse the local delete.
            Log.w(TAG, "isMediaOffloaded failed: $t")
            false
        }
    }

    fun setMediaOffloaded(localID: String, isOffloaded: Boolean): Boolean {
        return try {
            executor.submit(Callable {
                Branchdam.bindingSetMediaOffloaded(localID, isOffloaded)
                true
            }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "setMediaOffloaded failed: $t")
            false
        }
    }

    fun fetchNamingTemplate(): String {
        return try {
            executor.submit(Callable { Branchdam.bindingFetchNamingTemplate() }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "fetchNamingTemplate failed: $t")
            MOCK_NAMING_TEMPLATE
        }
    }

    /**
     * Engine-owned atomic reclaim. The engine does the server re-check +
     * the local flag set in one logical operation (B.2.7). Returns true if
     * the asset is eligible and the flag was set.
     */
    fun reclaimSafeSpace(localID: String): Boolean {
        return try {
            executor.submit(Callable {
                Branchdam.bindingReclaimSafeSpace(localID)
                true
            }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "reclaimSafeSpace failed: $t")
            false
        }
    }

    private const val MOCK_NAMING_TEMPLATE =
        "{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}"
}

/**
 * Convenience helper for the application class to compute the default
 * database path under the app's private files dir.
 */
fun defaultEngineDbPath(filesDir: File): String = File(filesDir, "branchdam_queue.db").absolutePath
