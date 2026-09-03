package com.branchdam.mobile.otg

import android.content.Context
import android.net.Uri
import android.util.Log
import com.branchdam.mobile.EngineHolder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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

class OtgIngestManager(
    private val context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val hashObserver: OtgHashObserver? = null,
) {
    private val _state = MutableStateFlow<OtgState>(OtgState.Idle)
    val state: StateFlow<OtgState> = _state.asStateFlow()

    private var inFlightJob: Job? = null

    /**
     * Called when a USB mass storage / SD card is attached or selected via SAF.
     * Starts background scanning and transitions to AwaitingConfirmation upon completion.
     */
    fun onCardDetected(deviceLabel: String, directory: File) {
        inFlightJob?.cancel()
        inFlightJob = scope.launch {
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
     * Called when a SAF DocumentTree URI is provided by the user.
     */
    fun onDocumentTreeSelected(treeUri: Uri, deviceLabel: String = "SD Card") {
        inFlightJob?.cancel()
        inFlightJob = scope.launch {
            _state.value = OtgState.Scanning(deviceLabel)
            val result = withContext(ioDispatcher) {
                if (context != null) {
                    OtgCardScanner.scanDocumentTree(context, treeUri, deviceLabel)
                } else {
                    OtgScanResult(deviceLabel, treeUri.toString(), emptyList())
                }
            }
            if (result.candidates.isNotEmpty()) {
                _state.value = OtgState.AwaitingConfirmation(result)
            } else {
                _state.value = OtgState.Idle
            }
        }
    }

    /**
     * Human confirmation hook to proceed with the ingest.
     */
    fun confirmImport(
        scanResult: OtgScanResult,
        destinationDir: File = context?.filesDir?.let { File(it, "otg_stage") } ?: File(System.getProperty("java.io.tmpdir"), "otg_stage"),
        onFileStaged: (file: File, candidate: OtgMediaCandidate) -> Unit = { _, _ -> }
    ) {
        inFlightJob?.cancel()
        inFlightJob = scope.launch {
            val candidates = scanResult.candidates
            val totalBytes = scanResult.totalSizeBytes
            var bytesProcessed = 0L
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

                    when (stageResult) {
                        is StageResult.Success -> {
                            bytesProcessed += candidate.sizeBytes
                            importedCount++
                            onFileStaged(stageResult.stagedFile, candidate)
                        }
                        is StageResult.Failure -> {
                            fileErrors += OtgIngestFileError(candidate, stageResult.message)
                        }
                    }
                }

                if (isActive) {
                    _state.value = OtgState.Completed(
                        importedCount = importedCount,
                        totalBytes = bytesProcessed,
                        fileErrors = fileErrors,
                    )
                }
            } catch (ce: CancellationException) {
                // Job was cancelled by user
                _state.value = OtgState.Idle
            } catch (e: Exception) {
                _state.value = OtgState.Error("Failed to import SD card media: ${e.message ?: "Unknown error"}")
            }
        }
    }

    /**
     * Human cancellation hook to reject or skip the ingest.
     */
    fun cancelImport() {
        inFlightJob?.cancel()
        inFlightJob = null
        _state.value = OtgState.Idle
    }

    /**
     * Resets state back to Idle after viewing completion or error.
     */
    fun reset() {
        inFlightJob?.cancel()
        inFlightJob = null
        _state.value = OtgState.Idle
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

        val targetFile = File(stageDir, candidate.relativePath)
        targetFile.parentFile?.mkdirs()

        try {
            val copiedBytes = when {
                candidate.uri.startsWith("content://") && context != null -> {
                    val uri = Uri.parse(candidate.uri)
                    val input = context.contentResolver.openInputStream(uri)
                        ?: return StageResult.Failure("Could not open stream for content URI: ${candidate.uri}")
                    input.use { stream ->
                        FileOutputStream(targetFile).use { output ->
                            stream.copyTo(output)
                        }
                    }
                }
                candidate.uri.startsWith("file:/") -> {
                    val sourceFile = File(java.net.URI(candidate.uri))
                    sourceFile.inputStream().use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                else -> {
                    val sourceFile = File(candidate.uri)
                    sourceFile.inputStream().use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }

            if (copiedBytes != candidate.sizeBytes) {
                targetFile.delete()
                return StageResult.Failure(
                    "Copy failed mid-stream; SD card may be failing (got ${OtgMediaCandidate.formatBytes(copiedBytes)}, expected ${OtgMediaCandidate.formatBytes(candidate.sizeBytes)})"
                )
            }
            if (targetFile.length() != candidate.sizeBytes) {
                targetFile.delete()
                return StageResult.Failure(
                    "Staged file size mismatch (got ${OtgMediaCandidate.formatBytes(targetFile.length())}, expected ${OtgMediaCandidate.formatBytes(candidate.sizeBytes)})"
                )
            }

            val priorHash = EngineHolder.lookupBlake3ForLocalID(candidate.uri)
            val freshHash = EngineHolder.computeBlake3Hex(targetFile.absolutePath)
            if (freshHash == null) {
                // Engine unavailable / hash skipped — fall through; the
                // upload side will compute the canonical hash later.
                Log.w(TAG, "BLAKE3 verify skipped for ${candidate.fileName}: engine unavailable")
            } else {
                hashObserver?.invoke(candidate, freshHash, priorHash)
                if (priorHash.isNotEmpty() && priorHash != freshHash) {
                    Log.w(
                        TAG,
                        "BLAKE3 mismatch for ${candidate.fileName} (localID=${candidate.uri}): " +
                            "prior=$priorHash fresh=$freshHash — SD card may be returning inconsistent bytes"
                    )
                }
            }

            return StageResult.Success(targetFile)
        } catch (ce: CancellationException) {
            targetFile.delete()
            throw ce
        } catch (e: Exception) {
            targetFile.delete()
            return StageResult.Failure(
                "Copy failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    companion object {
        private const val TAG = "OtgIngestManager"

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
