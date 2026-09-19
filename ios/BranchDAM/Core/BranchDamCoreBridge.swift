import Foundation
import Combine
#if canImport(branchdam)
import branchdam
#endif

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

public class BranchDamCoreBridge: @unchecked Sendable {
    public static let shared = BranchDamCoreBridge()

    private let workQueue = DispatchQueue(label: "com.branchdam.mobile.bridge", qos: .userInitiated)
    public private(set) var isInitialized: Bool = false

    public static var engineVersion: String {
        #if canImport(branchdam)
        return "1.0.0"
        #else
        return "unavailable"
        #endif
    }

    private init() {}

    public func initialize(
        dbPath: String,
        baseURL: String,
        apiKey: String = "",
        agentID: String = "iphone-companion",
        clientVersion: String = BranchDamCoreBridge.engineVersion,
        devCleartextHosts: String = ""
    ) -> Bool {
        #if canImport(branchdam)
        var success = false
        workQueue.sync {
            if self.isInitialized {
                _ = try? branchdam.bindingClose()
                self.isInitialized = false
            }
            let keyToUse = apiKey.isEmpty ? AppleKeychain.shared.apiKey ?? "" : apiKey
            do {
                try branchdam.bindingOpen(dbPath, baseURL, keyToUse, agentID, clientVersion, devCleartextHosts)
                self.isInitialized = true
                success = true
            } catch {
                self.isInitialized = false
                NSLog("BranchDamCoreBridge initialize failed: %@", String(describing: error))
            }
        }
        return success
        #else
        isInitialized = true
        return true
        #endif
    }

    public func shutdown() {
        #if canImport(branchdam)
        workQueue.sync {
            _ = try? branchdam.bindingClose()
            self.isInitialized = false
        }
        #else
        self.isInitialized = false
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
        workQueue.sync {
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
        workQueue.sync {
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

    public func enqueueDeleteEvent(nodeUUID: String) -> String {
        #if canImport(branchdam)
        guard isInitialized else { return "" }
        var eventUuid: String = ""
        workQueue.sync {
            do {
                eventUuid = try branchdam.bindingEnqueueDeleteEvent(nodeUUID)
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
        workQueue.sync {
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
        workQueue.sync {
            do {
                out = try branchdam.bindingIsMediaOffloaded(localID)
            } catch {
                NSLog("isMediaOffloaded failed: %@", String(describing: error))
            }
        }
        return out
        #else
        return false
        #endif
    }

    public func setMediaOffloaded(localID: String, isOffloaded: Bool) -> Bool {
        #if canImport(branchdam)
        guard isInitialized else { return false }
        var success = false
        workQueue.sync {
            do {
                try branchdam.bindingSetMediaOffloaded(localID, isOffloaded)
                success = true
            } catch {
                NSLog("setMediaOffloaded failed: %@", String(describing: error))
            }
        }
        return success
        #else
        return true
        #endif
    }

    public func reclaimSafeSpace(localID: String) -> (eligible: Bool, reason: String) {
        #if canImport(branchdam)
        guard isInitialized else { return (false, "Engine not initialized") }
        var eligible = false
        var reason = ""
        workQueue.sync {
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
        workQueue.sync {
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
        workQueue.sync {
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

    public func countPendingUploads() -> Int64 {
        #if canImport(branchdam)
        guard isInitialized else { return 0 }
        var count: Int64 = 0
        workQueue.sync {
            do {
                count = try branchdam.bindingCountPendingUploads()
            } catch {
                NSLog("countPendingUploads failed: %@", String(describing: error))
            }
        }
        return count
        #else
        return 0
        #endif
    }

    public func resetFailedUploads() -> Int64 {
        #if canImport(branchdam)
        guard isInitialized else { return 0 }
        var count: Int64 = 0
        workQueue.sync {
            do {
                count = try branchdam.bindingResetFailedUploads()
            } catch {
                NSLog("resetFailedUploads failed: %@", String(describing: error))
            }
        }
        return count
        #else
        return 0
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
        workQueue.sync {
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
        workQueue.sync {
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
        workQueue.sync {
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
        workQueue.sync {
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
}
