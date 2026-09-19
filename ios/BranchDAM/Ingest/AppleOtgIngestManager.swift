import Foundation
import Combine
import os

public struct AppleOtgIngestProgress: Equatable, Sendable {
    public let currentFileIndex: Int
    public let totalFiles: Int
    public let currentFileName: String
    public let bytesProcessed: Int64
    public let totalBytes: Int64

    public init(
        currentFileIndex: Int,
        totalFiles: Int,
        currentFileName: String,
        bytesProcessed: Int64,
        totalBytes: Int64
    ) {
        self.currentFileIndex = currentFileIndex
        self.totalFiles = totalFiles
        self.currentFileName = currentFileName
        self.bytesProcessed = bytesProcessed
        self.totalBytes = totalBytes
    }

    public var percentage: Double {
        guard totalBytes > 0 else { return 0.0 }
        return min(max(Double(bytesProcessed) / Double(totalBytes), 0.0), 1.0)
    }
}

public enum AppleOtgState: Equatable, Sendable {
    case idle
    case scanning(label: String)
    case awaitingConfirmation(scanResult: AppleOtgScanResult)
    case ingesting(progress: AppleOtgIngestProgress)
    case completed(importedCount: Int, totalBytes: Int64)
    case error(message: String)
}

/// OTG card ingest orchestrator.
@MainActor
public class AppleOtgIngestManager: ObservableObject {
    public static let shared = AppleOtgIngestManager()

    @Published public var state: AppleOtgState = .idle

    private let isCancelledFlag = OSAllocatedUnfairLock<Bool>(initialState: false)
    private let queue = DispatchQueue(label: "com.branchdam.mobile.otg", qos: .userInitiated)

    public init() {}

    public func onCardDetected(deviceLabel: String, directory: URL) {
        isCancelledFlag.withLock { $0 = false }
        state = .scanning(label: deviceLabel)

        queue.async { [weak self] in
            let result = AppleOtgCardScanner.scanDirectory(at: directory, deviceLabel: deviceLabel)
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                if !self.isCancelledFlag.withLock({ $0 }) {
                    if !result.candidates.isEmpty {
                        self.state = .awaitingConfirmation(scanResult: result)
                    } else {
                        self.state = .idle
                    }
                }
            }
        }
    }

    public func confirmImport(
        scanResult: AppleOtgScanResult,
        stageDirectory: URL? = nil,
        onFileStaged: (@Sendable (URL, AppleOtgCandidate) -> Void)? = nil
    ) {
        isCancelledFlag.withLock { $0 = false }
        let destinationDir = stageDirectory ?? FileManager.default.temporaryDirectory.appendingPathComponent("otg_stage", isDirectory: true)

        let candidates = scanResult.candidates
        let totalBytes = scanResult.totalSizeBytes

        try? FileManager.default.createDirectory(at: destinationDir, withIntermediateDirectories: true)

        queue.async { [weak self] in
            var bytesProcessed: Int64 = 0
            var importedCount = 0

            for (index, candidate) in candidates.enumerated() {
                let cancelled = self?.isCancelledFlag.withLock({ $0 }) ?? true
                if cancelled { break }

                let currentBytes = bytesProcessed
                DispatchQueue.main.async { [weak self] in
                    self?.state = .ingesting(
                        progress: AppleOtgIngestProgress(
                            currentFileIndex: index + 1,
                            totalFiles: candidates.count,
                            currentFileName: candidate.fileName,
                            bytesProcessed: currentBytes,
                            totalBytes: totalBytes
                        )
                    )
                }

                do {
                    let targetURL = destinationDir.appendingPathComponent(candidate.relativePath)
                    try FileManager.default.createDirectory(at: targetURL.deletingLastPathComponent(), withIntermediateDirectories: true)

                    if FileManager.default.fileExists(atPath: targetURL.path) {
                        try? FileManager.default.removeItem(at: targetURL)
                    }
                    try FileManager.default.copyItem(at: candidate.url, to: targetURL)

                    // Enqueue into core engine
                    _ = BranchDamCoreBridge.shared.enqueueMedia(
                        localPath: targetURL.path,
                        filename: candidate.fileName,
                        capturedAtUnix: candidate.lastModifiedUnix,
                        localID: candidate.url.absoluteString
                    )

                    bytesProcessed += candidate.sizeBytes
                    importedCount += 1
                    onFileStaged?(targetURL, candidate)
                } catch {
                    DispatchQueue.main.async { [weak self] in
                        self?.state = .error(message: "Failed to copy \(candidate.relativePath): \(error.localizedDescription)")
                    }
                    return
                }
            }

            let finalBytes = bytesProcessed
            let finalImportedCount = importedCount
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                if !self.isCancelledFlag.withLock({ $0 }) {
                    self.state = .completed(importedCount: finalImportedCount, totalBytes: finalBytes)
                } else {
                    self.state = .idle
                }
            }
        }
    }

    public func cancelImport() {
        isCancelledFlag.withLock { $0 = true }
        state = .idle
    }

    public func reset() {
        isCancelledFlag.withLock { $0 = true }
        state = .idle
    }
}
