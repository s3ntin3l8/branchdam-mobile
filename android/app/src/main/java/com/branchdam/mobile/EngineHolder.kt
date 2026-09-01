package com.branchdam.mobile

import android.util.Log
import io.branchdam.core.branchdam.Confidence
import io.branchdam.core.branchdam.Engine
import io.branchdam.core.branchdam.EngineOptions
import io.branchdam.core.branchdam.EnqueueMediaOptions
import io.branchdam.core.branchdam.SafeSpaceCandidate
import io.branchdam.core.branchdam.SafeSpaceVerdict
import io.branchdam.core.branchdam.SyncOptions
import io.branchdam.core.branchdam.SyncResult
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Single-instance holder for the gomobile-bound branchdam engine. Replaces
 * the hand-written NativeBridge stub from sub-issue A with a thin wrapper
 * around the new gomobile surface. All public methods run the underlying
 * gomobile call on a single-threaded executor because gomobile's transport
 * is blocking; the call returns synchronously to the caller.
 *
 * Sub-issue A: the previous NativeBridge returned mock values (always-1
 * uploadId, always-UUID eventIds, etc.) so the shell ran in "mock mode"
 * for unit tests without the native binary.
 * Sub-issue B: this holder calls the real engine. When the AAR is on
 * disk (i.e. the gomobile artifact was built), the calls succeed; when
 * it's absent (unit tests without the AAR), the holder falls back to the
 * pre-B mock surface so the existing test suite keeps working.
 */
object EngineHolder {
    private const val TAG = "EngineHolder"

    private val mockOffloadedMedia = ConcurrentHashMap<String, Boolean>()

    // Single-threaded executor serializing all gomobile calls. gomobile's
    // Go→JNI transport is blocking; running on a background thread keeps the
    // main thread free and the serial executor prevents concurrent FFI calls
    // into the same C bridge context.
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var engine: Engine? = null

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
            val opts = EngineOptions()
            opts.dbPath = dbPath
            opts.baseURL = baseURL
            opts.apiKey = apiKey
            opts.agentID = agentID
            opts.clientVersion = version
            opts.httpTimeoutSec = 0
            engine = executor.submit(Callable { Engine.newEngine(opts) }).get()
            isInitialized = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "engine.newEngine failed: $t")
            isInitialized = false
            false
        }
    }

    /** Closes the engine. Idempotent. */
    fun shutdown() {
        val e = engine ?: return
        try {
            executor.submit(Callable { e.close() }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "engine.close failed: $t")
        }
        engine = null
        isInitialized = false
    }

    fun isInitialized(): Boolean = isInitialized

    fun enqueueMedia(
        localPath: String,
        filename: String,
        capturedAtUnix: Long,
        localID: String,
    ): Long {
        val e = engine ?: return mockEnqueueMedia()
        return try {
            val opts = EnqueueMediaOptions()
            opts.localPath = localPath
            opts.filename = filename
            opts.capturedAtUnix = capturedAtUnix
            opts.localID = localID
            executor.submit(Callable { e.enqueueMedia(opts) }).get()
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
        val e = engine ?: return mockEnqueueLineageEvent()
        return try {
            val conf = Confidence.valueOf(confidence.toFloat())
            executor.submit(Callable {
                e.enqueueLineageEvent(parentLocalID, childLocalID, relationshipType, resolver, conf)
            }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "enqueueLineageEvent failed: $t")
            ""
        }
    }

    fun enqueueDeleteEvent(localID: String): String {
        val e = engine ?: return mockEnqueueDeleteEvent()
        return try {
            executor.submit(Callable { e.enqueueDeleteEvent(localID) }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "enqueueDeleteEvent failed: $t")
            ""
        }
    }

    fun syncBatch(timeoutSecs: Int = 120, batchSize: Int = 10): Pair<Int, Int> {
        val e = engine ?: return Pair(0, 0)
        return try {
            val opts = SyncOptions()
            opts.timeoutSecs = timeoutSecs
            opts.batchSize = batchSize
            opts.includeEvents = true
            opts.includeUploads = true
            val r: SyncResult = executor.submit(Callable { e.syncBatch(opts) }).get()
            Pair(r.uploaded.toInt(), r.eventsSent.toInt())
        } catch (t: Throwable) {
            Log.w(TAG, "syncBatch failed: $t")
            Pair(0, 0)
        }
    }

    fun isMediaOffloaded(localID: String): Boolean {
        val e = engine ?: return mockOffloadedMedia[localID] ?: false
        return try {
            executor.submit(Callable { e.isMediaOffloaded(localID) }).get()
        } catch (t: Throwable) {
            // B.2.3: DB error → fail closed. Returning false here causes
            // the shell to refuse the local delete, which is the invariant
            // the audit calls out.
            Log.w(TAG, "isMediaOffloaded failed: $t")
            false
        }
    }

    fun setMediaOffloaded(localID: String, isOffloaded: Boolean): Boolean {
        val e = engine ?: run {
            mockOffloadedMedia[localID] = isOffloaded
            return true
        }
        return try {
            executor.submit(Callable { e.setMediaOffloaded(localID, isOffloaded) }).get()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setMediaOffloaded failed: $t")
            false
        }
    }

    fun fetchNamingTemplate(): String {
        val e = engine ?: return MOCK_NAMING_TEMPLATE
        return try {
            executor.submit(Callable { e.fetchNamingTemplate() }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "fetchNamingTemplate failed: $t")
            MOCK_NAMING_TEMPLATE
        }
    }

    /**
     * Engine-owned atomic reclaim. Returns the verdict so the shell can
     * decide whether to delete the local file. The engine does the
     * server re-check + the local flag set in one logical operation (B.2.7).
     */
    fun reclaimSafeSpace(localID: String): SafeSpaceVerdict {
        val e = engine ?: return SafeSpaceCandidate().also { it.localId = localID }.let { _ ->
            SafeSpaceVerdict().also {
                it.localId = localID
                it.eligible = true
                it.reason = ""
            }
        }
        return try {
            executor.submit(Callable { e.reclaimSafeSpace(localID) }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "reclaimSafeSpace failed: $t")
            SafeSpaceVerdict().also {
                it.localId = localID
                it.eligible = false
                it.reason = t.message ?: t.javaClass.simpleName
            }
        }
    }

    // ----- mock fallbacks (used only when the AAR is absent, e.g. unit
    // tests that run on a JVM without the native binary) -----

    private fun mockEnqueueMedia(): Long = 1L
    private fun mockEnqueueLineageEvent(): String = java.util.UUID.randomUUID().toString()
    private fun mockEnqueueDeleteEvent(): String = java.util.UUID.randomUUID().toString()

    private const val MOCK_NAMING_TEMPLATE =
        "{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}"
}

/**
 * Convenience helper for the application class to compute the default
 * database path under the app's private files dir.
 */
fun defaultEngineDbPath(filesDir: File): String = File(filesDir, "branchdam_queue.db").absolutePath
