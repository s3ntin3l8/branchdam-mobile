package com.branchdam.mobile

import android.util.Log
import io.branchdam.core.branchdam.Branchdam
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-instance holder for the gomobile-bound branchdam engine. All
 * public methods dispatch through a single-threaded executor because
 * gomobile's Go→JNI transport is blocking; the call returns synchronously
 * to the caller.
 *
 * The underlying binding uses the Branchdam.bindingXxx static methods
 * (primitive-only signatures that survive gobind). When the native
 * library is absent (unit tests without the AAR), all calls fall back
 * to mock values so the existing test suite keeps working.
 */
object EngineHolder {
    private const val TAG = "EngineHolder"

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var isInitialized = false

    private val nativeAvailable = AtomicBoolean(false)

    init {
        try {
            Branchdam.touch()
            nativeAvailable.set(true)
        } catch (_: Throwable) {
            nativeAvailable.set(false)
        }
    }

    fun initialize(
        dbPath: String,
        baseURL: String,
        apiKey: String = "",
        agentID: String = "pixel-fold",
        version: String = BuildConfig.VERSION_NAME,
        devCleartextHosts: String = "",
    ): Boolean {
        if (!nativeAvailable.get()) return false
        return try {
            executor.submit(Callable {
                Branchdam.bindingOpen(dbPath, baseURL, apiKey, agentID, version, devCleartextHosts)
            }).get()
            isInitialized = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "bindingOpen failed: $t")
            isInitialized = false
            false
        }
    }

    fun shutdown() {
        if (!nativeAvailable.get()) return
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
        localId: String,
    ): Long {
        if (!nativeAvailable.get()) return 1L
        return try {
            executor.submit(Callable {
                Branchdam.bindingEnqueueMedia(localPath, filename, localId, "", capturedAtUnix, 0L)
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
        if (!nativeAvailable.get()) return java.util.UUID.randomUUID().toString()
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
        if (!nativeAvailable.get()) return java.util.UUID.randomUUID().toString()
        return try {
            executor.submit(Callable { Branchdam.bindingEnqueueDeleteEvent(localID) }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "enqueueDeleteEvent failed: $t")
            ""
        }
    }

    fun syncBatch(timeoutSecs: Int = 120, batchSize: Int = 10) {
        if (!nativeAvailable.get()) return
        try {
            executor.submit(Callable {
                Branchdam.bindingSyncBatch(timeoutSecs.toLong(), batchSize.toLong())
            }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "syncBatch failed: $t")
        }
    }

    fun isMediaOffloaded(localID: String): Boolean {
        if (!nativeAvailable.get()) return false
        return try {
            executor.submit(Callable { Branchdam.bindingIsMediaOffloaded(localID) }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "isMediaOffloaded failed: $t")
            false
        }
    }

    fun setMediaOffloaded(localID: String, isOffloaded: Boolean): Boolean {
        if (!nativeAvailable.get()) return true
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
        if (!nativeAvailable.get()) return MOCK_NAMING_TEMPLATE
        return try {
            executor.submit(Callable { Branchdam.bindingFetchNamingTemplate() }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "fetchNamingTemplate failed: $t")
            MOCK_NAMING_TEMPLATE
        }
    }

    /**
     * Attempts a handshake with the server to verify reachability and
     * authentication. Returns true if the handshake succeeded, false
     * otherwise.
     */
    fun testConnection(): Boolean {
        if (!nativeAvailable.get()) return false
        return try {
            executor.submit(Callable {
                Branchdam.bindingFetchNamingTemplate()
                true
            }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "testConnection failed: $t")
            false
        }
    }

    fun reclaimSafeSpace(localID: String): Boolean {
        if (!nativeAvailable.get()) return false
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

    /**
     * Streams localPath through the BLAKE3-256 hasher and returns the
     * 64-hex-character digest. Used by the OTG ingest pipeline (T2-7) to
     * verify a staged file's bytes match what the source claimed before
     * the upload-side hash is computed. Returns null when the engine is
     * unavailable or the hash can't be computed — the caller treats that
     * as "verify skipped, defer to upload-side BLAKE3".
     */
    fun computeBlake3Hex(localPath: String): String? {
        if (!nativeAvailable.get()) return null
        return try {
            executor.submit(Callable { Branchdam.bindingComputeHashes(localPath) })
                .get()
        } catch (t: Throwable) {
            Log.w(TAG, "computeBlake3Hex($localPath) failed: $t")
            null
        }
    }

    /**
     * Returns the BLAKE3-256 hash most recently recorded against localID
     * in the local_media_state table, or "" if the localID has never
     * been ingested. Used by the OTG ingest pipeline (T2-7) to detect
     * the "same localID, different bytes" case that usually indicates a
     * failing SD card mid-scan: the caller logs a warning when the prior
     * hash is non-empty AND differs from the freshly computed hash.
     */
    fun lookupBlake3ForLocalID(localID: String): String {
        if (!nativeAvailable.get() || localID.isEmpty()) return ""
        return try {
            executor.submit(Callable { Branchdam.bindingLookupBlake3ForLocalID(localID) })
                .get() ?: ""
        } catch (t: Throwable) {
            Log.w(TAG, "lookupBlake3ForLocalID($localID) failed: $t")
            ""
        }
    }

    private const val MOCK_NAMING_TEMPLATE =
        "{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}"
}

fun defaultEngineDbPath(filesDir: File): String = File(filesDir, "branchdam_queue.db").absolutePath
