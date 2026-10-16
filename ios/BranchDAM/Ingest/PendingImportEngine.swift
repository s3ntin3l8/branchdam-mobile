import Foundation
#if canImport(branchdam)
import branchdam
#endif

/// A camera-roll asset awaiting the user's import decision, as persisted
/// in the core's `pending_import` table (`queue.PendingImport`).
public struct PendingImportItem: Codable, Equatable, Sendable {
    /// The bare PHAsset localIdentifier: the same local ID the asset is
    /// enqueued for upload under.
    public let localId: String
    public let localPath: String
    public let filename: String
    public let mimeType: String
    public let sizeBytes: Int64
    public let capturedAtUnix: Int64
    /// When the row was first staged (seconds since the epoch).
    public let createdAtUnix: Int64

    public init(
        localId: String,
        localPath: String = "",
        filename: String,
        mimeType: String = "",
        sizeBytes: Int64 = 0,
        capturedAtUnix: Int64,
        createdAtUnix: Int64
    ) {
        self.localId = localId
        self.localPath = localPath
        self.filename = filename
        self.mimeType = mimeType
        self.sizeBytes = sizeBytes
        self.capturedAtUnix = capturedAtUnix
        self.createdAtUnix = createdAtUnix
    }

    private enum CodingKeys: String, CodingKey {
        case localId, localPath, filename, mimeType, sizeBytes, capturedAtUnix, createdAtUnix
    }

    // The core omits empty fields (mimeType is `omitempty`), so decode
    // everything but localId leniently.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        localId = try c.decode(String.self, forKey: .localId)
        localPath = try c.decodeIfPresent(String.self, forKey: .localPath) ?? ""
        filename = try c.decodeIfPresent(String.self, forKey: .filename) ?? ""
        mimeType = try c.decodeIfPresent(String.self, forKey: .mimeType) ?? ""
        sizeBytes = try c.decodeIfPresent(Int64.self, forKey: .sizeBytes) ?? 0
        capturedAtUnix = try c.decodeIfPresent(Int64.self, forKey: .capturedAtUnix) ?? 0
        createdAtUnix = try c.decodeIfPresent(Int64.self, forKey: .createdAtUnix) ?? 0
    }

    /// Decodes the JSON array `BindingListPendingImports` returns. Rows
    /// without a localId are dropped; malformed JSON yields nil.
    public static func decodeList(_ json: String) -> [PendingImportItem]? {
        guard let data = json.data(using: .utf8) else { return nil }
        struct Lenient: Decodable {
            let item: PendingImportItem?
            init(from decoder: Decoder) throws {
                item = try? PendingImportItem(from: decoder)
            }
        }
        guard let rows = try? JSONDecoder().decode([Lenient].self, from: data) else { return nil }
        return rows.compactMap { $0.item }.filter { !$0.localId.isEmpty }
    }
}

/// Result of staging a pending import.
public enum PendingImportStageResult: Equatable, Sendable {
    /// Recorded as awaiting the user's decision.
    case staged
    /// The user already skipped this asset; nothing was staged.
    case suppressed
    /// Nothing was recorded (engine not open, DB error).
    case failed
}

/// The engine operations the camera-roll import flow needs. The
/// production implementation is `BranchDamCoreBridge`; tests substitute
/// an in-memory fake. Every method blocks on the Go engine: call them
/// off the main thread.
public protocol PendingImportEngine: AnyObject, Sendable {
    var isReady: Bool { get }
    func stagePendingImport(_ item: PendingImportItem) -> PendingImportStageResult
    /// Items awaiting a decision, oldest capture first; nil when they
    /// can't be read (which is not "nothing pending").
    func listPendingImports() -> [PendingImportItem]?
    @discardableResult func deletePendingImport(localID: String) -> Bool
    @discardableResult func suppressPendingImport(localID: String) -> Bool
    func isImportSuppressed(localID: String) -> Bool
    /// Queues a staged file for upload; returns the queue row ID, 0 on failure.
    func enqueueStagedMedia(localPath: String, filename: String, capturedAtUnix: Int64, localID: String) -> Int64
    /// The upload status of [localID]; "NOT_ENQUEUED" when the engine
    /// doesn't know it.
    func mediaStatus(localID: String) -> String
}

/// Upload statuses as the core reports them (`queue.GetMediaStatus`).
public enum UploadStatus {
    /// Whether [status] means the asset is in the upload queue and will
    /// get (or got) there without being enqueued again. FAILED does not
    /// count: the core's upload picker skips FAILED rows, and enqueueing
    /// the asset again is what resets one for a retry.
    public static func isQueued(_ status: String) -> Bool {
        switch status {
        case "PENDING", "IN_PROGRESS", "COMPLETED", "OFFLOADED": return true
        default: return false
        }
    }
}

// MARK: - BranchDamCoreBridge

/// pending_import wrappers. Like the bridge's other query-class calls
/// they run on its concurrent query queue (`onQueryQueue`), so they never
/// overlap opening or closing the engine and never wait behind an upload
/// batch. They block: call them from a background queue (the import flow
/// and the PhotoKit scan always do), never from the main actor.
extension BranchDamCoreBridge: PendingImportEngine {

    public var isReady: Bool { isInitialized }

    public func stagePendingImport(_ item: PendingImportItem) -> PendingImportStageResult {
        guard !item.localId.isEmpty else { return .failed }
        #if canImport(branchdam)
        guard isInitialized else { return .failed }
        return onQueryQueue {
            do {
                let staged = try branchdam.bindingStagePendingImport(
                    item.localId,
                    item.localPath,
                    item.filename,
                    item.mimeType,
                    item.sizeBytes,
                    item.capturedAtUnix
                )
                return staged ? .staged : .suppressed
            } catch {
                NSLog("stagePendingImport failed: %@", String(describing: error))
                return .failed
            }
        }
        #else
        return MockPendingImports.shared.stage(item)
        #endif
    }

    public func listPendingImports() -> [PendingImportItem]? {
        #if canImport(branchdam)
        guard isInitialized else { return nil }
        return onQueryQueue {
            do {
                let json = try branchdam.bindingListPendingImports(0)
                return PendingImportItem.decodeList(json)
            } catch {
                NSLog("listPendingImports failed: %@", String(describing: error))
                return nil
            }
        }
        #else
        return MockPendingImports.shared.list()
        #endif
    }

    @discardableResult
    public func deletePendingImport(localID: String) -> Bool {
        guard !localID.isEmpty else { return false }
        #if canImport(branchdam)
        guard isInitialized else { return false }
        return onQueryQueue {
            do {
                return try branchdam.bindingDeletePendingImport(localID)
            } catch {
                NSLog("deletePendingImport failed: %@", String(describing: error))
                return false
            }
        }
        #else
        return MockPendingImports.shared.delete(localID)
        #endif
    }

    @discardableResult
    public func suppressPendingImport(localID: String) -> Bool {
        guard !localID.isEmpty else { return false }
        #if canImport(branchdam)
        guard isInitialized else { return false }
        return onQueryQueue {
            do {
                try branchdam.bindingSuppressPendingImport(localID)
                return true
            } catch {
                NSLog("suppressPendingImport failed: %@", String(describing: error))
                return false
            }
        }
        #else
        MockPendingImports.shared.suppress(localID)
        return true
        #endif
    }

    public func isImportSuppressed(localID: String) -> Bool {
        guard !localID.isEmpty else { return false }
        #if canImport(branchdam)
        guard isInitialized else { return false }
        return onQueryQueue {
            do {
                return try branchdam.bindingIsImportSuppressed(localID)
            } catch {
                NSLog("isImportSuppressed failed: %@", String(describing: error))
                return false
            }
        }
        #else
        return MockPendingImports.shared.isSuppressed(localID)
        #endif
    }

    public func enqueueStagedMedia(localPath: String, filename: String, capturedAtUnix: Int64, localID: String) -> Int64 {
        enqueueMedia(localPath: localPath, filename: filename, capturedAtUnix: capturedAtUnix, localID: localID)
    }

    public func mediaStatus(localID: String) -> String {
        getMediaStatus(localID: localID)
    }
}

#if !canImport(branchdam)
/// In-memory stand-in for pending_import when the Go framework is not
/// linked, mirroring the core's semantics.
final class MockPendingImports: @unchecked Sendable {
    static let shared = MockPendingImports()
    private let lock = NSLock()
    private var pending = [String: PendingImportItem]()
    private var suppressed = Set<String>()

    func stage(_ item: PendingImportItem) -> PendingImportStageResult {
        lock.lock(); defer { lock.unlock() }
        if suppressed.contains(item.localId) { return .suppressed }
        // Like the core: the staging time is stamped here (and kept on a
        // re-stage), whatever the caller passed.
        let createdAt = pending[item.localId]?.createdAtUnix ?? Int64(Date().timeIntervalSince1970)
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

    func list() -> [PendingImportItem] {
        lock.lock(); defer { lock.unlock() }
        return pending.values.sorted { ($0.capturedAtUnix, $0.localId) < ($1.capturedAtUnix, $1.localId) }
    }

    func delete(_ id: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        let a = pending.removeValue(forKey: id) != nil
        let b = suppressed.remove(id) != nil
        return a || b
    }

    func suppress(_ id: String) {
        lock.lock(); defer { lock.unlock() }
        pending.removeValue(forKey: id)
        suppressed.insert(id)
    }

    func isSuppressed(_ id: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return suppressed.contains(id)
    }
}
#endif
