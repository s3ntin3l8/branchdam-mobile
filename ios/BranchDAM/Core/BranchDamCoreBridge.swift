import Foundation
import Combine
#if canImport(branchdam)
import branchdam
#endif

public struct SafeSpaceCandidateVerdict: Codable, Equatable {
    public let localId: String
    public let nodeUuid: String
    public let blake3Hash: String
    public let isVerified: Bool?
    public let isEligible: Bool?
    public let tier: String?

    public init(localId: String, nodeUuid: String = "", blake3Hash: String = "", isVerified: Bool = false, isEligible: Bool = false, tier: String = "") {
        self.localId = localId
        self.nodeUuid = nodeUuid
        self.blake3Hash = blake3Hash
        self.isVerified = isVerified
        self.isEligible = isEligible
        self.tier = tier
    }
}

public struct ActiveUploadProgress: Codable, Equatable, Sendable {
    public let filename: String
    public let bytesSent: Int64
    public let totalBytes: Int64
    public let itemIndex: Int
    public let totalItems: Int
    public let speedBytesPerSec: Double

    public init(
        filename: String,
        bytesSent: Int64,
        totalBytes: Int64,
        itemIndex: Int,
        totalItems: Int,
        speedBytesPerSec: Double
    ) {
        self.filename = filename
        self.bytesSent = bytesSent
        self.totalBytes = totalBytes
        self.itemIndex = itemIndex
        self.totalItems = totalItems
        self.speedBytesPerSec = speedBytesPerSec
    }
}

/// Bridge between the Swift shells (camera-roll observer, BGTask manager,
/// audit UI) and the gomobile-bound `branchdam` Go engine.
///
/// The gomobile calls block (they marshal arguments and call into Go over
/// a sequence number channel), so the synchronous methods below dispatch
/// onto private queues and wait:
///
/// - `syncQueue` (serial) runs `syncBatch`, which can hold it for the whole
///   batch timeout.
/// - `queryQueue` (concurrent) runs every other engine call, so queue
///   counts, statuses and enqueues never wait behind an upload batch.
///   The Go engine already serialises its own SQLite access (Android
///   issues these calls from a thread pool too).
/// - Opening and closing the engine take both: they run on `syncQueue`
///   (never under an in-flight batch) as a barrier on `queryQueue` (never
///   under an in-flight query).
///
/// `@MainActor` code must not call the synchronous methods: use the
/// `...Async` variants, which wait on a background thread instead of
/// blocking the main actor.
public class BranchDamCoreBridge: @unchecked Sendable {
    public static let shared = BranchDamCoreBridge()

    /// Agent ID used when none was stored at pairing (installs paired
    /// before the agent ID was persisted, or never paired).
    public static let defaultAgentID = "iphone-companion"

    private let syncQueue = DispatchQueue(label: "com.branchdam.mobile.bridge.sync", qos: .utility)
    private let queryQueue = DispatchQueue(
        label: "com.branchdam.mobile.bridge.query",
        qos: .userInitiated,
        attributes: .concurrent
    )

    private let stateLock = NSLock()
    private var initializedFlag = false
    private var mockOffloadedSet = Set<String>()

    public var isInitialized: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return initializedFlag
    }

    private func setInitialized(_ value: Bool) {
        stateLock.lock()
        initializedFlag = value
        stateLock.unlock()
    }

    /// Runs a query-class engine call on `queryQueue`, so it never
    /// overlaps opening or closing the engine. For the bridge extensions
    /// in other files (see PendingImportEngine.swift); must not be called
    /// from a block already on `queryQueue`.
    func onQueryQueue<T>(_ body: () -> T) -> T {
        queryQueue.sync(execute: body)
    }

    /// The app's marketing version (CFBundleShortVersionString), sent to
    /// the server as the client version. Empty when the bundle has none,
    /// in which case the engine substitutes its own version.
    public static var clientVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
    }

    /// The version of the engine bundled with this build. The Go engine
    /// ships inside the app, so this is the app's version.
    public static var engineVersion: String {
        #if canImport(branchdam)
        return clientVersion.isEmpty ? "unknown" : clientVersion
        #else
        return "unavailable"
        #endif
    }

    private init() {}

    public func initialize(
        dbPath: String,
        baseURL: String,
        apiKey: String = "",
        agentID: String = BranchDamCoreBridge.defaultAgentID,
        clientVersion: String = BranchDamCoreBridge.clientVersion,
        devCleartextHosts: String = ""
    ) -> Bool {
        #if canImport(branchdam)
        // Re-pairing while a batch is uploading: ask it to stop at its next
        // checkpoint rather than wait out the whole batch timeout.
        if isInitialized {
            setCancelFlag()
        }
        var success = false
        withEngineExclusive {
            if self.isInitialized {
                _ = try? branchdam.bindingClose()
                self.setInitialized(false)
            }
            let keyToUse = apiKey.isEmpty ? AppleKeychain.shared.apiKey ?? "" : apiKey
            do {
                try branchdam.bindingOpen(dbPath, baseURL, keyToUse, agentID, clientVersion, devCleartextHosts)
                self.setInitialized(true)
                success = true
            } catch {
                self.setInitialized(false)
                NSLog("BranchDamCoreBridge initialize failed: %@", String(describing: error))
            }
        }
        return success
        #else
        setInitialized(true)
        return true
        #endif
    }

    public func shutdown() {
        #if canImport(branchdam)
        withEngineExclusive {
            _ = try? branchdam.bindingClose()
            self.setInitialized(false)
        }
        #else
        setInitialized(false)
        #endif
    }

    /// Runs [work] with no batch and no query in flight: open/close must
    /// not race either.
    private func withEngineExclusive(_ work: () -> Void) {
        syncQueue.sync {
            queryQueue.sync(flags: .barrier) {
                work()
            }
        }
    }

    /// Opens the engine from the stored pairing (server URL in
    /// UserDefaults, API key and agent ID in the Keychain) if it is not
    /// open yet.
    @discardableResult
    public func startEngineIfNeeded() -> Bool {
        if isInitialized { return true }
        let docsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dbPath = docsDir.appendingPathComponent("branchdam_queue.db").path
        let storedServer = UserDefaults.standard.string(forKey: "branchdam_server_url") ?? ""
        if !storedServer.isEmpty {
            let agentID = PairingCredentialStore(store: AppleKeychain.shared).resolvedAgentID()
            return initialize(dbPath: dbPath, baseURL: storedServer, agentID: agentID)
        }
        return false
    }

    public func fetchNamingTemplate() -> String? {
        #if canImport(branchdam)
        guard isInitialized else { return nil }
        var tpl: String? = nil
        queryQueue.sync {
            do {
                let res = try branchdam.bindingFetchNamingTemplate()
                if !res.isEmpty {
                    tpl = res
                }
            } catch {
                NSLog("fetchNamingTemplate failed: %@", String(describing: error))
            }
        }
        return tpl
        #else
        return nil
        #endif
    }

    public func enqueueMedia(
        localPath: String,
        filename: String,
        capturedAtUnix: Int64,
        localID: String,
        cameraModel: String = "",
        sizeBytes: Int64 = 0
    ) -> Int64 {
        #if canImport(branchdam)
        guard isInitialized else { return 0 }
        var uploadId: Int64 = 0
        queryQueue.sync {
            do {
                uploadId = try branchdam.bindingEnqueueMedia(
                    localPath,
                    filename,
                    localID,
                    cameraModel,
                    capturedAtUnix,
                    sizeBytes
                )
            } catch {
                NSLog("enqueueMedia failed: %@", String(describing: error))
            }
        }
        return uploadId
        #else
        return 1
        #endif
    }

    /// Enqueues a lineage edge between two local media items. Despite the
    /// labels, parentUUID and childUUID are local IDs: the bare PHAsset
    /// localIdentifier the media was enqueued for upload under (or a
    /// synthetic child ID such as "<localIdentifier>/pairedVideo"). The core
    /// resolves them to server node UUIDs once both are uploaded.
    public func enqueueLineageEvent(
        parentUUID: String,
        childUUID: String,
        relationshipType: String,
        resolver: String,
        confidence: Double
    ) -> String {
        #if canImport(branchdam)
        guard isInitialized else { return "" }
        var eventUuid: String = ""
        queryQueue.sync {
            do {
                eventUuid = try branchdam.bindingEnqueueLineageEvent(
                    parentUUID,
                    childUUID,
                    relationshipType,
                    resolver,
                    confidence
                )
            } catch {
                NSLog("enqueueLineageEvent failed: %@", String(describing: error))
            }
        }
        return eventUuid
        #else
        return UUID().uuidString
        #endif
    }

    /// Enqueues EVENT_NODE_DELETED for a local media item, identified by the
    /// local ID it was enqueued for upload under (not a node UUID). The core
    /// sends it as the uploaded node's UUID and drops it for media that was
    /// never uploaded or was reclaimed.
    public func enqueueDeleteEvent(localID: String) -> String {
        #if canImport(branchdam)
        guard isInitialized else { return "" }
        var eventUuid: String = ""
        queryQueue.sync {
            do {
                eventUuid = try branchdam.bindingEnqueueDeleteEvent(localID)
            } catch {
                NSLog("enqueueDeleteEvent failed: %@", String(describing: error))
            }
        }
        return eventUuid
        #else
        return UUID().uuidString
        #endif
    }

    public func syncBatch(timeoutSecs: Int32 = 120, batchSize: Int32 = 10) -> (uploaded: Int32, eventsSent: Int32) {
        #if canImport(branchdam)
        guard isInitialized else { return (0, 0) }
        var uploaded: Int32 = 0
        var eventsSent: Int32 = 0
        syncQueue.sync {
            do {
                let resStr = try branchdam.bindingSyncBatch(Int64(timeoutSecs), Int64(batchSize))
                let parts = resStr.split(separator: ",")
                if parts.count == 2,
                   let u = Int32(parts[0]),
                   let e = Int32(parts[1]) {
                    uploaded = u
                    eventsSent = e
                }
            } catch {
                NSLog("syncBatch failed: %@", String(describing: error))
            }
        }
        return (uploaded, eventsSent)
        #else
        return (0, 0)
        #endif
    }

    public func isMediaOffloaded(localID: String) -> Bool {
        #if canImport(branchdam)
        guard isInitialized else { return false }
        var out: Bool = false
        queryQueue.sync {
            do {
                out = try branchdam.bindingIsMediaOffloaded(localID)
            } catch {
                NSLog("isMediaOffloaded failed: %@", String(describing: error))
            }
        }
        return out
        #else
        stateLock.lock()
        defer { stateLock.unlock() }
        return mockOffloadedSet.contains(localID)
        #endif
    }

    public func setMediaOffloaded(localID: String, isOffloaded: Bool) -> Bool {
        #if canImport(branchdam)
        guard isInitialized else { return false }
        var success = false
        queryQueue.sync {
            do {
                try branchdam.bindingSetMediaOffloaded(localID, isOffloaded)
                success = true
            } catch {
                NSLog("setMediaOffloaded failed: %@", String(describing: error))
            }
        }
        return success
        #else
        stateLock.lock()
        defer { stateLock.unlock() }
        if isOffloaded {
            mockOffloadedSet.insert(localID)
        } else {
            mockOffloadedSet.remove(localID)
        }
        return true
        #endif
    }

    public func reclaimSafeSpace(localID: String) -> (eligible: Bool, reason: String) {
        #if canImport(branchdam)
        guard isInitialized else { return (false, "Engine not initialized") }
        var eligible = false
        var reason = ""
        queryQueue.sync {
            do {
                try branchdam.bindingReclaimSafeSpace(localID)
                eligible = true
            } catch {
                reason = String(describing: error)
                NSLog("reclaimSafeSpace failed: %@", reason)
            }
        }
        return (eligible, reason)
        #else
        return (true, "")
        #endif
    }

    public func getMediaStatus(localID: String) -> String {
        #if canImport(branchdam)
        guard isInitialized else { return "NOT_ENQUEUED" }
        var status = "NOT_ENQUEUED"
        queryQueue.sync {
            do {
                status = try branchdam.bindingGetMediaStatus(localID)
            } catch {
                NSLog("getMediaStatus failed: %@", String(describing: error))
            }
        }
        return status
        #else
        return "NOT_ENQUEUED"
        #endif
    }

    public func getAllMediaStatuses() -> [String: String] {
        #if canImport(branchdam)
        guard isInitialized else { return [:] }
        var map: [String: String] = [:]
        queryQueue.sync {
            do {
                let jsonStr = try branchdam.bindingGetAllMediaStatuses()
                if let data = jsonStr.data(using: .utf8),
                   let decoded = try? JSONDecoder().decode([String: String].self, from: data) {
                    map = decoded
                }
            } catch {
                NSLog("getAllMediaStatuses failed: %@", String(describing: error))
            }
        }
        return map
        #else
        return [:]
        #endif
    }

    public func countPendingUploads() -> Int64? {
        #if canImport(branchdam)
        guard isInitialized else { return nil }
        var count: Int64? = nil
        queryQueue.sync {
            do {
                count = try branchdam.bindingCountPendingUploads()
            } catch {
                NSLog("countPendingUploads failed: %@", String(describing: error))
            }
        }
        return count
        #else
        return isInitialized ? 0 : nil
        #endif
    }

    public func resetFailedUploads() -> Int64? {
        #if canImport(branchdam)
        guard isInitialized else { return nil }
        var count: Int64? = nil
        queryQueue.sync {
            do {
                count = try branchdam.bindingResetFailedUploads()
            } catch {
                NSLog("resetFailedUploads failed: %@", String(describing: error))
            }
        }
        return count
        #else
        return isInitialized ? 0 : nil
        #endif
    }

    public func getActiveUploadProgress() -> ActiveUploadProgress? {
        #if canImport(branchdam)
        guard isInitialized else { return nil }
        var progress: ActiveUploadProgress? = nil
        do {
            let jsonStr = try branchdam.bindingGetActiveUploadProgress()
            if !jsonStr.isEmpty,
               let data = jsonStr.data(using: .utf8),
               let decoded = try? JSONDecoder().decode(ActiveUploadProgress.self, from: data) {
                progress = decoded
            }
        } catch {
            NSLog("getActiveUploadProgress failed: %@", String(describing: error))
        }
        return progress
        #else
        return nil
        #endif
    }

    public func testConnectionDetailed() -> String? {
        #if canImport(branchdam)
        var errorMsg: String? = nil
        queryQueue.sync {
            guard self.isInitialized else {
                errorMsg = "Engine not initialized"
                return
            }
            do {
                _ = try branchdam.bindingFetchNamingTemplate()
            } catch {
                errorMsg = String(describing: error)
            }
        }
        return errorMsg
        #else
        return isInitialized ? nil : "Engine not initialized"
        #endif
    }

    public func checkContent(fastHash: String, fullHash: String) -> String? {
        #if canImport(branchdam)
        guard isInitialized else { return nil }
        var resultJson: String? = nil
        queryQueue.sync {
            do {
                let res = try branchdam.bindingCheckContent(fastHash, fullHash)
                if !res.isEmpty {
                    resultJson = res
                }
            } catch {
                NSLog("checkContent failed: %@", String(describing: error))
            }
        }
        return resultJson
        #else
        return nil
        #endif
    }

    public func lookupBlake3ForLocalID(localID: String) -> String {
        #if canImport(branchdam)
        guard isInitialized else { return "" }
        var hash = ""
        queryQueue.sync {
            do {
                hash = try branchdam.bindingLookupBlake3ForLocalID(localID)
            } catch {
                NSLog("lookupBlake3ForLocalID failed: %@", String(describing: error))
            }
        }
        return hash
        #else
        return ""
        #endif
    }

    public func computeHashes(localPath: String) -> String {
        #if canImport(branchdam)
        guard isInitialized else { return "" }
        var hash = ""
        queryQueue.sync {
            do {
                hash = try branchdam.bindingComputeHashes(localPath)
            } catch {
                NSLog("computeHashes failed: %@", String(describing: error))
            }
        }
        return hash
        #else
        return ""
        #endif
    }

    public func setCancelFlag() {
        #if canImport(branchdam)
        guard isInitialized else { return }
        do {
            try branchdam.bindingSetCancelFlag()
        } catch {
            NSLog("setCancelFlag failed: %@", String(describing: error))
        }
        #endif
    }

    // MARK: - Async variants for @MainActor callers

    /// Runs a blocking bridge call on a background thread and resumes the
    /// caller when it finishes, so SwiftUI views and view models never
    /// block the main actor on the engine.
    private func offMainActor<T: Sendable>(_ work: @escaping @Sendable () -> T) async -> T {
        await withCheckedContinuation { (continuation: CheckedContinuation<T, Never>) in
            DispatchQueue.global(qos: .userInitiated).async {
                continuation.resume(returning: work())
            }
        }
    }

    public func initializeAsync(
        dbPath: String,
        baseURL: String,
        apiKey: String = "",
        agentID: String = BranchDamCoreBridge.defaultAgentID,
        clientVersion: String = BranchDamCoreBridge.clientVersion,
        devCleartextHosts: String = ""
    ) async -> Bool {
        await offMainActor {
            self.initialize(
                dbPath: dbPath,
                baseURL: baseURL,
                apiKey: apiKey,
                agentID: agentID,
                clientVersion: clientVersion,
                devCleartextHosts: devCleartextHosts
            )
        }
    }

    @discardableResult
    public func startEngineIfNeededAsync() async -> Bool {
        await offMainActor { self.startEngineIfNeeded() }
    }

    public func fetchNamingTemplateAsync() async -> String? {
        await offMainActor { self.fetchNamingTemplate() }
    }

    public func countPendingUploadsAsync() async -> Int64? {
        await offMainActor { self.countPendingUploads() }
    }

    public func getAllMediaStatusesAsync() async -> [String: String] {
        await offMainActor { self.getAllMediaStatuses() }
    }

    public func resetFailedUploadsAsync() async -> Int64? {
        await offMainActor { self.resetFailedUploads() }
    }

    public func testConnectionDetailedAsync() async -> String? {
        await offMainActor { self.testConnectionDetailed() }
    }

    public func reclaimSafeSpaceAsync(localID: String) async -> (eligible: Bool, reason: String) {
        await offMainActor { self.reclaimSafeSpace(localID: localID) }
    }
}
