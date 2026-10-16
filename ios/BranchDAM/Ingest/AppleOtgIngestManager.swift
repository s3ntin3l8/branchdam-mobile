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

/// One file skipped during an OTG import, with the reason shown to the user.
public struct AppleOtgFileFailure: Equatable, Sendable {
    public let fileName: String
    public let relativePath: String
    public let message: String

    public init(fileName: String, relativePath: String, message: String) {
        self.fileName = fileName
        self.relativePath = relativePath
        self.message = message
    }
}

public enum AppleOtgState: Equatable, Sendable {
    case idle
    case scanning(label: String)
    case awaitingConfirmation(scanResult: AppleOtgScanResult)
    case ingesting(progress: AppleOtgIngestProgress)
    case completed(importedCount: Int, totalBytes: Int64, failures: [AppleOtgFileFailure])
    case error(message: String)
}

/// The engine calls an OTG import makes. Production goes through
/// `BranchDamCoreBridge`; tests inject a fake to force hash mismatches
/// and enqueue refusals without the Go framework.
public protocol AppleOtgIngestEngine: Sendable {
    /// BLAKE3-256 hex of the file, or "" when the engine cannot hash it.
    func computeHashes(localPath: String) -> String
    /// The BLAKE3 hash last recorded for `localID`, or "" if none.
    func lookupBlake3ForLocalID(localID: String) -> String
    /// Queue ID (> 0), or 0 when the engine refused the file.
    func enqueueMedia(localPath: String, filename: String, capturedAtUnix: Int64, localID: String) -> Int64
    /// Upload status by local path (see `getAllMediaStatuses`); empty when unknown.
    func mediaStatuses() -> [String: String]
}

public struct BridgeOtgIngestEngine: AppleOtgIngestEngine {
    public init() {}

    /// The app opens the engine at launch only when camera-roll access is
    /// granted, but a card import needs no Photos access. Open it from the
    /// stored pairing on first use (a no-op once open). Callers run on the
    /// OTG manager's background queue, never on the main actor.
    private var bridge: BranchDamCoreBridge {
        let bridge = BranchDamCoreBridge.shared
        bridge.startEngineIfNeeded()
        return bridge
    }

    public func computeHashes(localPath: String) -> String {
        bridge.computeHashes(localPath: localPath)
    }

    public func lookupBlake3ForLocalID(localID: String) -> String {
        bridge.lookupBlake3ForLocalID(localID: localID)
    }

    public func enqueueMedia(localPath: String, filename: String, capturedAtUnix: Int64, localID: String) -> Int64 {
        bridge.enqueueMedia(
            localPath: localPath,
            filename: filename,
            capturedAtUnix: capturedAtUnix,
            localID: localID
        )
    }

    public func mediaStatuses() -> [String: String] {
        bridge.getAllMediaStatuses()
    }
}

/// Where OTG card copies wait for upload, and when they may go.
///
/// Each confirmed import stages into its own session directory under
/// Application Support/otg_staging. Application Support is never purged by
/// the system (unlike tmp and Caches), and the directory is excluded from
/// iCloud backup. A copy is deleted only once the engine reports its upload
/// COMPLETED (or OFFLOADED): until then it may be the only copy left once
/// the card has been reformatted.
public enum AppleOtgStaging {
    static let directoryName = "otg_staging"
    static let partSuffix = ".part"
    /// An interrupted copy (or an empty session directory) is abandoned after this long.
    static let partGrace: TimeInterval = 60 * 60
    /// A finished copy no upload row references is removed after this long.
    static let orphanGrace: TimeInterval = 7 * 24 * 60 * 60

    public static func defaultRoot() -> URL {
        let base = (try? FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )) ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent(directoryName, isDirectory: true)
    }

    /// Creates a fresh session directory under `root` (and `root` itself,
    /// excluded from backup).
    public static func newSessionDirectory(in root: URL) throws -> URL {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var rootURL = root
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? rootURL.setResourceValues(values)
        let session = root.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: session, withIntermediateDirectories: true)
        return session
    }

    /// The staged location for `relativePath` inside `session`, or nil when
    /// the path would escape it (absolute, empty, or `.`/`..` segments).
    public static func stagedURL(in session: URL, relativePath: String) -> URL? {
        guard !relativePath.isEmpty, !relativePath.hasPrefix("/") else { return nil }
        let segments = relativePath.split(separator: "/", omittingEmptySubsequences: false)
        guard !segments.contains(where: { $0.isEmpty || $0 == "." || $0 == ".." }) else { return nil }
        return session.appendingPathComponent(relativePath)
    }

    public enum Outcome: Equatable, Sendable {
        case staged(URL)
        case failed(String)
    }

    /// Copies one candidate into `session`, verifies it and enqueues it.
    /// Any failure deletes the partial or rejected copy and returns the
    /// reason; it never throws, so one bad file cannot stop the card.
    public static func stage(
        _ candidate: AppleOtgCandidate,
        in session: URL,
        engine: any AppleOtgIngestEngine
    ) -> Outcome {
        let fm = FileManager.default
        guard candidate.sizeBytes >= 0 else {
            return .failed(String(localized: "Skipped a file with invalid size metadata"))
        }
        guard let target = stagedURL(in: session, relativePath: candidate.relativePath) else {
            return .failed(String(localized: "Skipped a file with an unsafe path"))
        }
        let part = target.deletingLastPathComponent()
            .appendingPathComponent(target.lastPathComponent + partSuffix)

        do {
            try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? fm.removeItem(at: part)
            try fm.copyItem(at: candidate.url, to: part)
            let handle = try FileHandle(forWritingTo: part)
            try handle.synchronize()
            try handle.close()

            let copied = (try fm.attributesOfItem(atPath: part.path)[.size] as? NSNumber)?.int64Value ?? -1
            guard copied == candidate.sizeBytes else {
                try? fm.removeItem(at: part)
                let got = AppleOtgCandidate.formatBytes(copied)
                let expected = AppleOtgCandidate.formatBytes(candidate.sizeBytes)
                return .failed(String(localized: "Copy incomplete; the card may be failing (got \(got), expected \(expected))"))
            }
            if fm.fileExists(atPath: target.path) {
                try fm.removeItem(at: target)
            }
            try fm.moveItem(at: part, to: target)
        } catch {
            try? fm.removeItem(at: part)
            try? fm.removeItem(at: target)
            return .failed(String(localized: "Copy failed: \(error.localizedDescription)"))
        }

        // Re-hash the staged bytes. A different hash for the same card file
        // (same path, size and mtime) means the card returned different
        // bytes than last time: refuse it rather than upload either copy.
        let fresh = engine.computeHashes(localPath: target.path)
        let prior = engine.lookupBlake3ForLocalID(localID: candidate.localID)
        if !prior.isEmpty && !fresh.isEmpty && fresh != prior {
            try? fm.removeItem(at: target)
            return .failed(String(localized: "BLAKE3 mismatch: the card returned different bytes for this file than on an earlier import. It may be failing."))
        }

        let id = engine.enqueueMedia(
            localPath: target.path,
            filename: candidate.fileName,
            capturedAtUnix: candidate.lastModifiedUnix,
            localID: candidate.localID
        )
        guard id > 0 else {
            try? fm.removeItem(at: target)
            return .failed(String(localized: "Could not add to the upload queue"))
        }
        return .staged(target)
    }

    /// Pure decision for `sweep`. `engineAnswered` is false when the status
    /// map came back empty, which is also what an unavailable engine
    /// returns: an unknown file is then kept.
    static func shouldDelete(
        fileName: String,
        status: String?,
        age: TimeInterval,
        engineAnswered: Bool
    ) -> Bool {
        if fileName.hasSuffix(partSuffix) { return age > partGrace }
        switch status {
        case "COMPLETED"?, "OFFLOADED"?:
            return true
        case nil, "NOT_ENQUEUED"?:
            return engineAnswered && age > orphanGrace
        default:
            // PENDING / IN_PROGRESS / FAILED: the upload still needs the bytes.
            return false
        }
    }

    /// The part of `path` after the staging directory, used to match a
    /// staged file to its queue row even when the app container path
    /// changed (it does across app updates) or differs by a /private prefix.
    static func stagingKey(_ path: String) -> String? {
        guard let range = path.range(of: "/\(directoryName)/") else { return nil }
        return String(path[range.upperBound...])
    }

    /// Deletes the staged copies under `root` that `shouldDelete` allows,
    /// then old empty session directories. Returns the number of files removed.
    @discardableResult
    public static func sweep(root: URL, statuses: [String: String], now: Date = Date()) -> Int {
        let fm = FileManager.default
        guard let enumerator = fm.enumerator(
            at: root,
            includingPropertiesForKeys: [.isRegularFileKey, .isDirectoryKey, .contentModificationDateKey]
        ) else { return 0 }

        var byKey: [String: String] = [:]
        for (path, status) in statuses {
            if let key = stagingKey(path) { byKey[key] = status }
        }
        let engineAnswered = !statuses.isEmpty

        var removed = 0
        var directories: [URL] = []
        for case let url as URL in enumerator {
            let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .isDirectoryKey, .contentModificationDateKey])
            if values?.isDirectory == true {
                directories.append(url)
                continue
            }
            guard values?.isRegularFile == true else { continue }
            let age = now.timeIntervalSince(values?.contentModificationDate ?? now)
            let status = statuses[url.path] ?? stagingKey(url.path).flatMap { byKey[$0] }
            if shouldDelete(fileName: url.lastPathComponent, status: status, age: age, engineAnswered: engineAnswered),
               (try? fm.removeItem(at: url)) != nil {
                removed += 1
            }
        }
        // Deepest first. Deleting a child bumps its parent's mtime, so a
        // parent emptied in this pass goes on a later one.
        for dir in directories.sorted(by: { $0.path.count > $1.path.count }) {
            let modified = (try? dir.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? now
            if now.timeIntervalSince(modified) > partGrace,
               let contents = try? fm.contentsOfDirectory(atPath: dir.path), contents.isEmpty {
                try? fm.removeItem(at: dir)
            }
        }
        return removed
    }
}

/// OTG card ingest orchestrator.
@MainActor
public class AppleOtgIngestManager: ObservableObject {
    public static let shared = AppleOtgIngestManager()

    @Published public var state: AppleOtgState = .idle

    /// Bumped by every scan, import, cancel and reset. A background run
    /// keeps going only while the generation it started with is current,
    /// so a cancelled run cannot be revived by the next one starting.
    private let runGeneration = OSAllocatedUnfairLock<Int>(initialState: 0)
    private let queue = DispatchQueue(label: "com.branchdam.mobile.otg", qos: .userInitiated)
    private let engine: any AppleOtgIngestEngine
    private let stagingRoot: URL
    /// The folder picked in Files, held open (security-scoped) from the
    /// scan until the import finishes or is dismissed.
    private var scopedCardURL: URL?
    /// Bumped whenever card access is taken or released, so a finishing
    /// run only releases the access it started with.
    private var cardAccessGeneration = 0

    public init(
        engine: any AppleOtgIngestEngine = BridgeOtgIngestEngine(),
        stagingRoot: URL = AppleOtgStaging.defaultRoot()
    ) {
        self.engine = engine
        self.stagingRoot = stagingRoot
    }

    /// Entry point for a card folder chosen in the Files picker (the
    /// card's root or its DCIM folder). Starts security-scoped access and
    /// scans it; an empty folder reports an error instead of closing.
    public func onCardFolderPicked(_ url: URL) {
        releaseCardAccess()
        if url.startAccessingSecurityScopedResource() {
            scopedCardURL = url
            cardAccessGeneration += 1
        }
        let volumeName = (try? url.resourceValues(forKeys: [.volumeNameKey]))?.volumeName
        let label = volumeName ?? url.lastPathComponent
        startScan(deviceLabel: label, directory: url, reportEmpty: true)
    }

    /// Reports a Files picker failure to the user.
    public func onCardFolderPickerFailed(_ message: String) {
        releaseCardAccess()
        state = .error(message: message)
    }

    public func onCardDetected(deviceLabel: String, directory: URL) {
        startScan(deviceLabel: deviceLabel, directory: directory, reportEmpty: false)
    }

    private func startScan(deviceLabel: String, directory: URL, reportEmpty: Bool) {
        let run = beginRun()
        state = .scanning(label: deviceLabel)
        sweepStaging()

        let runs = runGeneration
        queue.async { [weak self] in
            let result = AppleOtgCardScanner.scanDirectory(at: directory, deviceLabel: deviceLabel)
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                if runs.withLock({ $0 == run }) {
                    if !result.candidates.isEmpty {
                        self.state = .awaitingConfirmation(scanResult: result)
                    } else if reportEmpty {
                        self.releaseCardAccess()
                        self.state = .error(message: String(localized: "No photos or videos were found in the selected folder. Choose the card's root or its DCIM folder."))
                    } else {
                        self.state = .idle
                    }
                }
            }
        }
    }

    /// Copies, verifies and enqueues every candidate. A file that fails
    /// (copy error, size or hash mismatch, enqueue refusal) is skipped and
    /// listed in `.completed(failures:)`; the rest of the card still imports.
    public func confirmImport(
        scanResult: AppleOtgScanResult,
        stageDirectory: URL? = nil,
        onFileStaged: (@Sendable (URL, AppleOtgCandidate) -> Void)? = nil
    ) {
        let run = beginRun()
        let accessGeneration = cardAccessGeneration

        let destinationDir: URL
        do {
            if let stageDirectory {
                try FileManager.default.createDirectory(at: stageDirectory, withIntermediateDirectories: true)
                destinationDir = stageDirectory
            } else {
                destinationDir = try AppleOtgStaging.newSessionDirectory(in: stagingRoot)
            }
        } catch {
            releaseCardAccess()
            state = .error(message: String(localized: "Could not prepare storage for the import: \(error.localizedDescription)"))
            return
        }

        let candidates = scanResult.candidates
        let totalBytes = scanResult.totalSizeBytes
        let engine = self.engine
        let runs = runGeneration

        queue.async { [weak self] in
            var bytesProcessed: Int64 = 0
            var importedBytes: Int64 = 0
            var importedCount = 0
            var failures: [AppleOtgFileFailure] = []

            for (index, candidate) in candidates.enumerated() {
                if !runs.withLock({ $0 == run }) { break }

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

                let outcome = AppleOtgStaging.stage(candidate, in: destinationDir, engine: engine)
                bytesProcessed += max(candidate.sizeBytes, 0)
                switch outcome {
                case .staged(let url):
                    importedBytes += candidate.sizeBytes
                    importedCount += 1
                    onFileStaged?(url, candidate)
                case .failed(let message):
                    failures.append(AppleOtgFileFailure(
                        fileName: candidate.fileName,
                        relativePath: candidate.relativePath,
                        message: message
                    ))
                }
            }

            let finalBytes = importedBytes
            let finalImportedCount = importedCount
            let finalFailures = failures
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                if self.cardAccessGeneration == accessGeneration {
                    self.releaseCardAccess()
                }
                // A cancelled run already set .idle; a newer run owns the
                // state now, so a superseded run leaves it alone.
                if runs.withLock({ $0 == run }) {
                    self.state = .completed(
                        importedCount: finalImportedCount,
                        totalBytes: finalBytes,
                        failures: finalFailures
                    )
                }
            }
        }
    }

    public func cancelImport() {
        _ = beginRun()
        releaseCardAccess()
        state = .idle
    }

    public func reset() {
        _ = beginRun()
        releaseCardAccess()
        state = .idle
    }

    private func beginRun() -> Int {
        runGeneration.withLock { generation in
            generation += 1
            return generation
        }
    }

    /// Removes staged copies whose upload has completed (see
    /// `AppleOtgStaging.sweep`). Runs off the main actor.
    public func sweepStaging() {
        let engine = self.engine
        let root = stagingRoot
        queue.async {
            AppleOtgStaging.sweep(root: root, statuses: engine.mediaStatuses())
        }
    }

    private func releaseCardAccess() {
        guard let url = scopedCardURL else { return }
        url.stopAccessingSecurityScopedResource()
        scopedCardURL = nil
        cardAccessGeneration += 1
    }
}
