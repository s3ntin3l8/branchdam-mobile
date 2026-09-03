import Foundation
import Combine
import Synchronization

public struct AppleOtgIngestProgress: Equatable {
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

public enum AppleOtgState: Equatable {
    case idle
    case scanning(label: String)
    case awaitingConfirmation(scanResult: AppleOtgScanResult)
    case ingesting(progress: AppleOtgIngestProgress)
    case completed(importedCount: Int, totalBytes: Int64)
    case error(message: String)
}

/// OTG card ingest orchestrator.
///
/// Thread-safety model (T2-9 hardening):
/// - `state` is published via `@Published`. The Combine runtime
///   internally synchronizes `_send` so concurrent mutations from
///   the OTG queue and the main thread are observed atomically by
///   subscribers; reads of `state` from any thread return the latest
///   committed value. Swift 6 strict concurrency accepts this for
///   the `AppleOtgState` payload because every associated value is
///   `Sendable` (`URL`, `String`, `Int`, `Int64`, the nested
///   `AppleOtgScanResult`/`AppleOtgIngestProgress` structs).
/// - `isCancelled` is the cross-thread flag the Swift 6 strict
///   concurrency checker flagged as a data race when it was a plain
///   `var Bool`: written from `cancelImport()`/`reset()` on the
///   main thread and from `onCardDetected`/`confirmImport` on the
///   main thread, but read from `confirmImport`'s OTG-queue block
///   on a background thread. It is now an `Atomic<Bool>` from the
///   Swift 5.9 `Synchronization` framework, which provides lock-free
///   atomic load/store and is itself `Sendable`. The deployment
///   target is iOS 17, the floor at which `Synchronization` ships.
public class AppleOtgIngestManager: ObservableObject {
    public static let shared = AppleOtgIngestManager()

    @Published public var state: AppleOtgState = .idle

    private let isCancelledFlag = Atomic<Bool>(false)
    private let queue = DispatchQueue(label: "com.branchdam.mobile.otg", qos: .userInitiated)

    public init() {}

    public func onCardDetected(deviceLabel: String, directory: URL) {
        isCancelledFlag.store(false, ordering: .relaxed)
        state = .scanning(label: deviceLabel)

        queue.async { [weak self] in
            guard let self = self else { return }
            let result = AppleOtgCardScanner.scanDirectory(at: directory, deviceLabel: deviceLabel)
            DispatchQueue.main.async {
                if !self.isCancelledFlag.load(ordering: .relaxed) {
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
        onFileStaged: ((URL, AppleOtgCandidate) -> Void)? = nil
    ) {
        isCancelledFlag.store(false, ordering: .relaxed)
        let destinationDir = stageDirectory ?? FileManager.default.temporaryDirectory.appendingPathComponent("otg_stage", isDirectory: true)

        let candidates = scanResult.candidates
        let totalBytes = scanResult.totalSizeBytes

        try? FileManager.default.createDirectory(at: destinationDir, withIntermediateDirectories: true)

        queue.async { [weak self] in
            guard let self = self else { return }
            var bytesProcessed: Int64 = 0
            var importedCount = 0

            for (index, candidate) in candidates.enumerated() {
                if self.isCancelledFlag.load(ordering: .relaxed) { break }

                DispatchQueue.main.async {
                    self.state = .ingesting(
                        progress: AppleOtgIngestProgress(
                            currentFileIndex: index + 1,
                            totalFiles: candidates.count,
                            currentFileName: candidate.fileName,
                            bytesProcessed: bytesProcessed,
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
                    DispatchQueue.main.async {
                        self.state = .error(message: "Failed to copy \(candidate.relativePath): \(error.localizedDescription)")
                    }
                    return
                }
            }

            DispatchQueue.main.async {
                if !self.isCancelledFlag.load(ordering: .relaxed) {
                    self.state = .completed(importedCount: importedCount, totalBytes: bytesProcessed)
                } else {
                    self.state = .idle
                }
            }
        }
    }

    public func cancelImport() {
        isCancelledFlag.store(true, ordering: .relaxed)
        state = .idle
    }

    public func reset() {
        isCancelledFlag.store(true, ordering: .relaxed)
        state = .idle
    }
}
