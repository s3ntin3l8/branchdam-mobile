import Foundation

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

public class BranchDamCoreBridge {
    public static let shared = BranchDamCoreBridge()

    private var isInitialized = false
    private var mockOffloadedMedia: [String: Bool] = [:]

    private init() {}

    public func initialize(
        dbPath: String,
        baseURL: String,
        apiKey: String = "",
        agentID: String = "iphone-companion",
        version: String = "0.1.0"
    ) -> Bool {
        #if canImport(BranchDamCore)
        do {
            try Bindings.initCore(dbPath, baseURL: baseURL, apiKey: apiKey, agentID: agentID, clientVersion: version)
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

    public func enqueueMedia(
        localPath: String,
        filename: String,
        capturedAtUnix: Int64,
        localID: String
    ) -> Int64 {
        #if canImport(BranchDamCore)
        do {
            return try Bindings.enqueueMedia(localPath, filename: filename, capturedAtUnix: capturedAtUnix, localID: localID, cameraModel: "")
        } catch {
            return 0
        }
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
        do {
            return try Bindings.enqueueLineageEvent(parentUUID, childUUID: childUUID, relationshipType: relationshipType, resolver: resolver, confidence: confidence)
        } catch {
            return ""
        }
        #else
        return UUID().uuidString
        #endif
    }

    public func enqueueDeleteEvent(nodeUUID: String) -> String {
        #if canImport(BranchDamCore)
        do {
            return try Bindings.enqueueDeleteEvent(nodeUUID)
        } catch {
            return ""
        }
        #else
        return UUID().uuidString
        #endif
    }

    public func syncBatch(timeoutSecs: Int32 = 120, batchSize: Int32 = 10) -> (uploaded: Int32, eventsSent: Int32) {
        #if canImport(BranchDamCore)
        do {
            if let res = try Bindings.syncBatch(Int(timeoutSecs), batchSize: Int(batchSize)) {
                return (uploaded: Int32(res.uploaded), eventsSent: Int32(res.eventsSent))
            }
            return (uploaded: 0, eventsSent: 0)
        } catch {
            return (uploaded: 0, eventsSent: 0)
        }
        #else
        return (uploaded: 0, eventsSent: 0)
        #endif
    }

    public func isMediaOffloaded(localID: String) -> Bool {
        #if canImport(BranchDamCore)
        return Bindings.isMediaOffloaded(localID)
        #else
        return mockOffloadedMedia[localID] ?? false
        #endif
    }

    public func setMediaOffloaded(localID: String, isOffloaded: Bool) -> Bool {
        #if canImport(BranchDamCore)
        do {
            try Bindings.setMediaOffloaded(localID, isOffloaded: isOffloaded)
            return true
        } catch {
            return false
        }
        #else
        mockOffloadedMedia[localID] = isOffloaded
        return true
        #endif
    }

    public func fetchNamingTemplate() -> String {
        #if canImport(BranchDamCore)
        do {
            return try Bindings.fetchNamingTemplate()
        } catch {
            return "{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}"
        }
        #else
        return "{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}"
        #endif
    }
}
