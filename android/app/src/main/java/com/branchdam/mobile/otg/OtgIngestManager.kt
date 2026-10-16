package com.branchdam.mobile.otg

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.R
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CancellationException

data class OtgIngestProgress(
    val currentFileIndex: Int,
    val totalFiles: Int,
    val currentFileName: String,
    val bytesProcessed: Long,
    val totalBytes: Long
) {
    val percentage: Float
        get() = if (totalBytes > 0) (bytesProcessed.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * One file-level failure observed during an OTG ingest pass. Surfaced in
 * [OtgState.Completed.fileErrors] so the post-ingest dialog can show the
 * user which files were skipped and why (typically "File too large to
 * ingest" when the candidate exceeds [OtgIngestManager.MAX_OTG_STAGE_BYTES]
 * or "Copy failed mid-stream; SD card may be failing" when the bytes copied
 * don't match the candidate's reported sizeBytes).
 */
data class OtgIngestFileError(
    val candidate: OtgMediaCandidate,
    val message: String,
)

sealed class OtgState {
    object Idle : OtgState()
    data class Scanning(val deviceLabel: String) : OtgState()

    /**
     * A card reader was attached but its volume is not readable through
     * the filesystem (the norm since scoped storage). The user has to
     * pick the card's folder through the Storage Access Framework.
     */
    data class NeedsCardAccess(val deviceLabel: String) : OtgState()
    data class AwaitingConfirmation(val scanResult: OtgScanResult) : OtgState()
    data class Ingesting(val progress: OtgIngestProgress) : OtgState()
    data class Completed(
        val importedCount: Int,
        val totalBytes: Long,
        val fileErrors: List<OtgIngestFileError> = emptyList(),
    ) : OtgState()
    data class Error(val message: String) : OtgState()
}

/**
 * Optional callback for hashes the post-copy BLAKE3 verifier observes for a
 * given candidate. The first value is the freshly-computed hash, the second
 * is the prior hash recorded in the queue (empty string when this is the
 * first time we've seen the localID). Used by the OTG ingest pipeline to
 * log a warning when the two differ — usually a sign that the source SD
 * card is returning inconsistent bytes between scans.
 */
typealias OtgHashObserver = (candidate: OtgMediaCandidate, freshHashHex: String, priorHashHex: String) -> Unit

/**
 * Hash-source abstraction used by [OtgIngestManager] for the post-copy
 * BLAKE3 verify step. The first return value is the freshly-computed
 * BLAKE3-256 hex of the staged file, the second is the prior hash for
 * the same `localID` already recorded in the queue (empty string if
 * this is the first time we've seen the file). Returning `null` for the
 * fresh hash means "engine unavailable" — the verifier falls through
 * to the success path and the upload side will recompute the canonical
 * hash later.
 *
 * Production wiring is [DefaultOtgHashProvider], which delegates to
 * the gomobile-bound `EngineHolder`. Tests inject a custom provider to
 * exercise the prior/fresh mismatch case without loading the AAR.
 */
fun interface OtgHashProvider {
    fun lookup(localID: String, stagedFile: File): Pair<String?, String>
}

class DefaultOtgHashProvider : OtgHashProvider {
    override fun lookup(localID: String, stagedFile: File): Pair<String?, String> {
        // In a cold process the engine opens on a background thread; wait
        // for that first attempt so the verify step isn't skipped.
        EngineHolder.awaitInitialized(ENGINE_WAIT_MS)
        val fresh = EngineHolder.computeBlake3Hex(stagedFile.absolutePath)
        val prior = EngineHolder.lookupBlake3ForLocalID(localID)
        return Pair(fresh, prior)
    }
}

/**
 * Adds a verified staged copy to the engine's upload queue. Returns the
 * queue ID (> 0), or 0 when the engine refused it; the staged copy is
 * then deleted and the file reported as skipped.
 */
fun interface OtgEnqueuer {
    fun enqueue(stagedFile: File, candidate: OtgMediaCandidate): Long
}

class DefaultOtgEnqueuer : OtgEnqueuer {
    override fun enqueue(stagedFile: File, candidate: OtgMediaCandidate): Long {
        // enqueueMedia refuses (returns 0) until the engine is open.
        EngineHolder.awaitInitialized(ENGINE_WAIT_MS)
        return EngineHolder.enqueueMedia(
            localPath = stagedFile.absolutePath,
            filename = candidate.fileName,
            capturedAtUnix = candidate.lastModifiedUnix,
            localId = candidate.localId,
        )
    }
}

/**
 * How long the default hash provider and enqueuer wait for the engine's
 * first open attempt (see [EngineHolder.awaitInitialized]). Once that
 * attempt has finished, the wait returns at once.
 */
private const val ENGINE_WAIT_MS = 30_000L

class OtgIngestManager(
    private val context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val hashObserver: OtgHashObserver? = null,
    private val hashProvider: OtgHashProvider = DefaultOtgHashProvider(),
    private val enqueuer: OtgEnqueuer = DefaultOtgEnqueuer(),
) {
    private val _state = MutableStateFlow<OtgState>(OtgState.Idle)
    val state: StateFlow<OtgState> = _state.asStateFlow()

    /**
     * The current scan or import run. A cancelled import clears its state
     * only while it is still this job (see [confirmImport]). Guarded by
     * [jobLock]: the UI thread replaces it while a cancelled run on
     * [scope] checks it.
     */
    private var inFlightJob: Job? = null

    /**
     * Makes [replaceInFlight]'s swap and [clearIfCurrent]'s check-and-clear
     * atomic with respect to each other. Nothing inside it suspends.
     */
    private val jobLock = Any()

    /**
     * Called when a USB mass storage / SD card is attached or selected via SAF.
     * Starts background scanning and transitions to AwaitingConfirmation upon completion.
     */
    fun onCardDetected(deviceLabel: String, directory: File) {
        startRun {
            _state.value = OtgState.Scanning(deviceLabel)
            val result = withContext(ioDispatcher) {
                OtgCardScanner.scanDirectory(directory, deviceLabel)
            }
            if (result.candidates.isNotEmpty()) {
                _state.value = OtgState.AwaitingConfirmation(result)
            } else {
                _state.value = OtgState.Idle
            }
        }
    }

    /**
     * Called when a card reader is attached but no readable mount was
     * found. Asks the user to pick the card through the system folder
     * picker instead of guessing a path (the phone's own DCIM is never a
     * fallback: importing it would re-upload the camera roll as if it
     * came from the card).
     */
    fun onCardNeedsAccess(deviceLabel: String) {
        replaceInFlight(null)
        _state.value = OtgState.NeedsCardAccess(deviceLabel)
    }

    /**
     * Called when a SAF DocumentTree URI is provided by the user.
     */
    fun onDocumentTreeSelected(treeUri: Uri, deviceLabel: String = "SD Card") {
        startRun {
            _state.value = OtgState.Scanning(deviceLabel)
            val result = withContext(ioDispatcher) {
                if (context != null) {
                    OtgCardScanner.scanDocumentTree(context, treeUri, deviceLabel)
                } else {
                    OtgScanResult(deviceLabel, treeUri.toString(), emptyList())
                }
            }
            // The user picked this folder on purpose: say so when it holds
            // nothing importable instead of silently closing.
            _state.value = if (result.candidates.isNotEmpty()) {
                OtgState.AwaitingConfirmation(result)
            } else {
                OtgState.Error(text(R.string.otg_error_no_media_in_folder, NO_MEDIA_IN_FOLDER))
            }
        }
    }

    /**
     * Human confirmation hook to proceed with the ingest. Each candidate
     * is copied into [destinationDir] (a fresh session directory under
     * [OtgStaging.root] by default), verified, and enqueued through
     * [enqueuer]. A file that fails any step is skipped and reported in
     * [OtgState.Completed.fileErrors]; the rest of the card still imports.
     * [onFileStaged] runs after a successful enqueue.
     */
    fun confirmImport(
        scanResult: OtgScanResult,
        destinationDir: File = defaultSessionDir(),
        onFileStaged: (file: File, candidate: OtgMediaCandidate) -> Unit = { _, _ -> }
    ) {
        startRun {
            val thisRun = coroutineContext[Job]
            val candidates = scanResult.candidates
            val totalBytes = scanResult.totalSizeBytes
            // bytesProcessed drives the progress bar and also counts
            // skipped files; importedBytes is what actually got queued.
            var bytesProcessed = 0L
            var importedBytes = 0L
            var importedCount = 0
            val fileErrors = mutableListOf<OtgIngestFileError>()

            if (!destinationDir.exists()) {
                destinationDir.mkdirs()
            }

            try {
                for ((index, candidate) in candidates.withIndex()) {
                    if (!isActive) break

                    _state.value = OtgState.Ingesting(
                        OtgIngestProgress(
                            currentFileIndex = index + 1,
                            totalFiles = candidates.size,
                            currentFileName = candidate.fileName,
                            bytesProcessed = bytesProcessed,
                            totalBytes = totalBytes
                        )
                    )

                    val stageResult = withContext(ioDispatcher) {
                        copyCandidateToStage(candidate, destinationDir)
                    }
                    bytesProcessed += candidate.sizeBytes.coerceAtLeast(0L)

                    val stagedFile = when (stageResult) {
                        is StageResult.Success -> stageResult.stagedFile
                        is StageResult.Failure -> {
                            fileErrors += OtgIngestFileError(candidate, stageResult.message)
                            continue
                        }
                    }
                    val enqueueFailure = withContext(ioDispatcher) { enqueueStaged(stagedFile, candidate) }
                    if (enqueueFailure != null) {
                        fileErrors += OtgIngestFileError(candidate, enqueueFailure)
                        continue
                    }
                    importedBytes += candidate.sizeBytes
                    importedCount++
                    try {
                        onFileStaged(stagedFile, candidate)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        // The file is already queued; a failing observer
                        // must not abort the rest of the card.
                        Log.w(TAG, "onFileStaged failed for ${candidate.fileName}", e)
                    }
                }

                if (isActive) {
                    _state.value = OtgState.Completed(
                        importedCount = importedCount,
                        totalBytes = importedBytes,
                        fileErrors = fileErrors,
                    )
                }
            } catch (ce: CancellationException) {
                // Cancelled by the user (cancelImport or reset already set
                // Idle and cleared inFlightJob) or by a newer run, such as
                // a card event publishing NeedsCardAccess or a second
                // confirmImport publishing its own Ingesting. Either way
                // the state belongs to someone else now. Only a run that is
                // still the current one (cancelled with its scope) clears
                // its own progress: checking the run, not the state type,
                // keeps a late cancellation from dismissing a newer
                // Ingesting.
                clearIfCurrent(thisRun)
            } catch (e: Exception) {
                _state.value = OtgState.Error("Failed to import SD card media: ${e.message ?: "Unknown error"}")
            }
        }
    }

    /**
     * Human cancellation hook to reject or skip the ingest.
     */
    fun cancelImport() {
        replaceInFlight(null)
        _state.value = OtgState.Idle
    }

    /**
     * Resets state back to Idle after viewing completion or error.
     */
    fun reset() {
        replaceInFlight(null)
        _state.value = OtgState.Idle
    }

    /**
     * Launches [block] as the new current run. It is started lazily, after
     * [replaceInFlight] has made it current, so its body (and its
     * cancellation handler) never runs while [inFlightJob] still names an
     * older run, and every state it publishes comes after the swap.
     */
    private fun startRun(block: suspend CoroutineScope.() -> Unit) {
        val run = scope.launch(start = CoroutineStart.LAZY, block = block)
        replaceInFlight(run)
        run.start()
    }

    /**
     * Makes [next] the current run and cancels the previous one, as one
     * step under [jobLock]. [next] is assigned before the cancel: a
     * cancelled run's handler, inline on this thread or later on another,
     * can never find itself still current.
     */
    private fun replaceInFlight(next: Job?) {
        synchronized(jobLock) {
            val previous = inFlightJob
            inFlightJob = next
            previous?.cancel()
        }
    }

    /**
     * A cancelled run's cleanup: clears the state to Idle only while [run]
     * is still the current run. The check, the write and the clear happen
     * under [jobLock], so a [replaceInFlight] on another thread cannot slip
     * in between the check and the write and have its newer state
     * (Scanning, Ingesting, NeedsCardAccess) overwritten with Idle.
     */
    private fun clearIfCurrent(run: Job?) {
        synchronized(jobLock) {
            if (inFlightJob === run) {
                inFlightJob = null
                _state.value = OtgState.Idle
            }
        }
    }

    /**
     * Enqueues a verified staged copy. Returns null on success, or the
     * user-facing reason it was skipped; the staged copy is then deleted
     * so nothing unqueued lingers in staging.
     */
    private fun enqueueStaged(stagedFile: File, candidate: OtgMediaCandidate): String? {
        val id = try {
            enqueuer.enqueue(stagedFile, candidate)
        } catch (ce: CancellationException) {
            stagedFile.delete()
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "enqueue failed for ${candidate.fileName}", e)
            0L
        }
        if (id > 0L) return null
        stagedFile.delete()
        return text(R.string.otg_error_enqueue_failed, ENQUEUE_FAILED)
    }

    /**
     * A user-facing message from strings.xml, formatted with [args]. JVM
     * unit tests build the manager without a Context; they get the
     * English [fallback], formatted with the same [args], which must be
     * the resource's own text (OtgIngestManagerFallbackTest pins that).
     */
    private fun text(@StringRes id: Int, fallback: String, vararg args: Any): String =
        context?.getString(id, *args)
            ?: if (args.isEmpty()) fallback else String.format(Locale.ROOT, fallback, *args)

    private fun defaultSessionDir(): File {
        val root = context?.let { OtgStaging.root(it) }
            ?: File(System.getProperty("java.io.tmpdir"), "otg_staging")
        return OtgStaging.newSessionDir(root)
    }

    /**
     * Internal sealed result of [copyCandidateToStage]. Surfaces the
     * per-file error strings (size cap exceeded, mid-stream copy failure)
     * to [confirmImport] without throwing — a failure on one file must
     * not abort the rest of the scan.
     */
    private sealed class StageResult {
        data class Success(val stagedFile: File) : StageResult()
        data class Failure(val message: String) : StageResult()
    }

    /**
     * Copies a candidate media file from the SD card (or SAF DocumentTree)
     * into [stageDir]. Verifies the copy produced exactly the bytes the
     * candidate's filesystem metadata claimed, then computes a fresh
     * BLAKE3-256 over the staged file via the Go engine. Returns a
     * [StageResult.Success] when all three checks pass; otherwise a
     * [StageResult.Failure] carrying a user-facing reason. The staged
     * file is deleted on any failure so a subsequent ingest pass doesn't
     * try to upload a corrupt partial.
     */
    private fun copyCandidateToStage(candidate: OtgMediaCandidate, stageDir: File): StageResult {
        if (candidate.sizeBytes > MAX_OTG_STAGE_BYTES) {
            return StageResult.Failure(
                "File too large to ingest (${OtgMediaCandidate.formatBytes(candidate.sizeBytes)} > ${OtgMediaCandidate.formatBytes(MAX_OTG_STAGE_BYTES)} cap)"
            )
        }
        if (candidate.sizeBytes < 0) {
            return StageResult.Failure("Skipped file with invalid size metadata")
        }

        val targetFile = OtgStaging.stagedFile(stageDir, candidate.relativePath)
            ?: return StageResult.Failure(
                text(R.string.otg_error_unsafe_path, UNSAFE_PATH, candidate.relativePath),
            )
        val partFile = OtgStaging.partFileFor(targetFile)
        targetFile.parentFile?.mkdirs()

        try {
            val input = when {
                candidate.uri.startsWith("content://") && context != null ->
                    context.contentResolver.openInputStream(Uri.parse(candidate.uri))
                        ?: return StageResult.Failure("Could not open stream for content URI: ${candidate.uri}")
                candidate.uri.startsWith("file:/") -> File(java.net.URI(candidate.uri)).inputStream()
                else -> File(candidate.uri).inputStream()
            }
            // Copy to a .part file, flush it to disk, then rename: a crash
            // mid-copy never leaves a truncated file under the final name.
            val copiedBytes = input.use { stream ->
                FileOutputStream(partFile).use { output ->
                    val n = stream.copyTo(output, COPY_BUFFER)
                    output.fd.sync()
                    n
                }
            }

            if (copiedBytes != candidate.sizeBytes) {
                partFile.delete()
                return StageResult.Failure(
                    "Copy failed mid-stream; SD card may be failing (got ${OtgMediaCandidate.formatBytes(copiedBytes)}, expected ${OtgMediaCandidate.formatBytes(candidate.sizeBytes)})"
                )
            }
            if (!partFile.renameTo(targetFile)) {
                partFile.delete()
                return StageResult.Failure(
                    text(R.string.otg_error_finish_copy_failed, FINISH_COPY_FAILED),
                )
            }
            if (targetFile.length() != candidate.sizeBytes) {
                targetFile.delete()
                return StageResult.Failure(
                    "Staged file size mismatch (got ${OtgMediaCandidate.formatBytes(targetFile.length())}, expected ${OtgMediaCandidate.formatBytes(candidate.sizeBytes)})"
                )
            }

            val (freshHash, priorHash) = hashProvider.lookup(candidate.localId, targetFile)
            if (freshHash == null) {
                // Engine unavailable / hash skipped — fall through; the
                // upload side will compute the canonical hash later.
                Log.w(TAG, "BLAKE3 verify skipped for ${candidate.fileName}: engine unavailable")
                return StageResult.Success(targetFile)
            }

            hashObserver?.invoke(candidate, freshHash, priorHash)

            if (priorHash.isNotEmpty() && priorHash != freshHash) {
                Log.w(
                    TAG,
                    "BLAKE3 mismatch for ${candidate.fileName} (localID=${candidate.localId}): " +
                        "prior=$priorHash fresh=$freshHash — SD card may be returning inconsistent bytes"
                )
                // T2-7b: the audit's whole point was that a corrupt SD
                // card returning inconsistent bytes for the same file
                // must not silently proceed to the uploader. Delete the
                // staged copy and surface a per-file error so the
                // confirmation dialog can show the user which file was
                // skipped and why. (PR #91 only logged the mismatch —
                // the staged file was still handed to the uploader.)
                targetFile.delete()
                return StageResult.Failure(
                    "BLAKE3 mismatch: ${candidate.fileName} re-hashed to $freshHash " +
                        "but the queue has $priorHash for the same localID — " +
                        "SD card may be returning inconsistent bytes"
                )
            }

            return StageResult.Success(targetFile)
        } catch (ce: CancellationException) {
            partFile.delete()
            targetFile.delete()
            throw ce
        } catch (e: Exception) {
            partFile.delete()
            targetFile.delete()
            return StageResult.Failure(
                "Copy failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    companion object {
        private const val TAG = "OtgIngestManager"
        private const val COPY_BUFFER = 64 * 1024

        // English text of the R.string.otg_error_* resources, for a
        // manager without a Context. OtgIngestManagerFallbackTest checks
        // each one against its resource.

        /** R.string.otg_error_no_media_in_folder. */
        internal const val NO_MEDIA_IN_FOLDER =
            "No photos or videos were found in the selected folder. Choose the card's root or its DCIM folder."

        /** R.string.otg_error_enqueue_failed. */
        internal const val ENQUEUE_FAILED = "Could not add to the upload queue"

        /** R.string.otg_error_unsafe_path; its one argument is the file's path on the card. */
        internal const val UNSAFE_PATH = "Skipped file with an unsafe path: %1\$s"

        /** R.string.otg_error_finish_copy_failed. */
        internal const val FINISH_COPY_FAILED = "Could not finish the staged copy"

        /**
         * Per-file size cap for staged copies. Chosen to comfortably
         * exceed the largest plausible single capture (a 4K ProRes RAW
         * clip tops out around 30 GB/hour) while still refusing obviously
         * bogus values from a corrupt SD card metadata read. Surfaced as
         * "File too large to ingest" in the post-pass summary when hit.
         */
        const val MAX_OTG_STAGE_BYTES: Long = 50L * 1024L * 1024L * 1024L

        @Volatile
        private var instance: OtgIngestManager? = null

        fun getInstance(context: Context): OtgIngestManager {
            return instance ?: synchronized(this) {
                instance ?: OtgIngestManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
