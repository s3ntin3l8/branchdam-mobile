package com.branchdam.mobile

import android.util.Log
import io.branchdam.core.branchdam.Branchdam
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    private val syncExecutor = Executors.newSingleThreadExecutor()
    private val queryExecutor = Executors.newFixedThreadPool(2)

    @Volatile
    private var isInitialized = false

    private val nativeAvailable = AtomicBoolean(false)

    /**
     * Released once the first [initialize] attempt finishes, whether it
     * succeeded or not. BranchDamApplication opens the engine on a
     * background thread, so a component woken in a cold process (a
     * notification action, say) waits on it via [awaitInitialized]
     * instead of reading "not initialized" and dropping its work.
     */
    private val firstInitAttempt = FirstAttemptGate()

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
            syncExecutor.submit(Callable {
                Branchdam.bindingOpen(dbPath, baseURL, apiKey, agentID, version, devCleartextHosts)
            }).get()
            isInitialized = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "bindingOpen failed: $t")
            isInitialized = false
            false
        } finally {
            firstInitAttempt.release()
        }
    }

    /**
     * Blocks the calling (background) thread until the first
     * [initialize] attempt has finished, or [timeoutMs] passes, and
     * returns [isInitialized]. Returns false at once when the native
     * library is absent. Never call it on the main thread.
     */
    fun awaitInitialized(timeoutMs: Long): Boolean {
        if (!nativeAvailable.get()) return false
        return firstInitAttempt.await(timeoutMs) { isInitialized }
    }

    fun shutdown() {
        if (!nativeAvailable.get()) return
        try {
            syncExecutor.submit(Callable { Branchdam.bindingClose() }).get()
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
        if (nativeAvailable.get() && !isInitialized) return 0L
        if (!nativeAvailable.get()) return 1L
        return try {
            queryExecutor.submit(Callable {
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
        if (!nativeAvailable.get() || !isInitialized) return java.util.UUID.randomUUID().toString()
        return try {
            queryExecutor.submit(Callable {
                Branchdam.bindingEnqueueLineageEvent(parentLocalID, childLocalID, relationshipType, resolver, confidence)
            }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "enqueueLineageEvent failed: $t")
            ""
        }
    }

    fun enqueueDeleteEvent(localID: String): String {
        if (!nativeAvailable.get() || !isInitialized) return java.util.UUID.randomUUID().toString()
        return try {
            queryExecutor.submit(Callable { Branchdam.bindingEnqueueDeleteEvent(localID) }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "enqueueDeleteEvent failed: $t")
            ""
        }
    }

    fun syncBatch(timeoutSecs: Int = 120, batchSize: Int = 10): Boolean =
        syncBatchDetailed(timeoutSecs, batchSize) is SyncBatchOutcome.Success

    /**
     * Runs one sync batch and reports how it ended, keeping the core's
     * error code (AUTH_FAILED, CONTEXT_CANCELED, NETWORK_ERROR, ...) so
     * the caller can tell "re-pair this device" from a transient
     * failure.
     *
     * Blocks the calling thread until the batch finishes. When the
     * calling thread is interrupted (SyncWorker runs this inside
     * `runInterruptible`) the InterruptedException propagates; the Go
     * batch keeps running on [syncExecutor] until [setCancelFlag]
     * stops it.
     */
    @Throws(InterruptedException::class)
    fun syncBatchDetailed(timeoutSecs: Int = 120, batchSize: Int = 10): SyncBatchOutcome {
        if (!nativeAvailable.get()) return SyncBatchOutcome.Failed(null, "native engine unavailable")
        val future = syncExecutor.submit(Callable {
            Branchdam.bindingSyncBatch(timeoutSecs.toLong(), batchSize.toLong())
        })
        return try {
            future.get()
            SyncBatchOutcome.Success
        } catch (e: InterruptedException) {
            throw e
        } catch (t: Throwable) {
            val cause = (t as? java.util.concurrent.ExecutionException)?.cause ?: t
            Log.w(TAG, "syncBatch failed: $cause")
            SyncBatchOutcome.Failed(coreErrorCode(cause.message), cause.message ?: cause.toString())
        }
    }

    /**
     * Signals an in-flight [syncBatch] to abort at the next per-item
     * checkpoint. Fire-and-forget signal that does not block the
     * caller. Dispatched via [queryExecutor] so it isn't blocked behind
     * an active [syncBatch] on [syncExecutor]. (B.2.2)
     */
    fun setCancelFlag() {
        if (!nativeAvailable.get() || !isInitialized) return
        queryExecutor.submit {
            try {
                Branchdam.bindingSetCancelFlag()
            } catch (t: Throwable) {
                Log.w(TAG, "setCancelFlag failed: $t")
            }
        }
    }

    fun isMediaOffloaded(localID: String): Boolean {
        if (!nativeAvailable.get() || !isInitialized) return false
        return try {
            queryExecutor.submit(Callable { Branchdam.bindingIsMediaOffloaded(localID) }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "isMediaOffloaded failed: $t")
            false
        }
    }

    fun getMediaStatus(localID: String): String {
        if (!nativeAvailable.get() || !isInitialized) return mockMediaStatusMap[localID] ?: "NOT_ENQUEUED"
        return try {
            queryExecutor.submit(Callable { Branchdam.bindingGetMediaStatus(localID) }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "getMediaStatus failed: $t")
            "NOT_ENQUEUED"
        }
    }

    fun getAllMediaStatuses(): Map<String, String> {
        if (!nativeAvailable.get() || !isInitialized) return mockMediaStatusMap
        return try {
            val jsonStr = queryExecutor.submit(Callable { Branchdam.bindingGetAllMediaStatuses() }).get()
            if (jsonStr.isNullOrEmpty() || jsonStr == "{}") return emptyMap()
            val jsonObj = org.json.JSONObject(jsonStr)
            val result = mutableMapOf<String, String>()
            val keys = jsonObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = jsonObj.getString(key)
            }
            result
        } catch (t: Throwable) {
            Log.w(TAG, "getAllMediaStatuses failed: $t")
            emptyMap()
        }
    }

    fun countPendingUploads(): Long {
        if (!nativeAvailable.get() || !isInitialized) return mockPendingUploadsCount
        return try {
            queryExecutor.submit(Callable { Branchdam.bindingCountPendingUploads() }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "countPendingUploads failed: $t")
            0L
        }
    }

    fun resetFailedUploads(): Long {
        if (!nativeAvailable.get() || !isInitialized) return 0L
        return try {
            queryExecutor.submit(Callable { Branchdam.bindingResetFailedUploads() }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "resetFailedUploads failed: $t")
            0L
        }
    }

    fun getActiveUploadProgress(): ActiveUploadProgress? {
        if (!nativeAvailable.get() || !isInitialized) return null
        return try {
            val jsonStr = queryExecutor.submit(Callable { Branchdam.bindingGetActiveUploadProgress() }).get()
            if (jsonStr.isNullOrBlank()) null else parseActiveUploadProgress(jsonStr)
        } catch (t: Throwable) {
            Log.w(TAG, "getActiveUploadProgress failed: $t")
            null
        }
    }

    private fun parseActiveUploadProgress(jsonStr: String): ActiveUploadProgress? {
        return try {
            val obj = org.json.JSONObject(jsonStr)
            ActiveUploadProgress(
                id = obj.optLong("id", 0L),
                filename = obj.optString("filename", ""),
                bytesSent = obj.optLong("bytesSent", 0L),
                totalBytes = obj.optLong("totalBytes", 0L),
                speedBytesPerSec = obj.optDouble("speedBytesPerSec", 0.0),
                itemIndex = obj.optInt("itemIndex", 0),
                totalItems = obj.optInt("totalItems", 0)
            )
        } catch (t: Throwable) {
            null
        }
    }

    @androidx.annotation.VisibleForTesting
    internal val mockMediaStatusMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    @androidx.annotation.VisibleForTesting
    internal var mockPendingUploadsCount: Long = 0L

    fun setMediaOffloaded(localID: String, isOffloaded: Boolean): Boolean {
        if (!nativeAvailable.get() || !isInitialized) return true
        return try {
            queryExecutor.submit(Callable {
                Branchdam.bindingSetMediaOffloaded(localID, isOffloaded)
                true
            }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "setMediaOffloaded failed: $t")
            false
        }
    }

    fun fetchNamingTemplate(): String {
        if (!nativeAvailable.get() || !isInitialized) return MOCK_NAMING_TEMPLATE
        return try {
            queryExecutor.submit(Callable { Branchdam.bindingFetchNamingTemplate() }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "fetchNamingTemplate failed: $t")
            MOCK_NAMING_TEMPLATE
        }
    }

    /**
     * Attempts a handshake with the server to verify reachability and
     * authentication. Returns true if the handshake succeeded, false
     * otherwise. The actual `withTimeoutOrNull` wrapper lives in
     * [SyncStatusViewModel] / [SettingsViewModel]; this method
     * blocks on the gomobile binding for as long as the network
     * round-trip takes.
     */
    fun testConnection(): Boolean {
        return testConnectionDetailed() == null
    }

    /**
     * Attempts a handshake with the server to verify reachability and
     * authentication. Returns null if successful, or the detailed
     * error message string if the handshake fails.
     */
    fun testConnectionDetailed(): String? {
        if (!nativeAvailable.get() || !isInitialized) return "Engine not initialized"
        return try {
            queryExecutor.submit(Callable {
                Branchdam.bindingFetchNamingTemplate()
                null
            }).get()
        } catch (t: Throwable) {
            val msg = t.cause?.message ?: t.message ?: "Handshake failed"
            Log.w(TAG, "testConnection detailed failed: $msg")
            msg
        }
    }

    fun reclaimSafeSpace(localID: String): Boolean {
        if (!nativeAvailable.get()) return false
        return try {
            queryExecutor.submit(Callable {
                Branchdam.bindingReclaimSafeSpace(localID)
                true
            }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "reclaimSafeSpace failed: $t")
            false
        }
    }

    /**
     * Asks the engine which of [localIDs] are safe to reclaim. The engine
     * looks up each local ID's node UUID, batches the server's
     * node-status calls and applies the same reclaim gate as
     * [reclaimSafeSpace] (verified, Tier-3 master, live). Returns null
     * when the engine is unavailable or the server could not be asked, so
     * the caller can tell "nothing eligible" from "couldn't check".
     */
    fun checkSafeSpaceCandidates(localIDs: List<String>): List<com.branchdam.mobile.triage.SafeSpaceVerdict>? {
        if (localIDs.isEmpty()) return emptyList()
        if (!nativeAvailable.get() || !isInitialized) return null
        return try {
            // The binding takes a comma-separated list; chunk it so one
            // call never carries an unbounded string across JNI.
            localIDs.chunked(SAFE_SPACE_CHECK_CHUNK).flatMap { chunk ->
                val json = queryExecutor.submit(Callable {
                    Branchdam.bindingCheckSafeSpaceCandidates(chunk.joinToString(","))
                }).get()
                com.branchdam.mobile.triage.parseSafeSpaceVerdicts(json)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "checkSafeSpaceCandidates failed: $t")
            null
        }
    }

    private const val SAFE_SPACE_CHECK_CHUNK = 200

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
            queryExecutor.submit(Callable { Branchdam.bindingComputeHashes(localPath) })
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
            queryExecutor.submit(Callable { Branchdam.bindingLookupBlake3ForLocalID(localID) })
                .get() ?: ""
        } catch (t: Throwable) {
            Log.w(TAG, "lookupBlake3ForLocalID($localID) failed: $t")
            ""
        }
    }

    // -----------------------------------------------------------------
    // Pending imports (the core's pending_import table)
    //
    // A camera-roll item waiting for the user's "Import now" / "Skip"
    // answer is persisted in the core's queue DB, so the intent outlives
    // the app process. Without the native library (JVM unit tests) the
    // same contract is served from the in-memory mock below.
    // -----------------------------------------------------------------

    /** Result of [stagePendingImport]. */
    enum class StageResult {
        /** The item is now recorded as awaiting the user's decision. */
        STAGED,

        /** The user already skipped the item; it was not re-offered. */
        SUPPRESSED,

        /** Nothing was recorded (engine not open, DB error). */
        FAILED,
    }

    fun stagePendingImport(
        localId: String,
        localPath: String,
        filename: String,
        mimeType: String,
        sizeBytes: Long,
        capturedAtUnix: Long,
    ): StageResult {
        if (localId.isEmpty()) return StageResult.FAILED
        if (!nativeAvailable.get()) {
            return mockPendingImports.stage(
                PendingImport(localId, localPath, filename, mimeType, sizeBytes, capturedAtUnix, mockPendingImports.clock())
            )
        }
        if (!isInitialized) return StageResult.FAILED
        return try {
            val staged = queryExecutor.submit(Callable {
                Branchdam.bindingStagePendingImport(localId, localPath, filename, mimeType, sizeBytes, capturedAtUnix)
            }).get()
            if (staged) StageResult.STAGED else StageResult.SUPPRESSED
        } catch (t: Throwable) {
            Log.w(TAG, "stagePendingImport failed: $t")
            StageResult.FAILED
        }
    }

    /**
     * Items still awaiting an import decision, oldest capture first, or
     * null when they can't be read (engine not open, DB error). A null
     * is not "nothing pending": callers must leave their state alone.
     */
    fun listPendingImports(limit: Int = 0): List<PendingImport>? {
        if (!nativeAvailable.get()) return mockPendingImports.list(limit)
        if (!isInitialized) return null
        return try {
            val json = queryExecutor.submit(Callable {
                Branchdam.bindingListPendingImports(limit.toLong())
            }).get()
            parsePendingImports(json)
        } catch (t: Throwable) {
            Log.w(TAG, "listPendingImports failed: $t")
            null
        }
    }

    /** Forgets localId (after it was imported). True when a row was removed. */
    fun deletePendingImport(localId: String): Boolean {
        if (localId.isEmpty()) return false
        if (!nativeAvailable.get()) return mockPendingImports.delete(localId)
        if (!isInitialized) return false
        return try {
            queryExecutor.submit(Callable { Branchdam.bindingDeletePendingImport(localId) }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "deletePendingImport failed: $t")
            false
        }
    }

    /** Records that the user skipped localId. True when it was recorded. */
    fun suppressPendingImport(localId: String): Boolean {
        if (localId.isEmpty()) return false
        if (!nativeAvailable.get()) {
            mockPendingImports.suppress(localId)
            return true
        }
        if (!isInitialized) return false
        return try {
            queryExecutor.submit(Callable {
                Branchdam.bindingSuppressPendingImport(localId)
                true
            }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "suppressPendingImport failed: $t")
            false
        }
    }

    /** Whether the user skipped localId. False when it can't be read. */
    fun isImportSuppressed(localId: String): Boolean {
        if (localId.isEmpty()) return false
        if (!nativeAvailable.get()) return mockPendingImports.isSuppressed(localId)
        if (!isInitialized) return false
        return try {
            queryExecutor.submit(Callable { Branchdam.bindingIsImportSuppressed(localId) }).get()
        } catch (t: Throwable) {
            Log.w(TAG, "isImportSuppressed failed: $t")
            false
        }
    }

    /** In-memory stand-in for pending_import when the AAR is absent. */
    @androidx.annotation.VisibleForTesting
    internal val mockPendingImports = MockPendingImportStore()

    private const val MOCK_NAMING_TEMPLATE =
        "{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}"
}

data class ActiveUploadProgress(
    val id: Long,
    val filename: String,
    val bytesSent: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Double,
    val itemIndex: Int,
    val totalItems: Int
) {
    val progressFraction: Float
        get() = if (totalBytes > 0) (bytesSent.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val formattedTransferred: String
        get() = com.branchdam.mobile.ui.gallery.formatFileSize(bytesSent)

    val formattedTotal: String
        get() = com.branchdam.mobile.ui.gallery.formatFileSize(totalBytes)

    val formattedSpeed: String
        get() = if (speedBytesPerSec > 0) "${com.branchdam.mobile.ui.gallery.formatFileSize(speedBytesPerSec.toLong())}/s" else "0 B/s"
}

/**
 * The one-shot gate behind [EngineHolder.awaitInitialized]: [await]
 * blocks until [release] has been called once (the first initialize
 * attempt finished, successfully or not) or the timeout passes, then
 * reports readiness via the caller's `isReady`. Readiness is re-read
 * after the wait rather than carried by the gate, so a later successful
 * initialize (or a shutdown) is what callers see.
 */
internal class FirstAttemptGate {
    private val latch = CountDownLatch(1)

    /** Marks the first attempt finished; later calls are no-ops. */
    fun release() {
        latch.countDown()
    }

    /**
     * Returns [isReady] at once when it is already true; otherwise waits
     * up to [timeoutMs] for [release] and returns [isReady] then. An
     * interrupted wait returns early with the thread's interrupt flag
     * restored.
     */
    fun await(timeoutMs: Long, isReady: () -> Boolean): Boolean {
        if (isReady()) return true
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return isReady()
    }
}

fun defaultEngineDbPath(filesDir: File): String = File(filesDir, "branchdam_queue.db").absolutePath

/** How an [EngineHolder.syncBatchDetailed] call ended. */
sealed class SyncBatchOutcome {
    object Success : SyncBatchOutcome()

    /**
     * The batch failed. [code] is the core's error code (see
     * branchdam.go's Code* constants) or null when the error carried
     * none.
     */
    data class Failed(val code: String?, val message: String) : SyncBatchOutcome()
}

/** Error codes the core reports that the Android shell acts on. */
object CoreErrorCodes {
    /** The server rejected this device's credentials: re-pair it. */
    const val AUTH_FAILED = "AUTH_FAILED"

    /** The batch was cancelled (SetCancelFlag) or ran out of time. */
    const val CONTEXT_CANCELED = "CONTEXT_CANCELED"
}

private val CORE_ERROR_CODE = Regex("""branchdam: ([A-Z][A-Z0-9_]*):""")

/**
 * Extracts the core's error code from a gomobile exception message. The
 * core's errors render as `branchdam: <CODE>: <message>` (branchdam.Error);
 * anything else yields null.
 */
fun coreErrorCode(message: String?): String? =
    message?.let { CORE_ERROR_CODE.find(it)?.groupValues?.get(1) }

/** A camera-roll item awaiting the user's import decision. */
data class PendingImport(
    val localId: String,
    val localPath: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val capturedAtUnix: Long,
    val createdAtUnix: Long,
)

/**
 * Parses the JSON array BindingListPendingImports returns (the core's
 * queue.PendingImport objects). Rows without a localId are dropped.
 */
fun parsePendingImports(json: String?): List<PendingImport> {
    if (json.isNullOrBlank()) return emptyList()
    val array = org.json.JSONArray(json)
    val out = ArrayList<PendingImport>(array.length())
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        val localId = obj.optString("localId", "")
        if (localId.isEmpty()) continue
        out.add(
            PendingImport(
                localId = localId,
                localPath = obj.optString("localPath", ""),
                filename = obj.optString("filename", ""),
                mimeType = obj.optString("mimeType", ""),
                sizeBytes = obj.optLong("sizeBytes", 0L),
                capturedAtUnix = obj.optLong("capturedAtUnix", 0L),
                createdAtUnix = obj.optLong("createdAtUnix", 0L),
            )
        )
    }
    return out
}

/**
 * Mirrors the core's pending_import semantics for JVM tests: staging a
 * skipped item is refused, re-staging a pending one refreshes it (keeping
 * its createdAt), suppressing works whether or not the item was staged.
 */
internal class MockPendingImportStore {
    /** Seconds since the epoch stamped as createdAt; tests may replace it. */
    @Volatile
    var clock: () -> Long = { System.currentTimeMillis() / 1000L }

    private val pending = LinkedHashMap<String, PendingImport>()
    private val suppressed = HashSet<String>()

    @Synchronized
    fun stage(item: PendingImport): EngineHolder.StageResult {
        if (item.localId in suppressed) return EngineHolder.StageResult.SUPPRESSED
        val existing = pending[item.localId]
        pending[item.localId] = if (existing != null) item.copy(createdAtUnix = existing.createdAtUnix) else item
        return EngineHolder.StageResult.STAGED
    }

    @Synchronized
    fun list(limit: Int): List<PendingImport> {
        val all = pending.values.sortedWith(compareBy({ it.capturedAtUnix }, { it.localId }))
        return if (limit > 0) all.take(limit) else all
    }

    @Synchronized
    fun delete(localId: String): Boolean {
        val removedPending = pending.remove(localId) != null
        val removedSuppressed = suppressed.remove(localId)
        return removedPending || removedSuppressed
    }

    @Synchronized
    fun suppress(localId: String) {
        pending.remove(localId)
        suppressed.add(localId)
    }

    @Synchronized
    fun isSuppressed(localId: String): Boolean = localId in suppressed

    @Synchronized
    fun clear() {
        pending.clear()
        suppressed.clear()
    }
}
