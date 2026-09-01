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
/// audit UI) and the gomobile-bound `branchdam` Go engine.
///
/// All public methods are synchronous from the caller's perspective. The
/// underlying gomobile calls block (they marshal arguments and call
/// into Go over a sequence number channel), so the bridge internally
/// dispatches each call to a private serial background queue. Callers
/// should already be off-main-thread; invoking the bridge from the main
/// thread is a no-op-cost that runs synchronously.
///
/// Sub-issue A wired the bridge to the new framework. Sub-issue B
/// replaces the mock-fallback branches with real engine calls.
public class BranchDamCoreBridge {
    public static let shared = BranchDamCoreBridge()

    /// Serial queue that runs the gomobile calls. gomobile's transport
    /// is blocking; running everything on one serial queue keeps the
    /// ordering predictable and prevents concurrent Go-runtime access
    /// from a single process.
    private let workQueue = DispatchQueue(label: "com.branchdam.mobile.bridge", qos: .userInitiated)

    #if canImport(branchdam)
    private var engine: branchdam.Engine?
    #endif
    private var isInitialized = false
    private var mockOffloadedMedia: [String: Bool] = [:]

    private init() {}

    /// Initialize the Go engine. Returns true on success; surfaces
    /// INVALID_INPUT / DB_ERROR via the returned branchdam.Error code
    /// (logged, not raised, to keep the existing Bool return contract).
    public func initialize(
        dbPath: String,
        baseURL: String,
        apiKey: String = "",
        agentID: String = "iphone-companion",
        version: String = "0.1.0"
    ) -> Bool {
        #if canImport(branchdam)
        var opts = branchdam.EngineOptions()
        opts.dbPath = dbPath
        opts.baseURL = baseURL
        opts.apiKey = apiKey
        opts.agentID = agentID
        opts.clientVersion = version
        opts.httpTimeoutSec = 0
        do {
            let e = try branchdam.Engine.newEngine(opts)
            self.engine = e
            self.isInitialized = true
            return true
        } catch {
            NSLog("initialize failed: %@", String(describing: error))
            self.isInitialized = false
            return false
        }
        #else
        self.isInitialized = true
        return true
        #endif
    }

    /// Reported version of the bound Go engine. Useful for diagnostics
    /// and smoke tests confirming the artifact loaded.
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
            _ = try? self.engine?.close()
            self.engine = nil
            self.isInitialized = false
        }
        #endif
    }

    public func enqueueMedia(
        localPath: String,
        filename: String,
        capturedAtUnix: Int64,
        localID: String
    ) -> Int64 {
        #if canImport(branchdam)
        guard let engine = self.engine else { return 0 }
        let opts = branchdam.EnqueueMediaOptions()
        opts.localPath = localPath
        opts.filename = filename
        opts.capturedAtUnix = capturedAtUnix
        opts.localID = localID
        var outID: Int64 = 0
        workQueue.sync {
            do {
                outID = try engine.enqueueMedia(opts)
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
        guard let engine = self.engine else { return "" }
        var outUUID: String = ""
        workQueue.sync {
            do {
                outUUID = try engine.enqueueLineageEvent(
                    parentLocalID: parentUUID,
                    childLocalID: childUUID,
                    relationshipType: relationshipType,
                    resolver: resolver,
                    confidence: branchdam.Confidence(value: Float(confidence))
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
        guard let engine = self.engine else { return "" }
        var outUUID: String = ""
        workQueue.sync {
            do {
                outUUID = try engine.enqueueDeleteEvent(localID: nodeUUID)
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
        guard let engine = self.engine else { return (0, 0) }
        let opts = branchdam.SyncOptions()
        opts.timeoutSecs = Int(timeoutSecs)
        opts.batchSize = Int(batchSize)
        opts.includeEvents = true
        opts.includeUploads = true
        var uploaded: Int32 = 0
        var events: Int32 = 0
        workQueue.sync {
            do {
                let result = try engine.syncBatch(opts)
                uploaded = Int32(result.uploaded)
                events = Int32(result.eventsSent)
            } catch {
                NSLog("syncBatch failed: %@", String(describing: error))
            }
        }
        return (uploaded, events)
        #else
        return (0, 0)
        #endif
    }

    public func isMediaOffloaded(localID: String) -> Bool {
        #if canImport(branchdam)
        guard let engine = self.engine else { return false }
        var out: Bool = false
        workQueue.sync {
            do {
                out = try engine.isMediaOffloaded(localID: localID)
            } catch {
                // B.2.3: DB error → fail closed. Returning false here
                // causes the shell to refuse the local delete, which
                // is exactly the invariant the audit calls out.
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
        guard let engine = self.engine else { return false }
        var ok: Bool = false
        workQueue.sync {
            do {
                try engine.setMediaOffloaded(localID: localID, isOffloaded: isOffloaded)
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
        guard let engine = self.engine else { return "" }
        var tpl: String = ""
        workQueue.sync {
            do {
                tpl = try engine.fetchNamingTemplate()
            } catch {
                NSLog("fetchNamingTemplate failed: %@", String(describing: error))
            }
        }
        return tpl
        #else
        return "{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}"
        #endif
    }

    /// Engine-owned atomic reclaim. Returns the verdict so the shell
    /// can decide whether to delete the local file. The engine does
    /// the server re-check + the local flag set in one logical
    /// operation (B.2.7).
    public func reclaimSafeSpace(localID: String) -> (eligible: Bool, reason: String) {
        #if canImport(branchdam)
        guard let engine = self.engine else { return (false, "engine not initialized") }
        var eligible = false
        var reason = ""
        workQueue.sync {
            do {
                let v = try engine.reclaimSafeSpace(localID: localID)
                eligible = v.eligible
                reason = v.reason
            } catch {
                NSLog("reclaimSafeSpace failed: %@", String(describing: error))
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

    /// E.4: Sets the in-process cancel flag. The next SyncUploads/SyncEvents
    /// call will observe the flag and return early. Called by the BGTask
    /// expiration handler so the Go engine stops HTTP transfers promptly.
    public func setCancelFlag() {
        #if canImport(branchdam)
        guard let engine = self.engine else { return }
        workQueue.sync {
            _ = try? engine.setCancelFlag()
        }
        #endif
    }
}
