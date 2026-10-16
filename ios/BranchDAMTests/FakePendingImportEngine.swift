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
        return enqueueResult
    }
}

/// `AssetStager` that "copies" an asset to a fake path, or fails for the
/// identifiers in `failing`.
struct FakeAssetStager: AssetStager {
    var failing: Set<String> = []

    func stage(localIdentifier: String, filename: String, completion: @escaping @Sendable (String?) -> Void) {
        if failing.contains(localIdentifier) {
            completion(nil)
        } else {
            completion("/staged/\(localIdentifier.replacingOccurrences(of: "/", with: "_"))_\(filename)")
        }
    }
}
