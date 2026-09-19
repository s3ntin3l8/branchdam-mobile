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

public struct ActiveUploadProgress: Codable, Equatable, Sendable {
    public let id: Int64
    public let filename: String
    public let bytesSent: Int64
    public let totalBytes: Int64
    public let speedBytesPerSec: Double
    public let itemIndex: Int
    public let totalItems: Int

    public init(
        id: Int64 = 0,
        filename: String = "",
        bytesSent: Int64 = 0,
        totalBytes: Int64 = 0,
        speedBytesPerSec: Double = 0.0,
        itemIndex: Int = 0,
        totalItems: Int = 0
    ) {
        self.id = id
        self.filename = filename
        self.bytesSent = bytesSent
        self.totalBytes = totalBytes
        self.speedBytesPerSec = speedBytesPerSec
        self.itemIndex = itemIndex
        self.totalItems = totalItems
    }
}

/// Bridge between the Swift shells (camera-roll observer, BGTask manager,
/// audit UI) and the gomobile-bound `branchdam` Go engine.
///
/// All public methods are synchronous from the caller's perspective. The
/// underlying gomobile calls block (they marshal arguments and call
/// into Go over a sequence number channel), so the bridge internally
/// dispatches calls to a private serial background queue.
public class BranchDamCoreBridge: @unchecked Sendable {
    public static let shared = BranchDamCoreBridge()

    /// Serial queue that runs the gomobile calls. gomobile's transport
    /// is blocking; running everything on one serial queue keeps the
    /// ordering predictable and prevents concurrent Go-runtime access.
    private let workQueue = DispatchQueue(label: "com.branchdam.mobile.bridge", qos: .userInitiated)

    private(set) var isInitialized = false
    private var mockOffloadedMedia: [String: Bool] = [:]

    private init() {}

    /// Initialize the Go engine. Returns true on success; surfaces
    /// errors via NSLog and returns false if bindingOpen fails.
    ///
    /// T2-5 hardening: the API key is read from the iOS keychain by
    /// default unless supplied explicitly.
    public func initialize(
        dbPath: String,
        baseURL: String,
        apiKey: String? = nil,
        agentID: String = "iphone-companion",
        version: String = "0.1.0",
        devCleartextHosts: String = ""
    ) -> Bool {
        let resolvedApiKey = apiKey ?? AppleKeychain.shared.apiKey ?? ""
        #if canImport(branchdam)
        var success = false
        workQueue.sync {
            _ = try? branchdam.bindingClose()
            do {
                try branchdam.bindingOpen(dbPath, baseURL, resolvedApiKey, agentID, version, devCleartextHosts)
                self.isInitialized = true
                success = true
            } catch {
                NSLog("initialize bindingOpen failed: %@", String(describing: error))
                self.isInitialized = false
                success = false
            }
        }
        return success
        #else
        self.isInitialized = true
        return true
        #endif
    }

    /// Idempotent engine startup. Safe to call from both the app
    /// init (pre-authorized path) and the WelcomeView grant path.
    public func startEngineIfNeeded() {
        guard !isInitialized else { return }
        let paths = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
        let dbPath = paths[0].appendingPathComponent("branchdam_queue.db").path

        _ = initialize(
            dbPath: dbPath,
            baseURL: "",
            agentID: "iphone-pro",
            version: "0.1.0"
        )

        PhotoKitObserver.shared.startObserving()
    }

    /// Reported version of the bound Go engine.
    public static var engineVersion: String {
        #if canImport(branchdam)
        return branchdam.version()
        #else
        return "unavailable"
        #endif
    }

    /// Closes the engine. Idempotent.
    public func shutdown() {
        #if canImport(branchdam)
        workQueue.sync {
            _ = try? branchdam.bindingClose()
            self.isInitialized = false
        }
        #endif
    }

    public func enqueueMedia(
        localPath: String,
        filename: String,
        capturedAtUnix: Int64,
        localID: String,
        sourcePathHash: String = ""
    ) -> Int64 {
        #if canImport(branchdam)
        guard isInitialized else { return 0 }
        var outID: Int64 = 0
        workQueue.sync {
            do {
                outID = try branchdam.bindingEnqueueMediaWithSourceHash(
                    localPath,
                    filename,
                    localID,
                    "",
                    sourcePathHash,
                    capturedAtUnix,
                    0
                )
            } catch {
                NSLog("enqueueMedia failed: %@", String(describing: error))
            }
        }
        return outID
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
        #if canImport(branchdam)
        guard isInitialized else { return "" }
        var outUUID: String = ""
        workQueue.sync {
            do {
                outUUID = try branchdam.bindingEnqueueLineageEvent(
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
        return outUUID
        #else
        return UUID().uuidString
        #endif
    }

    public func enqueueDeleteEvent(nodeUUID: String) -> String {
        #if canImport(branchdam)
        guard isInitialized else { return "" }
        var outUUID: String = ""
        workQueue.sync {
            do {
                outUUID = try branchdam.bindingEnqueueDeleteEvent(nodeUUID)
            } catch {
                NSLog("enqueueDeleteEvent failed: %@", String(describing: error))
            }
        }
        return outUUID
        #else
        return UUID().uuidString
        #endif
    }

    public func syncBatch(timeoutSecs: Int32 = 120, batchSize: Int32 = 10) -> (uploaded: Int32, eventsSent: Int32) {
        #if canImport(branchdam)
        guard isInitialized else { return (0, 0) }
        var success = false
        workQueue.sync {
            do {
                try branchdam.bindingSyncBatch(Int64(timeoutSecs), Int64(batchSize))
                success = true
            } catch {
                NSLog("syncBatch failed: %@", String(describing: error))
            }
        }
        return success ? (1, 0) : (0, 0)
        #else
        return (0, 0)
        #endif
    }

    /// DB error → fail closed. Returning false here causes the shell to refuse
    /// the local delete, ensuring files are never deleted without verified offload.
    public func isMediaOffloaded(localID: String) -> Bool {
        #if canImport(branchdam)
        guard isInitialized else { return false }
        var out: Bool = false
        workQueue.sync {
            do {
                out = try branchdam.bindingIsMediaOffloaded(localID)
            } catch {
                NSLog("isMediaOffloaded failed: %@", String(describing: error))
                out = false
            }
        }
        return out
        #else
        return mockOffloadedMedia[localID] ?? false
        #endif
    }

    public func setMediaOffloaded(localID: String, isOffloaded: Bool) -> Bool {
        #if canImport(branchdam)
        guard isInitialized else { return false }
        var ok: Bool = false
        workQueue.sync {
            do {
                try branchdam.bindingSetMediaOffloaded(localID, isOffloaded)
                ok = true
            } catch {
                NSLog("setMediaOffloaded failed: %@", String(describing: error))
            }
        }
        return ok
        #else
        mockOffloadedMedia[localID] = isOffloaded
        return true
        #endif
    }

    public func fetchNamingTemplate() -> String {
        #if canImport(branchdam)
        guard isInitialized else { return "" }
        var tpl: String = ""
        workQueue.sync {
            do {
                tpl = try branchdam.bindingFetchNamingTemplate()
            } catch {
                NSLog("fetchNamingTemplate failed: %@", String(describing: error))
            }
        }
        return tpl
        #else
        return "{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}"
        #endif
    }

    public func reclaimSafeSpace(localID: String) -> (eligible: Bool, reason: String) {
        #if canImport(branchdam)
        guard isInitialized else { return (false, "engine not initialized") }
        var eligible = false
        var reason = ""
        workQueue.sync {
            do {
                try branchdam.bindingReclaimSafeSpace(localID)
                eligible = true
                reason = ""
            } catch {
                NSLog("reclaimSafeSpace failed: %@", String(describing: error))
                eligible = false
                reason = String(describing: error)
            }
        }
        return (eligible, reason)
        #else
        guard isInitialized else { return (false, "engine not initialized") }
        mockOffloadedMedia[localID] = true
        return (true, "")
        #endif
    }

    public func getAllMediaStatuses() -> [String: String] {
        #if canImport(branchdam)
        guard isInitialized else { return [:] }
        var result: [String: String] = [:]
        workQueue.sync {
            do {
                let jsonStr = try branchdam.bindingGetAllMediaStatuses()
                if let data = jsonStr.data(using: .utf8),
                   let dict = try? JSONSerialization.jsonObject(with: data) as? [String: String] {
                    result = dict
                }
            } catch {
                NSLog("getAllMediaStatuses failed: %@", String(describing: error))
            }
        }
        return result
        #else
        return [:]
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

    /// Reads active upload progress metrics without blocking the serial `workQueue`.
    /// The underlying Go binding accesses an atomic progress struct under a short mutex lock.
    public func getActiveUploadProgress() -> ActiveUploadProgress? {
        #if canImport(branchdam)
        guard isInitialized else { return nil }
        do {
            let jsonStr = try branchdam.bindingGetActiveUploadProgress()
            if !jsonStr.isEmpty,
               let data = jsonStr.data(using: .utf8),
               let decoded = try? JSONDecoder().decode(ActiveUploadProgress.self, from: data) {
                return decoded
            }
        } catch {
            NSLog("getActiveUploadProgress failed: %@", String(describing: error))
        }
        return nil
        #else
        return nil
        #endif
    }

    /// Tests connection reachability. Returns nil on success (no error),
    /// or a detailed error message string if the handshake fails.
    public func testConnectionDetailed() -> String? {
        #if canImport(branchdam)
        guard isInitialized else { return "Engine not initialized" }
        var errorMsg: String? = nil
        workQueue.sync {
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
        var result: String? = nil
        workQueue.sync {
            do {
                let jsonStr = try branchdam.bindingCheckContent(fastHash, fullHash)
                if !jsonStr.isEmpty {
                    result = jsonStr
                }
            } catch {
                NSLog("checkContent failed: %@", String(describing: error))
            }
        }
        return result
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

    /// E.4: Sets the in-process cancel flag. The next SyncUploads/SyncEvents
    /// call will observe the flag and return early. Called by the BGTask
    /// expiration handler or UI so the Go engine stops HTTP transfers promptly.
    ///
    /// IMPORTANT: This bypasses the serial workQueue because the Go engine's
    /// cancel flag is an atomic.Bool checked between upload items. Calling
    /// via workQueue.sync would deadlock when syncBatch is already running
    /// on that queue. The gomobile seq channel handles thread safety.
    public func setCancelFlag() {
        #if canImport(branchdam)
        _ = try? branchdam.bindingSetCancelFlag()
        #endif
    }
}
