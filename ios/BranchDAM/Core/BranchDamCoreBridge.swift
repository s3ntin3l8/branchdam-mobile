import Foundation

public struct SafeSpaceCandidateVerdict: Codable, Equatable {
    public let localId: String
    public let nodeUuid: String
    public let blake3Hash: String
    public let isVerified: Boolean?
    public let isEligible: Boolean?
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

public class BranchDamCoreBridge {
    public static let shared = BranchDamCoreBridge()

    private var isInitialized = false

    private init() {}

    public func initialize(
        dbPath: String,
        baseURL: String,
        apiKey: String = "",
        agentID: String = "iphone-companion",
        version: String = "0.1.0"
    ) -> Bool {
        #if canImport(BranchDamCore)
        let res = InitCore(dbPath, baseURL, apiKey, agentID, version)
        self.isInitialized = (res == 0)
        return self.isInitialized
        #else
        self.isInitialized = true
        return true
        #endif
    }

    public func enqueueMedia(
        localPath: String,
        filename: String,
        capturedAtUnix: Int64,
        localID: String
    ) -> Int64 {
        #if canImport(BranchDamCore)
        return EnqueueMedia(localPath, filename, capturedAtUnix, localID)
        #else
        return 1
        #endif
    }

    public func enqueueLineageEvent(
        parentUUID: String,
        childUUID: String,
        relationshipType: String = "DERIVED_FROM",
        resolver: String = "ios_apple_camera_pair",
        confidence: Double = 1.00
    ) -> String {
        #if canImport(BranchDamCore)
        guard let cStr = EnqueueLineageEvent(parentUUID, childUUID, relationshipType, resolver, confidence) else {
            return ""
        }
        let result = String(cString: cStr)
        FreeCString(cStr)
        return result
        #else
        return UUID().uuidString
        #endif
    }

    public func enqueueDeleteEvent(nodeUUID: String) -> String {
        #if canImport(BranchDamCore)
        guard let cStr = EnqueueDeleteEvent(nodeUUID) else {
            return ""
        }
        let result = String(cString: cStr)
        FreeCString(cStr)
        return result
        #else
        return UUID().uuidString
        #endif
    }

    public func syncBatch(timeoutSecs: Int32 = 120, batchSize: Int32 = 10) -> (uploaded: Int32, eventsSent: Int32) {
        #if canImport(BranchDamCore)
        let res = SyncBatch(timeoutSecs, batchSize)
        return (uploaded: res, eventsSent: 0)
        #else
        return (uploaded: 0, eventsSent: 0)
        #endif
    }

    public func isMediaOffloaded(localID: String) -> Bool {
        #if canImport(BranchDamCore)
        return GetMediaOffloaded(localID) == 1
        #else
        return false
        #endif
    }

    public func setMediaOffloaded(localID: String, isOffloaded: Bool) -> Bool {
        #if canImport(BranchDamCore)
        return SetMediaOffloaded(localID, isOffloaded ? 1 : 0) == 0
        #else
        return true
        #endif
    }

    public func fetchNamingTemplate() -> String {
        #if canImport(BranchDamCore)
        guard let cStr = FetchNamingTemplate() else {
            return "{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}"
        }
        let result = String(cString: cStr)
        FreeCString(cStr)
        return result
        #else
        return "{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}"
        #endif
    }
}
