import Foundation
@testable import BranchDAM

/// In-memory `PendingImportEngine` mirroring the core's pending_import
/// semantics, recording every call in order.
final class FakePendingImportEngine: PendingImportEngine, @unchecked Sendable {
    private let lock = NSLock()
    private var pending = [String: PendingImportItem]()
    private var suppressed = Set<String>()
    private(set) var enqueued = [(localPath: String, localID: String)]()
    private(set) var log = [String]()

    var isReady = true
    var failStaging = false
    var failListing = false
    var enqueueResult: Int64 = 7
    /// Statuses reported ahead of the queue (e.g. "FAILED"); enqueueing a
    /// local ID clears its entry, as the core's re-enqueue resets a FAILED
    /// row.
    var statusOverrides = [String: String]()
    var clock: Int64 = 1_000

    func stagePendingImport(_ item: PendingImportItem) -> PendingImportStageResult {
        lock.lock(); defer { lock.unlock() }
        log.append("stage:\(item.localId)")
        if failStaging { return .failed }
        if suppressed.contains(item.localId) { return .suppressed }
        let createdAt = pending[item.localId]?.createdAtUnix ?? clock
        pending[item.localId] = PendingImportItem(
            localId: item.localId,
            localPath: item.localPath,
            filename: item.filename,
            mimeType: item.mimeType,
            sizeBytes: item.sizeBytes,
            capturedAtUnix: item.capturedAtUnix,
            createdAtUnix: createdAt
        )
        return .staged
    }

    func listPendingImports() -> [PendingImportItem]? {
        lock.lock(); defer { lock.unlock() }
        if failListing { return nil }
        return pending.values.sorted { ($0.capturedAtUnix, $0.localId) < ($1.capturedAtUnix, $1.localId) }
    }

    @discardableResult
    func deletePendingImport(localID: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        log.append("delete:\(localID)")
        let a = pending.removeValue(forKey: localID) != nil
        let b = suppressed.remove(localID) != nil
        return a || b
    }

    @discardableResult
    func suppressPendingImport(localID: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        log.append("suppress:\(localID)")
        pending.removeValue(forKey: localID)
        suppressed.insert(localID)
        return true
    }

    func isImportSuppressed(localID: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return suppressed.contains(localID)
    }

    func enqueueStagedMedia(localPath: String, filename: String, capturedAtUnix: Int64, localID: String) -> Int64 {
        lock.lock(); defer { lock.unlock() }
        log.append("enqueue:\(localID)")
        enqueued.append((localPath, localID))
        if enqueueResult > 0 { statusOverrides.removeValue(forKey: localID) }
        return enqueueResult
    }

    func mediaStatus(localID: String) -> String {
        lock.lock(); defer { lock.unlock() }
        if let status = statusOverrides[localID] { return status }
        return enqueueResult > 0 && enqueued.contains(where: { $0.localID == localID }) ? "PENDING" : "NOT_ENQUEUED"
    }
}

/// `AssetStager` that "copies" an asset to a fake path, or fails for the
/// identifiers in `failing`.
struct FakeAssetStager: AssetStager {
    var failing: Set<String> = []
    /// Local IDs that are Live Photos (have a motion track to stage).
    var livePhotos: Set<String> = []
    /// Live Photos whose motion track fails to stage.
    var failingVideos: Set<String> = []

    func stagePairedVideo(localIdentifier: String, completion: @escaping @Sendable (PairedVideoStaging) -> Void) {
        guard livePhotos.contains(localIdentifier) else {
            completion(.none)
            return
        }
        if failingVideos.contains(localIdentifier) {
            completion(.failed)
            return
        }
        let safe = localIdentifier.replacingOccurrences(of: "/", with: "_")
        completion(.staged(StagedResource(path: "/staged/\(safe)_pairedVideo.mov", filename: "IMG_LIVE.MOV")))
    }

    func stage(localIdentifier: String, filename: String, completion: @escaping @Sendable (String?) -> Void) {
        if failing.contains(localIdentifier) {
            completion(nil)
        } else {
            completion("/staged/\(localIdentifier.replacingOccurrences(of: "/", with: "_"))_\(filename)")
        }
    }
}
