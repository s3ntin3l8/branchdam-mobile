package com.branchdam.mobile.otg

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

object OtgCardScanner {

    /**
     * Recursively scans an Android Storage Access Framework (SAF) DocumentFile tree.
     */
    fun scanDocumentTree(
        context: Context,
        treeUri: Uri,
        deviceLabel: String = "SD Card"
    ): OtgScanResult {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: return OtgScanResult(deviceLabel = deviceLabel, rootUri = treeUri.toString(), candidates = emptyList())

        val candidates = mutableListOf<OtgMediaCandidate>()
        scanDocumentFileRecursive(rootDoc, "", candidates)

        return OtgScanResult(
            deviceLabel = deviceLabel,
            rootUri = treeUri.toString(),
            candidates = candidates
        )
    }

    private fun scanDocumentFileRecursive(
        dir: DocumentFile,
        currentPath: String,
        results: MutableList<OtgMediaCandidate>
    ) {
        val files = dir.listFiles()
        for (file in files) {
            val fileName = file.name ?: continue
            if (fileName.startsWith(".")) continue

            val relativePath = if (currentPath.isEmpty()) fileName else "$currentPath/$fileName"
            if (file.isDirectory) {
                scanDocumentFileRecursive(file, relativePath, results)
            } else if (OtgMediaCandidate.isSupportedMedia(fileName)) {
                results.add(
                    OtgMediaCandidate(
                        uri = file.uri.toString(),
                        relativePath = relativePath,
                        fileName = fileName,
                        sizeBytes = file.length(),
                        lastModifiedUnix = file.lastModified() / 1000L,
                        isRaw = OtgMediaCandidate.isRawExtension(fileName),
                        isVideo = OtgMediaCandidate.isVideoExtension(fileName)
                    )
                )
            }
        }
    }

    /**
     * Recursively scans a filesystem directory path (e.g. /storage/xxxx-xxxx/DCIM).
     */
    fun scanDirectory(
        directory: File,
        deviceLabel: String = "SD Card"
    ): OtgScanResult {
        if (!directory.exists() || !directory.isDirectory) {
            return OtgScanResult(deviceLabel = deviceLabel, rootUri = directory.absolutePath, candidates = emptyList())
        }

        val candidates = mutableListOf<OtgMediaCandidate>()
        scanFileRecursive(directory, directory, candidates)

        return OtgScanResult(
            deviceLabel = deviceLabel,
            rootUri = directory.absolutePath,
            candidates = candidates
        )
    }

    private fun scanFileRecursive(
        root: File,
        current: File,
        results: MutableList<OtgMediaCandidate>
    ) {
        val files = current.listFiles() ?: return
        for (file in files) {
            val fileName = file.name
            if (fileName.startsWith(".")) continue

            if (file.isDirectory) {
                scanFileRecursive(root, file, results)
            } else if (OtgMediaCandidate.isSupportedMedia(fileName)) {
                val relativePath = file.relativeTo(root).path
                results.add(
                    OtgMediaCandidate(
                        uri = file.toURI().toString(),
                        relativePath = relativePath,
                        fileName = fileName,
                        sizeBytes = file.length(),
                        lastModifiedUnix = file.lastModified() / 1000L,
                        isRaw = OtgMediaCandidate.isRawExtension(fileName),
                        isVideo = OtgMediaCandidate.isVideoExtension(fileName)
                    )
                )
            }
        }
    }
}
