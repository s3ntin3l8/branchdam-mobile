package com.branchdam.mobile.otg

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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

sealed class OtgState {
    object Idle : OtgState()
    data class Scanning(val deviceLabel: String) : OtgState()
    data class AwaitingConfirmation(val scanResult: OtgScanResult) : OtgState()
    data class Ingesting(val progress: OtgIngestProgress) : OtgState()
    data class Completed(val importedCount: Int, val totalBytes: Long) : OtgState()
    data class Error(val message: String) : OtgState()
}

class OtgIngestManager(
    private val context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val _state = MutableStateFlow<OtgState>(OtgState.Idle)
    val state: StateFlow<OtgState> = _state.asStateFlow()

    /**
     * Called when a USB mass storage / SD card is attached or selected via SAF.
     * Starts background scanning and transitions to AwaitingConfirmation upon completion.
     */
    fun onCardDetected(deviceLabel: String, directory: File) {
        scope.launch {
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
        scope.launch {
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
        scope.launch {
            val candidates = scanResult.candidates
            val totalBytes = scanResult.totalSizeBytes
            var bytesProcessed = 0L
            var importedCount = 0

            if (!destinationDir.exists()) {
                destinationDir.mkdirs()
            }

            for ((index, candidate) in candidates.withIndex()) {
                _state.value = OtgState.Ingesting(
                    OtgIngestProgress(
                        currentFileIndex = index + 1,
                        totalFiles = candidates.size,
                        currentFileName = candidate.fileName,
                        bytesProcessed = bytesProcessed,
                        totalBytes = totalBytes
                    )
                )

                val stagedFile = withContext(ioDispatcher) {
                    copyCandidateToStage(candidate, destinationDir)
                }

                if (stagedFile != null && stagedFile.exists()) {
                    bytesProcessed += candidate.sizeBytes
                    importedCount++
                    onFileStaged(stagedFile, candidate)
                }
            }

            _state.value = OtgState.Completed(
                importedCount = importedCount,
                totalBytes = bytesProcessed
            )
        }
    }

    /**
     * Human cancellation hook to reject or skip the ingest.
     */
    fun cancelImport() {
        _state.value = OtgState.Idle
    }

    /**
     * Resets state back to Idle after viewing completion or error.
     */
    fun reset() {
        _state.value = OtgState.Idle
    }

    private fun copyCandidateToStage(candidate: OtgMediaCandidate, stageDir: File): File? {
        val targetFile = File(stageDir, candidate.fileName)
        return try {
            if (candidate.uri.startsWith("content://") && context != null) {
                val uri = Uri.parse(candidate.uri)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } else if (candidate.uri.startsWith("file:/")) {
                val sourceFile = File(java.net.URI(candidate.uri))
                sourceFile.copyTo(targetFile, overwrite = true)
            } else {
                val sourceFile = File(candidate.uri)
                sourceFile.copyTo(targetFile, overwrite = true)
            }
            targetFile
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        @Volatile
        private var instance: OtgIngestManager? = null

        fun getInstance(context: Context): OtgIngestManager {
            return instance ?: synchronized(this) {
                instance ?: OtgIngestManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
