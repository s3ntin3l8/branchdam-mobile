import Foundation
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

/// Bridge between the Swift shells (camera-roll observer, BGTask manager,
/// audit UI) and the gomobile-bound `branchdam` Go engine. Sub-issue A wires
/// the bridge to the new framework; sub-issues B/E replace the mock-fallback
/// branches with real engine calls.
public class BranchDamCoreBridge {
    public static let shared = BranchDamCoreBridge()

    #if canImport(branchdam)
    private var engine: branchdam.Engine?
    #endif
    private var isInitialized = false
    private var mockOffloadedMedia: [String: Bool] = [:]

    private init() {}

    /// Initialize the Go engine. Returns true on success. A's stub engine
    /// always succeeds; B's real engine opens the SQLite queue and HTTP client.
    public func initialize(
        dbPath: String,
        baseURL: String,
        apiKey: String = "",
        agentID: String = "iphone-companion",
        version: String = "0.1.0"
    ) -> Bool {
        #if canImport(branchdam)
        do {
            let opts = branchdam.EngineOptions()
            opts.dbPath = dbPath
            opts.baseURL = baseURL
            opts.apiKey = apiKey
            opts.agentID = agentID
            opts.clientVersion = version
            opts.httpTimeoutSec = 0
            let e = try branchdam.Engine.newEngine(opts)
            self.engine = e
            self.isInitialized = true
            return true
        } catch {
            self.isInitialized = false
            return false
        }
        #else
        self.isInitialized = true
        return true
        #endif
    }

    /// Reported version of the bound Go engine. Useful for diagnostics and
    /// smoke tests confirming the artifact loaded.
    public static var engineVersion: String {
        #if canImport(branchdam)
        // gomobile binds Go's `Version() string` (no error return) as a
        // non-throwing Swift method. The try/catch from the previous draft
        // was dead code and triggered a Swift 6 "no calls to throwing
        // functions" warning.
        return branchdam.version()
        #else
        return "unavailable"
        #endif
    }

    public func enqueueMedia(
        localPath: String,
        filename: String,
        capturedAtUnix: Int64,
        localID: String
    ) -> Int64 {
        // Sub-issue B wires the real engine call. Until then, return a
        // positive ID so callers that only check for failure keep working.
        return 1
    }

    public func enqueueLineageEvent(
        parentUUID: String,
        childUUID: String,
        relationshipType: String = "DERIVED_FROM",
        resolver: String = "ios_apple_camera_pair",
        confidence: Double = 1.00
    ) -> String {
        return UUID().uuidString
    }

    public func enqueueDeleteEvent(nodeUUID: String) -> String {
        return UUID().uuidString
    }

    public func syncBatch(timeoutSecs: Int32 = 120, batchSize: Int32 = 10) -> (uploaded: Int32, eventsSent: Int32) {
        return (uploaded: 0, eventsSent: 0)
    }

    public func isMediaOffloaded(localID: String) -> Bool {
        return mockOffloadedMedia[localID] ?? false
    }

    public func setMediaOffloaded(localID: String, isOffloaded: Bool) -> Bool {
        mockOffloadedMedia[localID] = isOffloaded
        return true
    }

    public func fetchNamingTemplate() -> String {
        return "{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}"
    }
}
